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
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${VM_IP}"
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
    env.PMM_UI_URL = "https://${OVF_INSTANCE_IP}/"
    env.ADMIN_PASSWORD = "admin"
}

def runOpenshiftClusterCreate(String OPENSHIFT_VERSION, DOCKER_VERSION, ADMIN_PASSWORD) {
    def clusterName = "nightly-test-${env.BUILD_NUMBER}"
    def pmmImageRepo = DOCKER_VERSION.split(":")[0]
    def pmmImageTag = DOCKER_VERSION.split(":")[1]

    clusterCreateJob = build job: 'openshift-cluster-create', parameters: [
        string(name: 'CLUSTER_NAME', value: clusterName),
        string(name: 'OPENSHIFT_VERSION', value: OPENSHIFT_VERSION),
        booleanParam(name: 'DEPLOY_PMM', value: true),
        string(name: 'TEAM_NAME', value: 'pmm'),
        string(name: 'PRODUCT_TAG', value: 'pmm'),
        string(name: 'PMM_ADMIN_PASSWORD', value: ADMIN_PASSWORD),
        string(name: 'PMM_IMAGE_REPOSITORY', value: pmmImageRepo),
        string(name: 'PMM_IMAGE_TAG', value: pmmImageTag),
    ]

    def pmmAddress = clusterCreateJob.buildVariables.PMM_URL
    def pmmHostname = pmmAddress.split("//")[1]

    env.VM_IP = pmmHostname
    env.VM_NAME = clusterCreateJob.buildVariables.VM_NAME
    env.WORK_DIR = clusterCreateJob.buildVariables.WORK_DIR
    env.FINAL_CLUSTER_NAME = clusterCreateJob.buildVariables.FINAL_CLUSTER_NAME
    env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${pmmHostname}"
    env.PMM_UI_URL = "${pmmAddress}/"
}

void runAMIStagingStart(String AMI_ID) {
    amiStagingJob = build job: 'pmm3-ami-staging-start', parameters: [
        string(name: 'AMI_ID', value: AMI_ID)
    ]
    env.AMI_INSTANCE_ID = amiStagingJob.buildVariables.INSTANCE_ID
    env.AMI_INSTANCE_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.ADMIN_PASSWORD = amiStagingJob.buildVariables.INSTANCE_ID
    env.VM_IP = amiStagingJob.buildVariables.PUBLIC_IP
    env.VM_NAME = amiStagingJob.buildVariables.INSTANCE_ID
    env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${AMI_INSTANCE_IP}"
    env.PMM_UI_URL = "https://${AMI_INSTANCE_IP}/"
}

