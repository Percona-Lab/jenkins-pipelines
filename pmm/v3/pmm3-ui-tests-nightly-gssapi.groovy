library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, PMM_QA_GIT_BRANCH, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PMM_DATA_RETENTION=48h -e PMM_DEV_PORTAL_URL=https://portal-dev.percona.com -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com:443 -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTkF7Snv08FCboTne4djQfN5qbrLfAjb8SY3/wwEP+X5nUrkxCEvUDJ'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
        env.PMM_UI_URL = "https://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${VM_IP}"
        env.PMM_UI_URL = "https://${VM_IP}/"
    }
}

void runStagingClient(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, NODE_TYPE, ENABLE_PULL_MODE, PSMDB_VERSION, MODB_VERSION , QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'ENABLE_PULL_MODE', value: ENABLE_PULL_MODE),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'PSMDB_VERSION', value: PSMDB_VERSION),
        string(name: 'MODB_VERSION', value: MODB_VERSION),
        string(name: 'PMM_QA_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    if ( NODE_TYPE == 'mongo-node' ) {
        env.VM_CLIENT_IP_MYSQL = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_MYSQL = stagingJob.buildVariables.VM_NAME
    } else if ( NODE_TYPE == 'sharded-psmdb' ) {
        env.VM_CLIENT_IP_PSMDB_SHARDED = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_PSMDB_SHARDED = stagingJob.buildVariables.VM_NAME
    } else {
        env.VM_CLIENT_IP_MONGO = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_MONGO = stagingJob.buildVariables.VM_NAME
    }
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void checkClientNodesAgentStatus(String VM_CLIENT_IP, PMM_QA_GIT_BRANCH) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_CLIENT_IP} '
                set -o errexit
                set -o xtrace
                echo "Checking Agent Status on Client Nodes";
                sudo mkdir -p /srv/pmm-qa || :
                sudo git clone --single-branch --branch $PMM_QA_GIT_BRANCH https://github.com/percona/pmm-qa.git /srv/pmm-qa
                sudo chmod -R 755 /srv/pmm-qa
                sudo chmod 755 /srv/pmm-qa/support_scripts/agent_status.py
                python3 /srv/pmm-qa/support_scripts/agent_status.py
            '
        """
    }
}

pipeline {
    agent {
        label 'min-noble-x64'
    }
    environment {
        REMOTE_AWS_MYSQL_USER=credentials('pmm-dev-mysql-remote-user')
        REMOTE_AWS_MYSQL_PASSWORD=credentials('pmm-dev-remote-password')
        REMOTE_AWS_MYSQL57_HOST=credentials('pmm-dev-mysql57-remote-host')
        OKTA_TOKEN=credentials('OKTA_TOKEN')
        PORTAL_BASE_URL=credentials('PORTAL_BASE_URL')
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
        ZEPHYR_PMM_API_KEY=credentials('ZEPHYR_PMM_API_KEY');
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['docker', 'ovf', 'ami'],
            description: "PMM Server installation type.",
            name: 'SERVER_TYPE')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-dynamic-ol9-latest.tar.gz',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Pull Mode, if you are using this instance as Client Node',
            name: 'ENABLE_PULL_MODE')
        string(
            defaultValue: 'pmm3admin!',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['8.0', '7.0', '6.0', '5.0', '4.4'],
            description: "Percona Server for MongoDB version",
            name: 'PSMDB_VERSION')
        choice(
            choices: ['8.0', '7.0', '6.0', '5.0', '4.4'],
            description: "Official MongoDB version",
            name: 'MODB_VERSION')
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

                slackSend botUser: true, channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                sh '''
                    sudo mkdir -p /srv/qa-integration || :
                    sudo git clone --single-branch --branch \${QA_INTEGRATION_GIT_BRANCH} https://github.com/Percona-Lab/qa-integration.git /srv/qa-integration
                    sudo chmod -R 755 /srv/qa-integration

                '''
            }
        }
        stage('Setup Docker Server Instance') {
            when {
                expression { env.SERVER_TYPE == "docker" }
            }
            steps {
                runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--help', 'no', '127.0.0.1', QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
            }
        }
        stage('Sanity check') {
            steps {
                sh '''
                    timeout 100 bash -c 'while [[ ! "$(curl -i -s --insecure -w "%{http_code}" \${PMM_URL}/ping)" =~ "200" ]]; do sleep 5; echo "$(curl -i -s --insecure -w "%{http_code}" \${PMM_URL}/ping)"; done' || false
                '''
            }
        }
        stage('Setup PMM Clients') {
            parallel {
                stage('Mongo pss client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database psmdb,SETUP_TYPE=pss,GSSAPI=true', 'yes', env.VM_IP, 'mongo-node', ENABLE_PULL_MODE, PSMDB_VERSION, MODB_VERSION, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
            }
        }
        stage('Disable upgrade on nightly PMM instance') {
            steps {
                sh '''
                    #!/bin/bash
                        curl --location -i --insecure --request PUT \
                        --user "admin:$ADMIN_PASSWORD" \
                        "$PMM_UI_URL/v1/server/settings" \
                        --header "Content-Type: application/json" \
                        --data '{ "enable_updates": false }'
                '''
            }
        }
        stage('Setup Node') {
            steps {
                sh """
                    curl -sL https://deb.nodesource.com/setup_22.x -o nodesource_setup.sh
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
        stage('Check agent status') {
            parallel {
                stage('Check Agent Status on ps single and mongo pss') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_MYSQL, env.PMM_QA_GIT_BRANCH)
                    }
                }
            }
        }
        stage('Run UI Tests') {
            options {
                timeout(time: 150, unit: "MINUTES")
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        sed -i 's+https://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        npx codeceptjs run --reporter mocha-multi -c pr.codecept.js --grep '@gssapi-nightly'
                    """
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
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit 'tests/output/*.xml'
                    slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    junit 'tests/output/*.xml'
                    slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'tests/output/*.png'
                }
            }
            script {
                if (env.SERVER_TYPE == "ovf") {
                    ovfStagingStopJob = build job: 'pmm-ovf-staging-stop', parameters: [
                        string(name: 'VM', value: env.OVF_INSTANCE_NAME),
                    ]
                }
                if (env.SERVER_TYPE == "ami") {
                    amiStagingStopJob = build job: 'pmm3-ami-staging-stop', parameters: [
                        string(name: 'AMI_ID', value: env.AMI_INSTANCE_ID),
                    ]
                }
                if(env.VM_NAME && env.SERVER_TYPE == "docker")
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
                if(env.VM_CLIENT_NAME_PGSQL)
                {
                    destroyStaging(VM_CLIENT_NAME_PS_GR)
                }
            }
            deleteDir()
        }
    }
}
