library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e DATA_RETENTION=48h -e PERCONA_TEST_SAAS_HOST=check-dev.percona.com -e PERCONA_TEST_PLATFORM_ADDRESS=https://check-dev.percona.com -e PERCONA_TEST_CHECKS_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PERCONA_TEST_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PERCONA_TEST_CHECKS_INTERVAL=10s'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}/"
    }
}

void runStagingClient(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, NODE_TYPE, ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, MO_VERSION, MODB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'QUERY_SOURCE', value: 'slowlog'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'ENABLE_PULL_MODE', value: ENABLE_PULL_MODE),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'PXC_VERSION', value: PXC_VERSION),
        string(name: 'PS_VERSION', value: PS_VERSION),
        string(name: 'MS_VERSION', value: MS_VERSION),
        string(name: 'PGSQL_VERSION', value: PGSQL_VERSION),
        string(name: 'PDPGSQL_VERSION', value: PDPGSQL_VERSION),
        string(name: 'MD_VERSION', value: MD_VERSION),
        string(name: 'MO_VERSION', value: MO_VERSION),
        string(name: 'MODB_VERSION', value: MODB_VERSION),
        string(name: 'QUERY_SOURCE', value: QUERY_SOURCE),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    if ( NODE_TYPE == 'mysql-node' ) {
        env.VM_CLIENT_IP_MYSQL = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_MYSQL = stagingJob.buildVariables.VM_NAME
    }
    else if ( NODE_TYPE == 'pxc-node' ) {
        env.VM_CLIENT_IP_PXC = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_PXC = stagingJob.buildVariables.VM_NAME
    }
    else if ( NODE_TYPE == 'postgres-node' ) {
        env.VM_CLIENT_IP_PGSQL = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_PGSQL = stagingJob.buildVariables.VM_NAME
    }
    else
    {
        env.VM_CLIENT_IP_MONGO = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_MONGO = stagingJob.buildVariables.VM_NAME
    }
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}/"
    }
}

