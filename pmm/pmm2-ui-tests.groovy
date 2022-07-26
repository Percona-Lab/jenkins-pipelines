library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void uploadAllureArtifacts() {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            scp -r -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no \
                tests/output/allure aws-jenkins@${MONITORING_HOST}:/home/aws-jenkins/allure-reports
        """
    }
}

def changeUserPasswordUtility(dockerImage) {
    tag = dockerImage.split(":")[1]

    if (tag.startsWith("PR") || tag.startsWith("dev"))
        return "yes"

    minorVersion = tag.split("\\.")[1].toInteger()

    if (minorVersion < 27)
        return "no"
    else
        return "yes"
}

pipeline {
    agent {
        label 'agent-amd64'
    }
    environment {
        AZURE_CLIENT_ID=credentials('AZURE_CLIENT_ID');
        AZURE_CLIENT_SECRET=credentials('AZURE_CLIENT_SECRET');
        AZURE_MYSQL_HOST=credentials('AZURE_MYSQL_HOST');
        AZURE_MYSQL_PASS=credentials('AZURE_MYSQL_PASS');
        AZURE_MYSQL_USER=credentials('AZURE_MYSQL_USER');
        AZURE_POSTGRES_HOST=credentials('AZURE_POSTGRES_HOST');
        AZURE_POSTGRES_PASS=credentials('AZURE_POSTGRES_PASS');
        AZURE_POSTGRES_USER=credentials('AZURE_POSTGRES_USER');
        AZURE_SUBSCRIPTION_ID=credentials('AZURE_SUBSCRIPTION_ID');
        AZURE_TENNANT_ID=credentials('AZURE_TENNANT_ID');
        GCP_SERVER_IP=credentials('GCP_SERVER_IP');
        GCP_USER=credentials('GCP_USER');
        GCP_USER_PASSWORD=credentials('GCP_USER_PASSWORD');
        GCP_MYSQL57_HOST=credentials('GCP_MYSQL57_HOST');
        GCP_MYSQL57_USER=credentials('GCP_MYSQL57_USER');
        GCP_MYSQL57_PASSWORD=credentials('GCP_MYSQL57_PASSWORD');
        GCP_MYSQL80_HOST=credentials('GCP_MYSQL80_HOST');
        GCP_MYSQL80_USER=credentials('GCP_MYSQL80_USER');
        GCP_MYSQL80_PASSWORD=credentials('GCP_MYSQL80_PASSWORD');
        GCP_MYSQL56_HOST=credentials('GCP_MYSQL56_HOST');
        GCP_MYSQL56_USER=credentials('GCP_MYSQL56_USER');
        GCP_MYSQL56_PASSWORD=credentials('GCP_MYSQL56_PASSWORD');
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
        GCP_PGSQL10_HOST=credentials('GCP_PGSQL10_HOST');
        GCP_PGSQL10_USER=credentials('GCP_PGSQL10_USER');
        GCP_PGSQL10_PASSWORD=credentials('GCP_PGSQL10_PASSWORD');
        GCP_PGSQL96_HOST=credentials('GCP_PGSQL96_HOST');
        GCP_PGSQL96_USER=credentials('GCP_PGSQL96_USER');
        GCP_PGSQL96_PASSWORD=credentials('GCP_PGSQL96_PASSWORD');
        REMOTE_AWS_MYSQL_USER=credentials('pmm-dev-mysql-remote-user')
        REMOTE_AWS_MYSQL_PASSWORD=credentials('pmm-dev-remote-password')
        REMOTE_AWS_MYSQL57_HOST=credentials('pmm-dev-mysql57-remote-host')
        REMOTE_AWS_MYSQL80_HOST=credentials('REMOTE_AWS_MYSQL80_HOST')
        REMOTE_AWS_MYSQL80_USER=credentials('REMOTE_AWS_MYSQL80_USER')
        REMOTE_AWS_MYSQL80_PASSWORD=credentials('REMOTE_AWS_MYSQL80_PASSWORD')
        REMOTE_AWS_POSTGRES12_USER=credentials('pmm-qa-postgres-12-user')
        REMOTE_AWS_POSTGRES12_PASSWORD=credentials('pmm-qa-postgres-12-password')
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
        EXTERNAL_EXPORTER_PORT=credentials('external-exporter-port')
        MAILOSAUR_API_KEY=credentials('MAILOSAUR_API_KEY')
        MAILOSAUR_SERVER_ID=credentials('MAILOSAUR_SERVER_ID')
        MAILOSAUR_SMTP_PASSWORD=credentials('MAILOSAUR_SMTP_PASSWORD')
        PORTAL_USER_EMAIL=credentials('PORTAL_USER_EMAIL')
        PORTAL_USER_PASSWORD=credentials('PORTAL_USER_PASSWORD')
        OKTA_TOKEN=credentials('OKTA_TOKEN')
        SERVICENOW_LOGIN=credentials('SERVICENOW_LOGIN')
        SERVICENOW_PASSWORD=credentials('SERVICENOW_PASSWORD')
        SERVICENOW_DEV_URL=credentials('SERVICENOW_DEV_URL')
        OAUTH_DEV_CLIENT_ID=credentials('OAUTH_DEV_CLIENT_ID')
        PORTAL_BASE_URL=credentials('PORTAL_BASE_URL')
        PAGER_DUTY_SERVICE_KEY=credentials('PAGER_DUTY_SERVICE_KEY')
        PAGER_DUTY_API_KEY=credentials('PAGER_DUTY_API_KEY')
        PMM_QA_AURORA2_MYSQL_HOST=credentials('PMM_QA_AURORA2_MYSQL_HOST')
        PMM_QA_AURORA2_MYSQL_PASSWORD=credentials('PMM_QA_AURORA2_MYSQL_PASSWORD')
        PMM_QA_AURORA3_MYSQL_HOST=credentials('PMM_QA_AURORA3_MYSQL_HOST')
        PMM_QA_AURORA3_MYSQL_PASSWORD=credentials('PMM_QA_AURORA3_MYSQL_PASSWORD')
        PMM_QA_AWS_ACCESS_KEY_ID=credentials('PMM_QA_AWS_ACCESS_KEY_ID')
        PMM_QA_AWS_ACCESS_KEY=credentials('PMM_QA_AWS_ACCESS_KEY')
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
            description: "Run Tests for OVF supported Features",
            name: 'OVF_TEST')
        string(
            defaultValue: '',
            description: "Run Tests for Specific Areas or cloud providers example: @gcp|@aws|@instances",
            name: 'TAG')
        choice(
            choices: ['no', 'yes'],
            description: "Run Specified Tagged Tests",
            name: 'RUN_TAGGED_TEST')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Pull Mode, if you are using this instance as Client Node',
            name: 'ENABLE_PULL_MODE')
        string (
            defaultValue: '',
            description: 'Value for Server Public IP, to use this instance just as client',
            name: 'SERVER_IP')
        string(
            defaultValue: 'admin-password',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')  
        string(
            defaultValue: 'percona:5.7',
            description: 'Percona Server Docker Container Image',
            name: 'MYSQL_IMAGE')
        string(
            defaultValue: 'perconalab/percona-distribution-postgresql:14.4',
            description: 'Postgresql Docker Container Image',
            name: 'POSTGRES_IMAGE')
        string(
            defaultValue: 'percona/percona-server-mongodb:4.4',
            description: 'Percona Server MongoDb Docker Container Image',
            name: 'MONGO_IMAGE')
        string(
            defaultValue: 'proxysql/proxysql:2.3.0',
            description: 'ProxySQL Docker Container Image',
            name: 'PROXYSQL_IMAGE')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        text(
            defaultValue: '--addclient=haproxy,1 --addclient=ps,1 --setup-external-service --setup-mysql-ssl --setup-mongodb-ssl --mongo-replica-for-backup',
            description: '''
            Configure PMM Clients
            ms - MySQL (ex. --addclient=ms,1),
            ps - Percona Server for MySQL (ex. --addclient=ps,1),
            pxc - Percona XtraDB Cluster, --with-proxysql (to be used with proxysql only ex. --addclient=pxc,1 --with-proxysql),
            md - MariaDB Server (ex. --addclient=md,1),
            mo - Percona Server for MongoDB(ex. --addclient=mo,1),
            modb - Official MongoDB version from MongoDB Inc (ex. --addclient=modb,1),
            pgsql - Postgre SQL Server (ex. --addclient=pgsql,1)
            pdpgsql - Percona Distribution for PostgreSQL (ex. --addclient=pdpgsql,1)
            An example: --addclient=ps,1 --addclient=mo,1 --addclient=md,1 --addclient=pgsql,2 --addclient=modb,2
            ''',
            name: 'CLIENTS')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    env.CHANGE_USER_PASSWORD_UTILITY = changeUserPasswordUtility(DOCKER_VERSION)
                    if(env.TAG != "") {
                        currentBuild.description = env.TAG
                    }
                }
                // clean up workspace and fetch pmm-ui-tests repository
                deleteDir()
                git poll: false,
                    branch: GIT_BRANCH,
                    url: 'https://github.com/percona/pmm-ui-tests.git'

                sh '''
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                    popd
                    /srv/pmm-qa/pmm-tests/install_k8s_tools.sh --kubectl --sudo
                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium
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
        stage('Setup PMM Server') {
            parallel {
                stage('Setup Server Instance') {
                    when {
                        expression { env.CLIENT_INSTANCE == "no" }
                    }
                    steps {
                        withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                            sh """
                                aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                                PWD=\$(pwd) MYSQL_IMAGE=\${MYSQL_IMAGE} MONGO_IMAGE=\${MONGO_IMAGE} POSTGRES_IMAGE=\${POSTGRES_IMAGE} PROXYSQL_IMAGE=\${PROXYSQL_IMAGE} PMM_SERVER_IMAGE=\${DOCKER_VERSION} docker-compose up -d
                            """
                        }
                        waitForContainer('pmm-server', 'pmm-managed entered RUNNING state')
                        waitForContainer('pmm-agent_mongo', 'waiting for connections on port 27017')
                        waitForContainer('pmm-agent_mysql_5_7', "Server hostname (bind-address):")
                        waitForContainer('pmm-agent_postgres', 'PostgreSQL init process complete; ready for start up.')
                        sh """
                            if [ \$CHANGE_USER_PASSWORD_UTILITY == yes ]; then
                                docker exec pmm-server change-admin-password \${ADMIN_PASSWORD}
                            else
                                docker exec pmm-server grafana-cli --homepath /usr/share/grafana --configOverrides cfg:default.paths.data=/srv/grafana admin reset-admin-password \${ADMIN_PASSWORD}
                            fi
                            bash -x testdata/db_setup.sh
                        """
                        script {
                            env.SERVER_IP = "127.0.0.1"
                            env.PMM_UI_URL = "http://${env.SERVER_IP}/"
                            env.PMM_URL = "http://admin:${env.ADMIN_PASSWORD}@${env.SERVER_IP}"
                        }
                    }
                }
                stage('Setup PMM Server Information') {
                    when {
                        expression { env.CLIENT_INSTANCE == "yes" }
                    }
                    steps {
                        script {
                            env.PMM_URL = "http://admin:${env.ADMIN_PASSWORD}@${SERVER_IP}"
                            env.PMM_UI_URL = "http://${SERVER_IP}/"
                        }
                    }
                }
            }
        }
        stage('Setup Client for PMM-Server') {
            steps {
                setupPMMClient(env.SERVER_IP, CLIENT_VERSION, 'pmm2', ENABLE_PULL_MODE, 'no', 'yes', 'compose_setup', ADMIN_PASSWORD)
                sh """
                    set -o errexit
                    set -o xtrace
                    export PATH=\$PATH:/usr/sbin
                    if [[ \$CLIENT_VERSION != dev-latest ]]; then
                        export PATH="`pwd`/pmm2-client/bin:$PATH"
                    fi
                    bash /srv/pmm-qa/pmm-tests/pmm-framework.sh \
                        --download \
                        ${CLIENTS} \
                        --pmm2 \
                        --pmm2-server-ip=\$SERVER_IP
                    sleep 10
                    pmm-admin list
                """
            }
        }
        stage('Setup') {
            parallel {
                stage('Sanity check') {
                    steps {
                        sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
                    }
                }
                stage('Setup Node') {
                    steps {
                        sh """
                            npm ci
                            envsubst < env.list > env.generated.list
                        """
                    }
                }
            }
        }
        stage('Run UI Tests OVF') {
            options {
                timeout(time: 90, unit: "MINUTES")
            }
            when {
                expression { env.OVF_TEST == "yes" }
            }
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh """
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export PATH=\$PATH:/usr/sbin
                        if [[ \$CLIENT_VERSION != dev-latest ]]; then
                           export PATH="`pwd`/pmm2-client/bin:$PATH"
                        fi
                        export CHROMIUM_PATH=/usr/bin/chromium
                        ./node_modules/.bin/codeceptjs run --debug --steps --reporter mocha-multi -c pr.codecept.js --grep '(?=.*)^(?!.*@not-ui-pipeline)^(?!.*@ami-upgrade)^(?!.*@pmm-upgrade)^(?!.*@not-ovf)^(?!.*@qan)^(?!.*@dbaas)^(?!.*@dashboards)^(?!.*@menu)^(?!.*@pmm-portal-upgrade)'
                    """
                }
            }
        }
        stage('Run UI Tests Docker') {
            options {
                timeout(time: 60, unit: "MINUTES")
            }
            when {
                expression { env.OVF_TEST == "no" && env.RUN_TAGGED_TEST == "no" }
            }
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh """
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export PATH=\$PATH:/usr/sbin
                        if [[ \$CLIENT_VERSION != dev-latest ]]; then
                           export PATH="`pwd`/pmm2-client/bin:$PATH"
                        fi
                        export CHROMIUM_PATH=/usr/bin/chromium
                        ./node_modules/.bin/codeceptjs run-multiple parallel --steps --reporter mocha-multi -c pr.codecept.js --grep '(?=.*)^(?!.*@not-ui-pipeline)^(?!.*@dbaas)^(?!.*@ami-upgrade)^(?!.*@pmm-upgrade)^(?!.*@qan)^(?!.*@nightly)^(?!.*@settings)^(?!.*@menu)^(?!.*@pmm-portal-upgrade)'
                    """
                }
            }
        }
        stage('Run UI Tests Tagged') {
            options {
                timeout(time: 60, unit: "MINUTES")
            }
            when {
                expression { env.OVF_TEST == "no" && env.RUN_TAGGED_TEST == "yes" }
            }
            steps {
                script {
                            env.CODECEPT_TAG = "'" + "${TAG}" + "'"
                }
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh """
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export PATH=\$PATH:/usr/sbin
                        if [[ \$CLIENT_VERSION != dev-latest ]]; then
                           export PATH="`pwd`/pmm2-client/bin:$PATH"
                        fi
                        export CHROMIUM_PATH=/usr/bin/chromium
                        ./node_modules/.bin/codeceptjs run-multiple parallel --steps --reporter mocha-multi -c pr.codecept.js --grep ${CODECEPT_TAG}
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
                echo --- Jobs from pmm-server --- >> job_logs.txt
                docker exec pmm-server psql -Upmm-managed -c 'select id,error,data,created_at,updated_at from jobs ORDER BY updated_at DESC LIMIT 1000;' >> job_logs.txt || true
                echo --- Job logs from pmm-server --- >> job_logs.txt
                docker exec pmm-server psql -Upmm-managed -c 'select * from job_logs ORDER BY job_id LIMIT 1000;' >> job_logs.txt || true
                echo --- pmm-managed logs from pmm-server --- >> pmm-managed-full.log
                docker exec pmm-server cat /srv/logs/pmm-managed.log > pmm-managed-full.log || true
                echo --- pmm-agent logs from pmm-server --- >> pmm-agent-full.log
                docker exec pmm-server cat /srv/logs/pmm-agent.log > pmm-agent-full.log || true
                docker-compose down
                docker rm -f $(sudo docker ps -a -q) || true
                docker volume rm $(sudo docker volume ls -q) || true
                sudo chown -R ec2-user:ec2-user . || true
            '''
            script {
                if(env.VM_NAME)
                {
                    destroyStaging(VM_NAME)
                }
                if(env.VM_CLIENT_NAME)
                {
                    destroyStaging(VM_CLIENT_NAME)
                }
            }
            script {
                env.PATH_TO_REPORT_RESULTS = 'tests/output/parallel_chunk*/*.xml'
                if (env.OVF_TEST == "yes") {
                    env.PATH_TO_REPORT_RESULTS = 'tests/output/*.xml'
                }
                archiveArtifacts artifacts: 'pmm-managed-full.log'
                archiveArtifacts artifacts: 'pmm-agent-full.log'
                archiveArtifacts artifacts: 'logs.zip'
                archiveArtifacts artifacts: 'job_logs.txt'
                junit env.PATH_TO_REPORT_RESULTS
                if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
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
