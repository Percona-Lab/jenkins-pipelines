library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void checkUpgrade(String PMM_VERSION, String PRE_POST) {
    def pmm_version = PMM_VERSION.trim();
    sh """
        export PRE_POST=${PRE_POST}
        sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.py
        echo ${pmm_version}
        echo $PRE_POST
        python3 /srv/pmm-qa/pmm-tests/check_upgrade.py -v ${pmm_version} -p ${PRE_POST}
    """
}

void checkClientAfterUpgrade(String PMM_SERVER_VERSION) {
    sh """
        echo "Upgrading pmm-client";
        sudo yum clean all
        sudo yum makecache
        sudo yum -y install pmm-client
        sleep 30
        sudo chmod 755 /srv/pmm-qa/pmm-tests/check_client_upgrade.sh
        bash -xe /srv/pmm-qa/pmm-tests/check_client_upgrade.sh ${PMM_SERVER_VERSION}
    """
}

void checkClientBeforeUpgrade(String PMM_SERVER_VERSION, String PMM_CLIENT_VERSION) {
    def pmm_server_version = PMM_SERVER_VERSION.trim();
    def pmm_client_version = PMM_CLIENT_VERSION.trim();
    sh """
        sudo chmod 755 /srv/pmm-qa/pmm-tests/check_client_upgrade.sh
        bash -xe /srv/pmm-qa/pmm-tests/check_client_upgrade.sh ${pmm_server_version} ${pmm_client_version}
    """
}

