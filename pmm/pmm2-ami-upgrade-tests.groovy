library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runAMIStagingStart(AMI_ID, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, AMI_UPGRADE_TESTING_INSTANCE) {
    amiStagingJob = build job: 'pmm2-ami-staging-start', parameters: [
        string(name: 'AMI_ID', value: AMI_ID),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'AMI_UPGRADE_TESTING_INSTANCE', value: AMI_UPGRADE_TESTING_INSTANCE)
    ]
    env.AMI_INSTANCE_ID = amiStagingJob.buildVariables.INSTANCE_ID
    env.AMI_INSTANCE_IP = amiStagingJob.buildVariables.IP
    env.VM_IP = env.AMI_INSTANCE_IP
    env.PMM_URL = "http://admin:admin@${AMI_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${AMI_INSTANCE_IP}"
}

void runAMIStaginStop(INSTANCE_ID) {
    amiStagingStopJob = build job: 'pmm2-ami-staging-stop', parameters: [
        string(name: 'AMI_ID', value: INSTANCE_ID),
    ]
}

void customSetupAMIInstance(INSTANCE_IP) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${INSTANCE_IP} '
                sudo chmod 755 /srv/pmm-qa/pmm-tests/pmm-framework.sh
                bash -x /srv/pmm-qa/pmm-tests/pmm-framework.sh --pmm2 --setup-custom-ami
            '
        """
    }
}

void setInstanceAMIId(PMM_VERSION) {
    switch(PMM_VERSION) {
        case "2.15.0":
            env.AMI_ID = "ami-086a3a95eefa9567f"
            break;
        case "2.15.1":
            env.AMI_ID = "ami-073928dbea8c7ebc3"
            break;
        case "2.16.0":
            env.AMI_ID = "ami-01097b383f63f7db5"
            break;
        case "2.17.0":
            env.AMI_ID = "ami-03af848f3557ff8d0"
            break;
        case "2.18.0":
            env.AMI_ID = "ami-0184c7b18b45d2a7b"
            break;
        case "2.19.0":
            env.AMI_ID = "ami-0d3c21da426d248d3"
            break;
        case "2.20.0":
            env.AMI_ID = "ami-04ad85dd7364bba21"
            break;
        case "2.21.0":
            env.AMI_ID = "ami-0605d9dbdc9d6a233"
            break;
        case "custom":
            env.AMI_ID = env.AMI_ID_CUSTOM
            break;
    }
}

void runStagingClient(CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_CLIENT_IP = stagingJob.buildVariables.IP
    env.VM_CLIENT_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:admin@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "http://admin:admin@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}/"
    }
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void checkUpgrade(String PMM_VERSION, String PRE_POST) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins-admin', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no admin@${VM_IP} '
                export PMM_VERSION=${PMM_VERSION}
                sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.sh
                bash -xe /srv/pmm-qa/pmm-tests/check_upgrade.sh ${PMM_VERSION} ${PRE_POST} ami
            '
        """
    }
}