void checkClientNodesAgentStatus(String VM_CLIENT_IP) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_CLIENT_IP} '
                set -o errexit
                set -o xtrace
                echo "Checking Agent Status on Client Nodes";
                sudo chmod 755 /srv/pmm-qa/pmm-tests/agent_status.sh
                bash -xe /srv/pmm-qa/pmm-tests/agent_status.sh
            '
        """
    }
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void uploadAllureArtifacts() {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            scp -r -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                tests/output/allure aws-jenkins@${MONITORING_HOST}:/home/aws-jenkins/allure-reports
        """
    }
}
pipeline {
    agent {
        label 'min-focal-x64'
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
        GCP_MYSQL57_HOST=credentials('GCP_MYSQL57_HOST');
        GCP_MYSQL57_USER=credentials('GCP_MYSQL57_USER');
        GCP_MYSQL57_PASSWORD=credentials('GCP_MYSQL57_PASSWORD');
        GCP_MYSQL80_HOST=credentials('GCP_MYSQL80_HOST');
        GCP_MYSQL80_USER=credentials('GCP_MYSQL80_USER');
        GCP_MYSQL80_PASSWORD=credentials('GCP_MYSQL80_PASSWORD');
        GCP_PGSQL13_HOST=credentials('GCP_PGSQL13_HOST');
        GCP_PGSQL13_USER=credentials('GCP_PGSQL13_USER');
        GCP_PGSQL13_PASSWORD=credentials('GCP_PGSQL13_PASSWORD');
        GCP_PGSQL12_HOST=credentials('GCP_PGSQL12_HOST');
        GCP_PGSQL12_USER=credentials('GCP_PGSQL12_USER');
        GCP_PGSQL12_PASSWORD=credentials('GCP_PGSQL12_PASSWORD');
        GCP_PGSQL14_HOST=credentials('GCP_PGSQL14_HOST');
        GCP_PGSQL14_USER=credentials('GCP_PGSQL14_USER');
        GCP_PGSQL14_PASSWORD=credentials('GCP_PGSQL14_PASSWORD');
        GCP_PGSQL11_HOST=credentials('GCP_PGSQL11_HOST');
        GCP_PGSQL11_USER=credentials('GCP_PGSQL11_USER');
        GCP_PGSQL11_PASSWORD=credentials('GCP_PGSQL11_PASSWORD');
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        choice(
            choices: ['no', 'yes'],
            description: "Use this instance only as a client host",
            name: 'CLIENT_INSTANCE')
        choice(
            choices: ['no', 'yes'],
            description: "Run AMI Setup Wizard for AMI UI tests",
            name: 'AMI_TEST')
        string(
            defaultValue: '',
            description: 'AMI Instance ID',
            name: 'AMI_INSTANCE_ID')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Pull Mode, if you are using this instance as Client Node',
            name: 'ENABLE_PULL_MODE')
        string(
            defaultValue: 'admin-password',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')  
        string (
            defaultValue: '',
            description: 'Value for Server Public IP, to use this instance just as client',
            name: 'SERVER_IP')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['8.0','5.7'],
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        choice(
            choices: ['8.0', '5.7', '5.7.30', '5.6'],
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION')
        choice(
            choices: ['8.0', '5.7', '5.6'],
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        choice(
            choices: ['13', '12', '11', '10.8'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        choice(
            choices: ['14.4','14.3','14.2', '14.1', '14.0', '13.7', '13.6', '13.4', '13.2', '13.1', '12.11', '12.10', '12.8', '11.16', '11.15', '11.13'],
            description: 'Percona Distribution for PostgreSQL',
            name: 'PDPGSQL_VERSION')
        choice(
            choices: ['10.6', '10.5', '10.4', '10.3', '10.2'],
            description: "MariaDB Server version",
            name: 'MD_VERSION')
        choice(
            choices: ['4.4', '4.2', '4.0', '3.6'],
            description: "Percona Server for MongoDB version",
            name: 'MO_VERSION')
        choice(
            choices: ['4.4', '4.2', '4.0', '5.0.2'],
            description: "Official MongoDB version from MongoDB Inc",
            name: 'MODB_VERSION')
        choice(
            choices: ['slowlog', 'perfschema'],
            description: "Query Source for Monitoring",
            name: 'QUERY_SOURCE')
    }
    options {
        skipDefaultCheckout()
    }
    triggers { cron('0 0 * * *') }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-ui-tests repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-ui-tests.git'

                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                sh '''
                    sudo mkdir -p /srv/pmm-qa || :
                    sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git /srv/pmm-qa
                    sudo chmod -R 755 /srv/pmm-qa
                '''
            }
        }
        stage('Checkout Commit') {
             when {
                expression { env.GIT_COMMIT_HASH.length()>0 }
            }
            steps {
                sh 'git checkout ' + env.GIT_COMMIT_HASH
            }
        }
        stage('Start Server') {
            steps {
                runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--addclient=haproxy,1 --setup-alertmanager --setup-external-service', CLIENT_INSTANCE, '127.0.0.1', ADMIN_PASSWORD)
            }
        }
        stage('Setup PMM Client and Kubernetes Cluster') {
            parallel {
                stage('Start Client Instance - ps-replication') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1 --pmm2 --add-annotation --setup-replication-ps-pmm2 --group', 'yes', env.VM_IP, 'mysql-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, MO_VERSION, MODB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
                stage('Start Client Instance - ms/md/pxc') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ms,1 --addclient=md,1 --addclient=pxc,3 --with-proxysql --pmm2', 'yes', env.VM_IP, 'pxc-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, MO_VERSION, MODB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
                stage('Start Client Instance - mongo and pgsql') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--addclient=mo,1 --with-replica --mongomagic --addclient=pgsql,1  --pmm2', 'yes', env.VM_IP, 'mongo-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, MO_VERSION, MODB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
                stage('Start Client Instance - postgresql only pdpgsql') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--addclient=pdpgsql,1 --pmm2', 'yes', env.VM_IP, 'postgres-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, MO_VERSION, MODB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Setup Node') {
            steps {
                sh """
                    curl -sL https://deb.nodesource.com/setup_14.x -o nodesource_setup.sh
                    sudo bash nodesource_setup.sh
                    sudo apt install nodejs
                    sudo apt-get install -y gettext
                    npm ci
                    npx playwright install
                    sudo npx playwright install-deps
                    envsubst < env.list > env.generated.list
                """
            }
        }
        stage('Sleep') {
            steps {
                sleep 300
            }
        }
        stage('Run Tests') {
            parallel {
                stage('Run UI - Tests') {
                    options {
                        timeout(time: 150, unit: "MINUTES")
                    }
                    when {
                        expression { env.AMI_TEST == "no" }
                    }
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh """
                                sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                                export PWD=\$(pwd);
                                npx codeceptjs run --steps --reporter mocha-multi -c pr.codecept.js --grep '@qan|@nightly|@menu' --override '{ "helpers": { "Playwright": { "browser": "firefox" }}}'
                            """
                        }
                    }
                }
                stage('Check Agent Status on ps & replication node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_MYSQL)
                    }
                }
                stage('Check Agent Status on ms/md/pxc node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_PXC)
                    }
                }
                stage('Check Agent Status on mongo node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_MONGO)
                    }
                }
                stage('Check Agent Status on postgresql node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_PGSQL)
                    }
                }

            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
            script {
                if(env.VM_NAME)
                {
                    destroyStaging(VM_NAME)
                }
                if(env.VM_CLIENT_NAME_MYSQL)
                {
                    destroyStaging(VM_CLIENT_NAME_MYSQL)
                }
                if(env.VM_CLIENT_NAME_MONGO)
                {
                    destroyStaging(VM_CLIENT_NAME_MONGO)
                }
                if(env.VM_CLIENT_NAME_PXC)
                {
                    destroyStaging(VM_CLIENT_NAME_PXC)
                }
                if(env.VM_CLIENT_NAME_PGSQL)
                {
                    destroyStaging(VM_CLIENT_NAME_PGSQL)
                }
            }
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit 'tests/output/*.xml'
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    junit 'tests/output/*.xml'
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'logs.zip'
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