def latestVersion = pmmVersion()
def versionsList = pmmVersion('list')
def getMinorVersion(VERSION) {
    return VERSION.split("\\.")[1].toInteger()
}

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
        GCP_SERVER_IP=credentials('GCP_SERVER_IP')
        GCP_USER=credentials('GCP_USER')
        GCP_USER_PASSWORD=credentials('GCP_USER_PASSWORD')
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
        PMM_QA_AURORA2_MYSQL_HOST=credentials('PMM_QA_AURORA2_MYSQL_HOST')
        PMM_QA_AURORA2_MYSQL_PASSWORD=credentials('PMM_QA_AURORA2_MYSQL_PASSWORD')
        PMM_QA_AWS_ACCESS_KEY_ID=credentials('PMM_QA_AWS_ACCESS_KEY_ID')
        PMM_QA_AWS_ACCESS_KEY=credentials('PMM_QA_AWS_ACCESS_KEY')
        MAILOSAUR_API_KEY=credentials('MAILOSAUR_API_KEY')
        MAILOSAUR_SERVER_ID=credentials('MAILOSAUR_SERVER_ID')
        MAILOSAUR_SMTP_PASSWORD=credentials('MAILOSAUR_SMTP_PASSWORD')
    }
    parameters {
        string(
            defaultValue: 'PMM-7-upgrade-job-pmm3',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server:202410251936',
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_TAG')
        choice(
            choices: ["3.0.0"],
            description: 'PMM Client Version to test for Upgrade',
            name: 'PMM_CLIENT_VERSION')
        string(
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for qa-integration repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ["SSL", "EXTERNAL SERVICES", "MONGO BACKUP", "CUSTOM PASSWORD", "CUSTOM DASHBOARDS"],
            description: 'Subset of tests for the upgrade',
            name: 'UPGRADE_FLAG')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    currentBuild.description = "PMM Server upgrade from ${env.DOCKER_TAG} to ${env.PMM_SERVER_LATEST} running tests ${env.UPGRADE_FLAG}"
                }
                git poll: false,
                    branch: PMM_UI_GIT_BRANCH,
                    url: 'https://github.com/percona/pmm-ui-tests.git'

                slackSend channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                sh '''
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                    popd
                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium
                '''
            }
        }
        stage('Select subset of tests') {
            parallel {
                stage('Select SSL Tests') {
                    when {
                        expression { env.UPGRADE_FLAG == "SSL" }
                    }
                    steps {
                         script {
                            env.PRE_UPGRADE_FLAG = "@pre-ssl-upgrade"
                            env.POST_UPGRADE_FLAG = "@post-ssl-upgrade"
                            env.PMM_CLIENTS = "--database ssl_psmdb --database ssl_mysql --database ssl_pdpgsql"
                         }
                    }
                }
                stage('Select External Services Tests') {
                    when {
                        expression { env.UPGRADE_FLAG == "EXTERNAL SERVICES" }
                    }
                    steps {
                         script {
                            env.PRE_UPGRADE_FLAG = "@pre-external-upgrade"
                            env.POST_UPGRADE_FLAG = "@post-external-upgrade"
                            env.PMM_CLIENTS = "--database external"
                         }
                    }
                }
                stage('Select Mongo Backup Tests') {
                    when {
                        expression { env.UPGRADE_FLAG == "MONGO BACKUP" }
                    }
                    steps {
                         script {
                            env.PRE_UPGRADE_FLAG = "@pre-mongo-backup-upgrade"
                            env.POST_UPGRADE_FLAG = "@post-mongo-backup-upgrade"
                            env.PMM_CLIENTS = "--database psmdb,SETUP_TYPE=pss"
                         }
                    }
                }
                stage('Select Custom Password Tests') {
                    when {
                        expression { env.UPGRADE_FLAG == "CUSTOM PASSWORD" }
                    }
                    steps {
                         script {
                            env.PRE_UPGRADE_FLAG = "@pre-custom-password-upgrade"
                            env.POST_UPGRADE_FLAG = "@post-custom-password-upgrade"
                            env.PMM_CLIENTS = "--database ps --database pgsql --database psmdb"
                         }
                    }
                }
                stage('Select Custom dashboards Tests') {
                    when {
                        expression { env.UPGRADE_FLAG == "CUSTOM DASHBOARDS" }
                    }
                    steps {
                         script {
                            env.PRE_UPGRADE_FLAG = "@pre-dashboards-upgrade"
                            env.POST_UPGRADE_FLAG = "@post-dashboards-upgrade"
                            env.PMM_CLIENTS = "--help"
                         }
                    }
                }
            }
        }
        stage('Start Server Instance') {
            steps {
                sh """
                    sudo mkdir -p /srv/qa-integration || true
                    pushd /srv/qa-integration
                        sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/Percona-Lab/qa-integration.git .
                    popd
                    sudo chown ec2-user -R /srv/qa-integration

                    docker network create pmm-qa
                    docker volume create pmm-volume

                    docker run --detach --restart always \
                        --network="pmm-qa" \
                        -e WATCHTOWER_DEBUG=1 \
                        -e WATCHTOWER_HTTP_API_TOKEN=testUpgradeToken \
                        -e WATCHTOWER_HTTP_API_UPDATE=1 \
                        --volume /var/run/docker.sock:/var/run/docker.sock \
                        --name watchtower \
                        perconalab/watchtower:latest

                    sleep 10

                    docker run --detach --restart always \
                        --network="pmm-qa" \
                        -e PMM_DEBUG=1 \
                        -e PMM_WATCHTOWER_HOST=http://watchtower:8080 \
                        -e PMM_WATCHTOWER_TOKEN=testUpgradeToken \
                        -e PMM_ENABLE_UPDATES=1 \
                        --publish 80:8080 --publish 443:8443 --publish 42200:42200 \
                        --volume pmm-volume \
                        --name pmm-server \
                        ${DOCKER_TAG}

                """
                waitForContainer('pmm-server', 'pmm-managed entered RUNNING state')
                waitForContainer('pmm-server', 'The HTTP API is enabled at :8080.')
                script {
                    env.SERVER_IP = "127.0.0.1"
                    env.PMM_UI_URL = "http://${env.SERVER_IP}/"
                    env.PMM_URL = "http://admin:${env.ADMIN_PASSWORD}@${env.SERVER_IP}"
                }
            }
        }

        stage('Setup Databases  and PMM Client for PMM-Server') {
            parallel {
                stage('Setup Databases for PMM-Server') {
                    steps {
                        sh """
                            set -o errexit
                            set -o xtrace

                            pushd /srv/qa-integration/pmm_qa
                                echo "Setting docker based PMM clients"
                                python3 -m venv virtenv
                                . virtenv/bin/activate
                                pip install --upgrade pip
                                pip install -r requirements.txt

                                python pmm-framework.py --v \
                                --client-version=${PMM_CLIENT_VERSION} \
                                ${PMM_CLIENTS}
                            popd
                        """
                    }
                }
                stage('Setup PMM Client') {
                    steps {
                        sh """
                            wget https://repo.percona.com/yum/percona-release-latest.noarch.rpm
                            sudo rpm -i percona-release-latest.noarch.rpm
                            sudo percona-release enable-only pmm3-client experimental
                            sudo yum install -y pmm-client
                            sudo pmm-agent setup --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml --server-address=127.0.0.1:443 --server-insecure-tls --metrics-mode=auto --server-username=admin --server-password=admin
                        """
                    }
                }
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Install dependencies') {
            steps {
                sh '''
                    npm ci
                    npx playwright install
                    envsubst < env.list > env.generated.list
                    sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                    export PWD=$(pwd)
                    export CHROMIUM_PATH=/usr/bin/chromium
                '''
            }
        }
        stage('Sleep') {
            steps {
                sleep 60
            }
        }
//         stage('Check Packages before Upgrade') {
//             steps {
//                 script {
//                     sh """
//                         export PMM_VERSION=\$(curl --location 'http://localhost/v1/server/version' --header 'Authorization: Basic YWRtaW46YWRtaW4=' | jq -r '.version' | awk -F "-" \'{print \$1}\')
//                         sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.py
//                         python3 /srv/pmm-qa/pmm-tests/check_upgrade.py -v \$PMM_VERSION -p pre
//                     """
//                 }
//             }
//         }
        stage('Run pre upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh """
                    echo ${PRE_UPGRADE_FLAG}
                    ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --grep ${PRE_UPGRADE_FLAG}
                    """
                }
            }
        }
        stage('Run UI upgrade') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                    ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --grep '@pmm-upgrade'
                    '''
                }
            }
        }
        stage('Run post upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh """
                    echo ${PRE_UPGRADE_FLAG}
                    ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --grep ${POST_UPGRADE_FLAG}
                    """
                }
            }
        }
        stage('Check Packages after Upgrade') {
            steps {
                script {
                    checkUpgrade(PMM_SERVER_LATEST, "post")
                }
            }
        }
        stage('Check Client before Upgrade') {
            steps {
                script {
                    checkClientBeforeUpgrade(PMM_SERVER_LATEST, CLIENT_VERSION)
                }
            }
        }
        stage('Check Client Upgrade') {
            steps {
                checkClientAfterUpgrade(PMM_SERVER_LATEST);
                sh '''
                    export PWD=$(pwd)
                    export CHROMIUM_PATH=/usr/bin/chromium
                    sleep 60
                    ./node_modules/.bin/codeceptjs run --reporter mocha-multi -c pr.codecept.js --grep '@post-client-upgrade'
                '''
            }
        }
    }
    post {
        always {
            sh '''
                # fetch all the logs from PMM server
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true

                # get logs from systemd pmm-agent.service
                if [[ ${CLIENT_VERSION} != http* ]]; then
                    journalctl -u pmm-agent.service >  ./pmm-agent.log
                fi

                # get logs from managed and update-perform
                echo --- pmm-managed logs from pmm-server --- >> pmm-managed-full.log
                docker exec pmm-server cat /srv/logs/pmm-managed.log >> pmm-managed-full.log || true
                docker exec pmm-server cat /srv/logs/pmm-update-perform.log >> pmm-update-perform.log || true
                echo --- pmm-update-perform logs from pmm-server --- >> pmm-update-perform.log
                docker cp pmm-server:/srv/logs srv-logs
                tar -zcvf srv-logs.tar.gz srv-logs

                # stop the containers
                docker-compose down || true
                docker rm -f $(sudo docker ps -a -q) || true
                docker volume rm $(sudo docker volume ls -q) || true
                sudo chown -R ec2-user:ec2-user . || true
            '''
            script {
                archiveArtifacts artifacts: 'pmm-managed-full.log'
                archiveArtifacts artifacts: 'pmm-update-perform.log'
                archiveArtifacts artifacts: 'pmm-agent.log'
                archiveArtifacts artifacts: 'logs.zip'
                archiveArtifacts artifacts: 'srv-logs.tar.gz'

                def PATH_TO_REPORT_RESULTS = 'tests/output/parallel_chunk*/*.xml'
                try {
                    junit PATH_TO_REPORT_RESULTS
                } catch (err) {
                    error "No test reports found at path: " + PATH_TO_REPORT_RESULTS
                }

                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
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
        failure {
            archiveArtifacts artifacts: 'tests/output/parallel_chunk*/*.png'
            slackSend channel: '#pmm-ci',
                      color: '#FF0000',
                      message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}, ver: ${DOCKER_TAG}"
        }
    }
}