void checkClientAfterUpgrade(String PMM_VERSION, String PRE_POST) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_CLIENT_IP} '
                export PMM_VERSION=${PMM_VERSION}
                echo "Upgrading pmm2-client";
                sudo yum clean all
                sudo yum makecache
                sudo yum -y install pmm2-client
                sudo yum -y update
                sudo chmod 755 /srv/pmm-qa/pmm-tests/check_client_upgrade.sh
                bash -xe /srv/pmm-qa/pmm-tests/check_client_upgrade.sh ${PMM_VERSION} ${PRE_POST}
            '
        """
    }
}

void fetchAgentLog(String CLIENT_VERSION) {
     withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_CLIENT_IP} '
                set -o errexit
                set -o xtrace
                export CLIENT_VERSION=${CLIENT_VERSION}
                if [[ \$CLIENT_VERSION != http* ]]; then
                    journalctl -u pmm-agent.service > /var/log/pmm-agent.log
                    sudo chmod 777 /var/log/pmm-agent.log
                fi
                if [[ -e /var/log/pmm-agent.log ]]; then
                    cp /var/log/pmm-agent.log .
                fi
            '
            if [[ \$CLIENT_VERSION != http* ]]; then
                scp -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                    ${USER}@${VM_CLIENT_IP}:pmm-agent.log \
                    pmm-agent.log
            fi
        """
    }
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            if [[ \$CLIENT_VERSION == http* ]]; then
                scp -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                    ${USER}@${VM_CLIENT_IP}:workspace/aws-staging-start/pmm-agent.log \
                    pmm-agent.log
            fi
        """
    }
}

pipeline {
    agent {
        label 'large-amazon'
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
        GCP_SERVER_IP=credentials('GCP_SERVER_IP')
        GCP_USER=credentials('GCP_USER')
        GCP_USER_PASSWORD=credentials('GCP_USER_PASSWORD')
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for UI Tests Repo repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'PMM Server AMI ID',
            name: 'AMI_ID_CUSTOM')
        choice(
            choices: ['2.15.0','2.15.1', '2.16.0', '2.17.0', '2.18.0', '2.19.0', '2.20.0', '2.21.0', 'custom'],
            description: 'PMM Server AMI ID to test for Upgrade',
            name: 'SERVER_VERSION')
        choice(
            choices: ['2.15.0', '2.15.1', '2.16.0', '2.17.0', '2.18.0', '2.19.0', '2.20.0', '2.21.0'],
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '2.22.0',
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo, for RC testing',
            name: 'ENABLE_TESTING_REPO')
        choice(
            choices: ['true', 'false'],
            description: 'Enable to setup Docker-compose for remote instances',
            name: 'AMI_UPGRADE_TESTING_INSTANCE')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-ui-tests repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-ui-tests.git'

                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                installDocker()
                setInstanceAMIId(SERVER_VERSION)
                sh '''
                    sudo yum -y install jq svn
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                        sudo chmod 755 pmm-tests/install-google-chrome.sh
                        bash ./pmm-tests/install-google-chrome.sh
                        sudo svn export https://github.com/Percona-QA/percona-qa.git/trunk/get_download_link.sh
                        sudo chmod 755 get_download_link.sh
                    popd
                    sudo ln -s /usr/bin/google-chrome-stable /usr/bin/chromium
                '''
            }
        }
        stage('Start AMI Server') {
            steps {
                runAMIStagingStart(AMI_ID, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO, AMI_UPGRADE_TESTING_INSTANCE)
                customSetupAMIInstance(AMI_INSTANCE_IP)
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Start Client Instance') {
            steps {
                runStagingClient(CLIENT_VERSION, '--addclient=ps,1 --setup-with-custom-queries --setup-remote-db', 'yes', AMI_INSTANCE_IP, PMM_QA_GIT_BRANCH, ENABLE_TESTING_REPO)
            }
        }
        stage('Sleep') {
            steps {
                sleep 60
            }
        }
        stage('Check Packages before Upgrade') {
            steps {
                checkUpgrade(SERVER_VERSION, "pre");
            }
        }
        stage('Run UI Upgrade Tests') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        curl --silent --location https://rpm.nodesource.com/setup_14.x | sudo bash -
                        sudo yum -y install nodejs
                        npm install
                        node -v
                        npm -v
                        sudo yum install -y gettext
                        envsubst < env.list > env.generated.list
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export CHROMIUM_PATH=/usr/bin/chromium
                        ./node_modules/.bin/codeceptjs run --debug --steps --reporter mocha-multi -c pr.codecept.js --grep '@ami-upgrade'
                    """
                    }
                }
        }
        stage('Check Packages after Upgrade') {
            steps {
                checkUpgrade(PMM_SERVER_LATEST, "post");
            }
        }
        stage('Check Client Upgrade') {
            steps {
                checkClientAfterUpgrade(PMM_SERVER_LATEST, "post");
            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
            fetchAgentLog(CLIENT_VERSION)
            script {
                if(env.AMI_INSTANCE_IP) {
                    runAMIStaginStop(AMI_INSTANCE_ID)
                }
                if(env.VM_CLIENT_NAME)
                {
                    destroyStaging(VM_CLIENT_IP)
                }
            }
            sh '''
                ./node_modules/.bin/mochawesome-merge tests/output/*.json > tests/output/combine_results.json || true
                ./node_modules/.bin/marge tests/output/combine_results.json --reportDir tests/output/ --inline --cdn --charts || true
            '''
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit 'tests/output/*.xml'
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                    archiveArtifacts artifacts: 'tests/output/combine_results.html'
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'pmm-agent.log'
                } else {
                    junit 'tests/output/*.xml'
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'tests/output/combine_results.html'
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'pmm-agent.log'
                    archiveArtifacts artifacts: 'tests/output/*.png'
                }
            }
            allure([
                includeProperties: false,
                jdk: '',
                properties: [],
                reportBuildPolicy: 'ALWAYS',
                results: [[path: 'tests/output/allure']]
            ])
            sh '''
                sudo rm -r node_modules/
                sudo rm -r tests/output
            '''
            deleteDir()
        }
    }
}
