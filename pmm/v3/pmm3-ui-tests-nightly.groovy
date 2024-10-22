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
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PMM_DATA_RETENTION=48h -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com:443 -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX'),
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
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}/"
    }
}

void runOVFStagingStart(String SERVER_VERSION, PMM_QA_GIT_BRANCH) {
    ovfStagingJob = build job: 'pmm3-ovf-staging-start', parameters: [
        string(name: 'OVA_VERSION', value: SERVER_VERSION),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
    ]
    env.OVF_INSTANCE_NAME = ovfStagingJob.buildVariables.VM_NAME
    env.OVF_INSTANCE_IP = ovfStagingJob.buildVariables.IP
    env.VM_IP = ovfStagingJob.buildVariables.IP
    env.VM_NAME = ovfStagingJob.buildVariables.VM_NAME
    env.PMM_URL = "https://admin:admin@${OVF_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${OVF_INSTANCE_IP}"
    env.ADMIN_PASSWORD = "admin"
}

void runAMIStagingStart(String AMI_ID) {
    amiStagingJob = build job: 'pmm3-ami-staging-start', parameters: [
        string(name: 'AMI_ID', value: AMI_ID)
    ]
    env.AMI_INSTANCE_ID = amiStagingJob.buildVariables.INSTANCE_ID
    env.AMI_INSTANCE_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.VM_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.VM_NAME = amiStagingJob.buildVariables.INSTANCE_ID
    env.PMM_URL = "https://admin:admin@${AMI_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${AMI_INSTANCE_IP}"
    env.ADMIN_PASSWORD = "admin"
}

void runStagingClient(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, NODE_TYPE, ENABLE_PULL_MODE, PXC_VERSION,
PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
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
        string(name: 'PSMDB_VERSION', value: PSMDB_VERSION),
        string(name: 'QUERY_SOURCE', value: QUERY_SOURCE),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    if ( NODE_TYPE == 'mysql-node' ) {
        env.VM_CLIENT_IP_MYSQL = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_MYSQL = stagingJob.buildVariables.VM_NAME
    }
    else if ( NODE_TYPE == 'ps-gr-node' ) {
        env.VM_CLIENT_IP_PS_GR = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_PS_GR = stagingJob.buildVariables.VM_NAME
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

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}
pipeline {
    agent {
        label 'min-focal-x64'
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
    }
    parameters {
        string(
            defaultValue: 'v3',
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
            defaultValue: '3-dev-latest',
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
            defaultValue: 'v3',
            description: 'Tag/Branch for qa-integration repository',
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
            choices: ['15','14', '13', '12', '11'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        choice(
            choices: ['16','15', '14', '13', '12'],
            description: 'Percona Distribution for PostgreSQL',
            name: 'PDPGSQL_VERSION')
        choice(
            choices: ['10.6', '10.5', '10.4', '10.3', '10.2'],
            description: "MariaDB Server version",
            name: 'MD_VERSION')
        choice(
            choices: ['7.0.7-4', '6.0.14-11', '5.0.26-22', '4.4.29-28'],
            description: "Percona Server for MongoDB version",
            name: 'PSMDB_VERSION')
        choice(
            choices: ['perfschema', 'slowlog'],
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

                slackSend botUser: true, channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                sh '''
                    sudo mkdir -p /srv/qa-integration || :
                    sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/Percona-Lab/qa-integration.git /srv/qa-integration
                    sudo chmod -R 755 /srv/qa-integration
                '''
            }
        }
        stage('Start Server') {
            parallel {
                stage('Setup Docker Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "docker" }
                    }
                    steps {
                        runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--database external --database haproxy', 'no', '127.0.0.1', PMM_QA_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('Setup OVF Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "ovf" }
                    }
                    steps {
                        runOVFStagingStart(DOCKER_VERSION, PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Setup AMI Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "ami" }
                    }
                    steps {
                        runAMIStagingStart(DOCKER_VERSION)
                    }
                }
            }
        }
        stage('Setup PMM Clients') {
            parallel {
                stage('ps-group-replication client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database ps,SETUP_TYPE=gr', 'yes', env.VM_IP, 'ps-gr-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
                stage('ps-replication and pxc') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database ps,SETUP_TYPE=replica --database pxc', 'yes', env.VM_IP, 'pxc-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
                stage('ps single and mongo pss') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database ps --database psmdb,SETUP_TYPE=pss', 'yes', env.VM_IP, 'mysql-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
                stage('pdpgsql, pgsql and mysql') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database pdpgsql --database pgsql --database mysql', 'yes', env.VM_IP, 'postgres-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, QUERY_SOURCE, ADMIN_PASSWORD)
                    }
                }
            }
        }
        stage('Sanity check') {
            steps {
                sh '''
                    echo "${PMM_URL}/ping"
                    timeout 100 bash -c \'while [[ "$(curl -s --insecure -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false
                '''
            }
        }
        stage('Setup Node') {
            steps {
                sh """
                    curl -sL https://deb.nodesource.com/setup_18.x -o nodesource_setup.sh
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
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh """
                                sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                                export PWD=\$(pwd);
                                npx codeceptjs run --reporter mocha-multi -c pr.codecept.js --grep '@qan|@nightly|@menu' --override '{ "helpers": { "Playwright": { "browser": "firefox" }}}'
                            """
                        }
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
            script {
                if (env.SERVER_TYPE == "ovf") {
                    ovfStagingStopJob = build job: 'pmm-ovf-staging-stop', parameters: [
                        string(name: 'VM', value: env.OVF_INSTANCE_NAME),
                    ]
                }
                if (env.SERVER_TYPE == "ami") {
                    amiStagingStopJob = build job: 'pmm2-ami-staging-stop', parameters: [
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
