import hudson.model.Node.Mode
import hudson.slaves.*
import jenkins.model.Jenkins
import hudson.plugins.sshslaves.SSHLauncher


library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runOVFStagingStart(SERVER_VERSION, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, ENABLE_EXPERIMENTAL_REPO) {
    ovfStagingJob = build job: 'pmm2-ovf-staging-start', parameters: [
        string(name: 'OVA_VERSION', value: SERVER_VERSION),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ENABLE_EXPERIMENTAL_REPO', value: ENABLE_EXPERIMENTAL_REPO)
    ]
    env.OVF_INSTANCE_NAME = ovfStagingJob.buildVariables.VM_NAME
    env.OVF_INSTANCE_IP = ovfStagingJob.buildVariables.IP
    env.PMM_URL = "http://admin:admin@${OVF_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${OVF_INSTANCE_IP}"
}

void customSetupOVFInstance(INSTANCE_IP, OVF_INSTANCE_NAME) {
    node(env.OVF_INSTANCE_NAME) {
        withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
            sh """
                ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${INSTANCE_IP} '
                    export OVF_INSTANCE_NAME=${OVF_INSTANCE_NAME}
                    sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.sh
                    bash -x /srv/pmm-qa/pmm-tests/pmm-framework.sh --pmm2 --setup-custom-ami
                '
            """
        }
    }
}