void runStagingClient(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP, NODE_TYPE, ENABLE_PULL_MODE, PXC_VERSION,
PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION , QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD = "admin") {
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
        string(name: 'MODB_VERSION', value: MODB_VERSION),
        string(name: 'QUERY_SOURCE', value: QUERY_SOURCE),
        string(name: 'PMM_QA_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
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
    else if ( NODE_TYPE == 'extra-pxc-node' ) {
        env.VM_CLIENT_IP_EXTRA_PXC = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_EXTRA_PXC = stagingJob.buildVariables.VM_NAME
    }
    else if ( NODE_TYPE == 'postgres-node' ) {
        env.VM_CLIENT_IP_PGSQL = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_PGSQL = stagingJob.buildVariables.VM_NAME
    } else if ( NODE_TYPE == 'external-node' ) {
        env.VM_CLIENT_IP_EXTERNAL = stagingJob.buildVariables.IP
        env.VM_CLIENT_NAME_EXTERNAL = stagingJob.buildVariables.VM_NAME
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
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['docker', 'ovf', 'ami', 'helm'],
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
            name: 'QA_INTEGRATION_GIT_BRANCH')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['latest', '4.19.6', '4.19.5', '4.19.4', '4.19.3', '4.19.2', '4.18.9', '4.18.8', '4.18.7', '4.18.6', '4.18.5', '4.17.9', '4.17.8', '4.17.7', '4.17.6', '4.17.5', '4.16.9', '4.16.8', '4.16.7', '4.16.6', '4.16.5'],
            description: 'OpenShift version to install (specific version or channel)',
            name: 'OPENSHIFT_VERSION')
        choice(
            choices: ['8.0', '8.4', '5.7'],
            description: 'Percona XtraDB Cluster version',
            name: 'PXC_VERSION')
        choice(
            choices: ['8.4', '8.0', '5.7', '5.7.30', '5.6'],
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION')
        choice(
            choices: ['8.4', '8.0', '5.7', '5.6'],
            description: 'MySQL Community Server version',
            name: 'MS_VERSION')
        choice(
            choices: ['17', '16', '15', '14', '13'],
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        choice(
            choices: ['17', '16', '15','14', '13'],
            description: 'Percona Distribution for PostgreSQL',
            name: 'PDPGSQL_VERSION')
        choice(
            choices: ['10.6', '10.5', '10.4', '10.3', '10.2'],
            description: "MariaDB Server version",
            name: 'MD_VERSION')
        choice(
            choices: ['8.0', '7.0', '6.0', '5.0', '4.4'],
            description: "Percona Server for MongoDB version",
            name: 'PSMDB_VERSION')
        choice(
            choices: ['8.0', '7.0', '6.0', '5.0', '4.4'],
            description: "Official MongoDB version",
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
                script {
                    currentBuild.description = "${env.SERVER_TYPE} Server: ${env.DOCKER_VERSION}. Client: ${env.CLIENT_VERSION}"
                }
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
        stage('Start Server') {
            parallel {
                stage('Setup Docker Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "docker" }
                    }
                    steps {
                        runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--help', 'no', '127.0.0.1', QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
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
                stage('Setup Helm Server Instance') {
                    when {
                        expression { env.SERVER_TYPE == "helm" }
                    }
                    steps {
                        runOpenshiftClusterCreate(OPENSHIFT_VERSION, DOCKER_VERSION, ADMIN_PASSWORD)
                    }
                }
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
                stage('external and haproxy client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database external --database haproxy', 'yes', env.VM_IP, 'external-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION, QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('ps-group-replication and mysql client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database ps,SETUP_TYPE=gr --database mysql', 'yes', env.VM_IP, 'ps-gr-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION, QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('ps-replication and pxc client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database ps,SETUP_TYPE=replication --database pxc', 'yes', env.VM_IP, 'pxc-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION, QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('ps single and mongo pss client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database ps,QUERY_SOURCE=slowlog --database psmdb,SETUP_TYPE=pss', 'yes', env.VM_IP, 'mysql-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION, QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('pdpgsql, pgsql and pdpgsql patroni client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database pdpgsql --database pgsql --database pdpgsql,SETUP_TYPE=patroni', 'yes', env.VM_IP, 'postgres-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION, QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('psmdb sharded client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database psmdb,SETUP_TYPE=sharding', 'yes', env.VM_IP, 'sharded-psmdb', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION, QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
                    }
                }
                stage('extra pxc client') {
                    steps {
                        runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--database pxc', 'yes', env.VM_IP, 'extra-pxc-node', ENABLE_PULL_MODE, PXC_VERSION, PS_VERSION, MS_VERSION, PGSQL_VERSION, PDPGSQL_VERSION, MD_VERSION, PSMDB_VERSION, MODB_VERSION, QUERY_SOURCE, QA_INTEGRATION_GIT_BRANCH, ADMIN_PASSWORD)
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
        stage('Check agent status') {
            parallel {
                stage('Check Agent Status on external node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_EXTERNAL, env.PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Check Agent Status on ps single and mongo pss') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_MYSQL, env.PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Check Agent Status on ps & replication node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_PS_GR, env.PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Check Agent Status on ms/md/pxc node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_PXC, env.PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Check Agent Status on postgresql node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_PGSQL, env.PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Check Agent Status on psmdb sharded node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_PSMDB_SHARDED, env.PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Check Agent Status on extra pxc node') {
                    steps {
                        checkClientNodesAgentStatus(env.VM_CLIENT_IP_EXTRA_PXC, env.PMM_QA_GIT_BRANCH)
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
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        npx codeceptjs run --reporter mocha-multi -c pr.codecept.js --grep '@qan|@nightly|@menu'
                    """
                }
            }
        }
    }
    post {
        always {
            // stop staging
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
                if (env.SERVER_TYPE == "helm") {
                    build job: 'openshift-cluster-destroy', parameters: [
                        string(name: 'CLUSTER_NAME', value: env.FINAL_CLUSTER_NAME),
                        string(name: 'DESTROY_REASON', value: 'testing-complete'),
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
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
        }
        success {
            script {
                junit 'tests/output/*.xml'
                slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                archiveArtifacts artifacts: 'logs.zip'
            }
        }
        failure {
            script {
                junit 'tests/output/*.xml'
                slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                archiveArtifacts artifacts: 'logs.zip'
                archiveArtifacts artifacts: 'tests/output/*.png'
            }
        }
    }
}