void runStagingClient(CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, NODE_TYPE) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'ADMIN_PASSWORD', value: 'admin')
    ]
    if ( NODE_TYPE == 'remote-node' ) {
        env.VM_CLIENT_IP = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME = stagingJob.buildVariables.VM_NAME
    } else {
        env.VM_CLIENT_IP_DB = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_DB = stagingJob.buildVariables.VM_NAME
    }

    if ( CLIENT_INSTANCE == "yes" ) {
        env.PMM_URL = "http://admin:admin@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    } else {
        env.PMM_URL = "http://admin:admin@${OVF_INSTANCE_IP}"
        env.PMM_UI_URL = "http://${OVF_INSTANCE_IP}/"
    }
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void checkUpgrade(String PMM_VERSION, String PRE_POST, String OVF_INSTANCE_NAME) {
    node(env.OVF_INSTANCE_NAME) {
        withCredentials([sshUserPrivateKey(credentialsId: 'OVF_VM_TESTQA', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
            sh """
                ssh -i "${KEY_PATH}" -p 3022 -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${OVF_INSTANCE_IP} '
                    sudo yum install -y python3
                    export PMM_VERSION=${PMM_VERSION}
                    export OVF_INSTANCE_NAME=${OVF_INSTANCE_NAME}
                    sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.py
                    python3 /srv/pmm-qa/pmm-tests/check_upgrade.py --env=ami --pre_post=${PRE_POST} --version=${PMM_VERSION}
                '
            """
        }
    }
}

void checkClientAfterUpgrade(String PMM_VERSION) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_CLIENT_IP_DB} '
                export PMM_VERSION=${PMM_VERSION}
                echo "Upgrading pmm2-client";
                sudo yum clean all
                sudo yum makecache
                sudo yum -y install pmm2-client
                sleep 20
                sudo chmod 755 /srv/pmm-qa/pmm-tests/check_client_upgrade.sh
                bash -xe /srv/pmm-qa/pmm-tests/check_client_upgrade.sh ${PMM_VERSION}
            '
        """
    }
}

void fetchAgentLog(String CLIENT_VERSION) {
     withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_CLIENT_IP_DB} '
                set -o errexit
                set -o xtrace
                export CLIENT_VERSION=${CLIENT_VERSION}
                if [[ \$CLIENT_VERSION != http* ]]; then
                    journalctl -u pmm-agent.service > pmm-agent.log
                    sudo chown ec2-user:ec2-user pmm-agent.log
                fi
            '
            if [[ \$CLIENT_VERSION != http* ]]; then
                scp -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                    ${USER}@${VM_CLIENT_IP_DB}:pmm-agent.log \
                    pmm-agent.log
            fi
        """
    }
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            if [[ \$CLIENT_VERSION == http* ]]; then
                scp -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                    ${USER}@${VM_CLIENT_IP_DB}:workspace/aws-staging-start/pmm-agent.log \
                    pmm-agent.log
            fi
        """
    }
}

def latestVersion = pmmVersion()
def versionsList = pmmVersion('ovf')

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    environment {
        REMOTE_AWS_MYSQL_USER=credentials('pmm-dev-mysql-remote-user')
        REMOTE_AWS_MYSQL_PASSWORD=credentials('pmm-dev-remote-password')
        REMOTE_AWS_MYSQL57_HOST=credentials('pmm-dev-mysql57-remote-host')
        REMOTE_MYSQL_HOST=credentials('mysql-remote-host')
        REMOTE_MYSQL_USER=credentials('mysql-remote-user')
        REMOTE_MYSQL_PASSWORD=credentials('mysql-remote-password')
        REMOTE_MONGODB_HOST=credentials('qa-remote-mongodb-host')
        REMOTE_MONGODB_USER=credentials('qa-remote-mongodb-user')
        REMOTE_MONGODB_PASSWORD=credentials('qa-remote-mongodb-password')
        REMOTE_POSTGRESQL_HOST=credentials('qa-remote-pgsql-host')
        REMOTE_POSTGRESQL_USER=credentials('qa-remote-pgsql-user')
        REMOTE_POSTGRESSQL_PASSWORD=credentials('qa-remote-pgsql-password')
        REMOTE_PROXYSQL_HOST=credentials('qa-remote-proxysql-host')
        REMOTE_PROXYSQL_USER=credentials('qa-remote-proxysql-user')
        REMOTE_PROXYSQL_PASSWORD=credentials('qa-remote-proxysql-password')
        INFLUXDB_ADMIN_USER=credentials('influxdb-admin-user')
        INFLUXDB_ADMIN_PASSWORD=credentials('influxdb-admin-password')
        INFLUXDB_USER=credentials('influxdb-user')
        INFLUXDB_USER_PASSWORD=credentials('influxdb-user-password')
        MONITORING_HOST=credentials('monitoring-host')
        MAILOSAUR_API_KEY=credentials('MAILOSAUR_API_KEY')
        MAILOSAUR_SERVER_ID=credentials('MAILOSAUR_SERVER_ID')
        MAILOSAUR_SMTP_PASSWORD=credentials('MAILOSAUR_SMTP_PASSWORD')
        PMM_QA_AURORA2_MYSQL_HOST=credentials('PMM_QA_AURORA2_MYSQL_HOST')
        PMM_QA_AURORA2_MYSQL_PASSWORD=credentials('PMM_QA_AURORA2_MYSQL_PASSWORD')
        PMM_QA_AWS_ACCESS_KEY_ID=credentials('PMM_QA_AWS_ACCESS_KEY_ID')
        PMM_QA_AWS_ACCESS_KEY=credentials('PMM_QA_AWS_ACCESS_KEY')
        GCP_SERVER_IP=credentials('GCP_SERVER_IP')
        GCP_USER=credentials('GCP_USER')
        GCP_USER_PASSWORD=credentials('GCP_USER_PASSWORD')
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for UI Tests Repo repository',
            name: 'GIT_BRANCH')
        choice(
            choices: versionsList,
            description: 'OVA Image version, for installing already released version, pass 2.x.y ex. 2.28.0',
            name: 'SERVER_VERSION')
        choice(
            choices: versionsList,
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo, for RC testing',
            name: 'ENABLE_TESTING_REPO')
        choice(
            choices: ['yes', 'no'],
            description: 'Enable Experimental, for Dev Latest testing',
            name: 'ENABLE_EXPERIMENTAL_REPO')
        choice(
            choices: ['true', 'false'],
            description: 'Enable to setup Docker-compose for remote instances',
            name: 'OVF_UPGRADE_TESTING_INSTANCE')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                // fetch pmm-ui-tests repository
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-ui-tests.git'

                slackSend channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                installDocker()
                sh '''
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                        sudo wget https://raw.githubusercontent.com/Percona-QA/percona-qa/master/get_download_link.sh
                        sudo chmod 755 get_download_link.sh
                    popd
                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium
                '''
            }
        }
        stage('Start OVF Server') {
            steps {
                runOVFStagingStart(SERVER_VERSION, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, ENABLE_EXPERIMENTAL_REPO)
                script {
                    SSHLauncher ssh_connection = new SSHLauncher(OVF_INSTANCE_IP, 22, 'e54a801f-e662-4e3c-ace8-0d96bec4ce0e')
                    DumbSlave node = new DumbSlave(OVF_INSTANCE_NAME, "OVA staging instance: ${OVF_INSTANCE_NAME}", "/root", "1", Mode.EXCLUSIVE, "", ssh_connection, RetentionStrategy.INSTANCE)
                    Jenkins.instance.addNode(node)
                }
                customSetupOVFInstance(OVF_INSTANCE_IP, OVF_INSTANCE_NAME)
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Setup PMM Client Instances, Remote and Actual DB clients') {
            parallel {
                stage('Start Client Instance Remote Instance') {
                    steps {
                        runStagingClient(CLIENT_VERSION, '--setup-remote-db', 'yes', OVF_INSTANCE_IP, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, 'remote-node')
                    }
                }
                stage('Start Client Instance DB connect Instance') {
                    steps {
                        runStagingClient(CLIENT_VERSION, '--addclient=modb,1 --addclient=pgsql,1 --addclient=ps,1 --setup-with-custom-queries', 'yes', OVF_INSTANCE_IP, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, 'db-node')
                    }
                }
            }
        }
        stage('Sleep') {
            steps {
                sleep 60
            }
        }
        stage('Check Packages before Upgrade') {
            steps {
                checkUpgrade(SERVER_VERSION, "pre", OVF_INSTANCE_NAME);
            }
        }
        stage('Run UI Upgrade Tests') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        envsubst < env.list > env.generated.list
                        npm ci
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export CHROMIUM_PATH=/usr/bin/chromium
                        ./node_modules/.bin/codeceptjs run --reporter mocha-multi -c pr.codecept.js --grep '@ovf-upgrade'
                    """
                    }
                }
        }
        stage('Check Packages after Upgrade') {
            steps {
                checkUpgrade(PMM_SERVER_LATEST, "post", OVF_INSTANCE_NAME);
            }
        }
        stage('Check Client Upgrade') {
            steps {
                checkClientAfterUpgrade(PMM_SERVER_LATEST);
                sh """
                    export PWD=\$(pwd);
                    export CHROMIUM_PATH=/usr/bin/chromium
                    sleep 30
                    ./node_modules/.bin/codeceptjs run --reporter mocha-multi -c pr.codecept.js --grep '(?=.*@post-client-upgrade)(?=.*@ovf-upgrade)'
                """
            }
        }
    }
    post {
        // https://www.jenkins.io/doc/book/pipeline/syntax/#post-conditions
        always {
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
            script {
                // JUnit is known to fail if there are no files in `tests/output`
                try {
                    junit 'tests/output/*.xml'
                } catch (err) {
                    echo err.getMessage()
                }
                fetchAgentLog(CLIENT_VERSION)
                archiveArtifacts artifacts: 'logs.zip'
                archiveArtifacts artifacts: 'pmm-agent.log'
            }
            /*
            allure([
                includeProperties: false,
                jdk: '',
                properties: [],
                reportBuildPolicy: 'ALWAYS',
                results: [[path: 'tests/output/allure']]
            ])
            */
        }
        success {
            script {
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
            }

        }
        failure {
            script {
                archiveArtifacts artifacts: 'tests/output/*.png'
                slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
            }
        }
        cleanup {
            script {
                if (env.OVF_INSTANCE_IP) {
                    ovfStagingStopJob = build job: 'pmm2-ovf-staging-stop', parameters: [
                        string(name: 'VM', value: env.OVF_INSTANCE_NAME),
                    ]
                }
                if (env.VM_CLIENT_NAME) {
                    destroyStaging(VM_CLIENT_IP)
                }
                if (env.VM_CLIENT_IP_DB) {
                    destroyStaging(VM_CLIENT_IP_DB)
                }
                def node = Jenkins.instance.getNode(env.OVF_INSTANCE_NAME)
                if (node) {
                    Jenkins.instance.removeNode(node)
                }
            }
        }
    }
}
