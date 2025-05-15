library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

void checkClientBeforeUpgrade(String PMM_SERVER_VERSION, String CLIENT_VERSION) {
    def PMM_VERSION = CLIENT_VERSION.trim();
    env.PMM_VERSION = PMM_VERSION;
    if (PMM_VERSION == '3-dev-latest') {
        sh '''
            GET_PMM_CLIENT_VERSION=$(wget -q https://raw.githubusercontent.com/Percona-Lab/pmm-submodules/v3/VERSION -O -)
            sudo chmod 755 /srv/pmm-qa/pmm-tests/check_client_upgrade.py
            python3 /srv/pmm-qa/pmm-tests/check_client_upgrade.py ${GET_PMM_CLIENT_VERSION}
        '''
    } else if (PMM_VERSION == 'pmm3-rc') {
        sh '''
            GET_PMM_CLIENT_VERSION=$(wget -q "https://registry.hub.docker.com/v2/repositories/perconalab/pmm-client/tags?page_size=25&name=rc" -O - | jq -r .results[].name  | grep 3.*.*-rc$ | sort -V | tail -n1)
            sudo chmod 755 /srv/pmm-qa/pmm-tests/check_client_upgrade.py
            python3 /srv/pmm-qa/pmm-tests/check_client_upgrade.py ${GET_PMM_CLIENT_VERSION}
        '''
    } else {
        sh '''
            sudo chmod 755 /srv/pmm-qa/pmm-tests/check_client_upgrade.py
            python3 /srv/pmm-qa/pmm-tests/check_client_upgrade.py ${PMM_VERSION}
        '''
    }
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
        ZEPHYR_PMM_API_KEY=credentials('ZEPHYR_PMM_API_KEY')
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: 'percona/pmm-server:3.0.0',
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_TAG')
        string(
            defaultValue: '',
            description: 'PMM Server Version to upgrade to, if empty docker tag will be used from version service.',
            name: 'DOCKER_TAG_UPGRADE')
        string(
            defaultValue: '3.0.0',
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '3.1.0',
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        choice(
            choices: ["experimental", "testing", "release"],
            description: 'PMM client repository',
            name: 'CLIENT_REPOSITORY')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
        choice(
            choices: ["SSL", "EXTERNAL SERVICES", "MONGO BACKUP", "CUSTOM PASSWORD", "CUSTOM DASHBOARDS", "ANNOTATIONS-PROMETHEUS", "ADVISORS-ALERTING", "SETTINGS-METRICS"],
            description: 'Subset of tests for the upgrade',
            name: 'UPGRADE_FLAG')
        string(
            defaultValue: '8.0',
            description: "Percona Server for MySQL version",
            name: 'PS_VERSION')
        string(
            defaultValue: '17',
            description: "Which version of PostgreSQL",
            name: 'PGSQL_VERSION')
        string(
            defaultValue: '17',
            description: "Which version of Percona Distribution for PostgreSQL",
            name: 'PDPGSQL_VERSION')
        string(
            defaultValue: '8.0',
            description: "Which version of Percona Server for MongoDB",
            name: 'PSMDB_VERSION')
        string(
            defaultValue: 'admin',
            description: "Password for PMM Server ",
            name: 'ADMIN_PASSWORD')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    env.ADMIN_PASSWORD = params.ADMIN_PASSWORD
                    currentBuild.description = "${env.UPGRADE_FLAG} - Upgrade for PMM from ${env.DOCKER_TAG.split(":")[1]} to ${env.PMM_SERVER_LATEST}."
                }
                git poll: false,
                    branch: PMM_UI_GIT_BRANCH,
                    url: 'https://github.com/percona/pmm-ui-tests.git'

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
            steps {
                script {
                    if (env.UPGRADE_FLAG == "SSL") {
                        env.PRE_UPGRADE_FLAG = "@pre-ssl-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-ssl-upgrade"
                        env.PMM_CLIENTS = "--database ssl_psmdb --database ssl_mysql --database ssl_pdpgsql"
                    } else if (env.UPGRADE_FLAG == "EXTERNAL SERVICES") {
                        env.PRE_UPGRADE_FLAG = "@pre-external-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-external-upgrade"
                        env.PMM_CLIENTS = "--database external --database ps --database pdpgsql --database psmdb --database pxc"
                    } else if (env.UPGRADE_FLAG == "MONGO BACKUP") {
                        env.PRE_UPGRADE_FLAG = "@pre-mongo-backup-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-mongo-backup-upgrade"
                        env.PMM_CLIENTS = "--database psmdb,SETUP_TYPE=pss"
                    } else if (env.UPGRADE_FLAG == "CUSTOM PASSWORD") {
                        env.PRE_UPGRADE_FLAG = "@pre-custom-password-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-custom-password-upgrade"
                        env.PMM_CLIENTS = "--database ps --database pgsql --database psmdb"
                    } else if (env.UPGRADE_FLAG == "CUSTOM DASHBOARDS") {
                        env.PRE_UPGRADE_FLAG = "@pre-dashboards-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-dashboards-upgrade"
                        env.PMM_CLIENTS = "--help"
                    } else if (env.UPGRADE_FLAG == "ANNOTATIONS-PROMETHEUS") {
                        env.PRE_UPGRADE_FLAG = "@pre-annotations-prometheus-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-annotations-prometheus-upgrade"
                        env.PMM_CLIENTS = "--database ps --database pgsql --database psmdb"
                    } else if (env.UPGRADE_FLAG == "ADVISORS-ALERTING") {
                        env.PRE_UPGRADE_FLAG = "@pre-advisors-alerting-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-advisors-alerting-upgrade"
                        env.PMM_CLIENTS = "--help"
                    } else if (env.UPGRADE_FLAG == "SETTINGS-METRICS") {
                        env.PRE_UPGRADE_FLAG = "@pre-settings-metrics-upgrade"
                        env.POST_UPGRADE_FLAG = "@post-settings-metrics-upgrade"
                        env.PMM_CLIENTS = "--database pgsql --database ps --database psmdb"
                    }
                }
            }
        }
        stage('Start Server Instance') {
            steps {
                sh '''
                    sudo mkdir -p /srv/qa-integration || true
                    pushd /srv/qa-integration
                        sudo git clone --single-branch --branch \${QA_INTEGRATION_GIT_BRANCH} https://github.com/Percona-Lab/qa-integration.git .
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
                    export DOCKER_TAG_UPGRADE=\${DOCKER_TAG_UPGRADE}

                    if [[ -z \$DOCKER_TAG_UPGRADE ]]; then
                        docker run --detach --restart always \
                            --network="pmm-qa" \
                            -e PMM_DEBUG=1 \
                            -e PMM_WATCHTOWER_HOST=http://watchtower:8080 \
                            -e PMM_WATCHTOWER_TOKEN=testUpgradeToken \
                            -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com:443 \
                            -e PERCONA_TEST_PLATFORM_ADDRESS=https://check-dev.percona.com:443 \
                            -e PMM_DEV_PORTAL_URL=https://portal-dev.percona.com \
                            -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTkF7Snv08FCboTne4djQfN5qbrLfAjb8SY3/wwEP+X5nUrkxCEvUDJ \
                            -e PMM_ENABLE_UPDATES=1 \
                            --publish 80:8080 --publish 443:8443 \
                            --volume pmm-volume:/srv \
                            --name pmm-server \
                            ${DOCKER_TAG}
                    else
                        docker run --detach --restart always \
                            --network="pmm-qa" \
                            -e PMM_DEBUG=1 \
                            -e PMM_WATCHTOWER_HOST=http://watchtower:8080 \
                            -e PMM_WATCHTOWER_TOKEN=testUpgradeToken \
                            -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com:443 \
                            -e PERCONA_TEST_PLATFORM_ADDRESS=https://check-dev.percona.com:443 \
                            -e PMM_DEV_PORTAL_URL=https://portal-dev.percona.com \
                            -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTkF7Snv08FCboTne4djQfN5qbrLfAjb8SY3/wwEP+X5nUrkxCEvUDJ \
                            -e PMM_ENABLE_UPDATES=1 \
                            -e PMM_DEV_UPDATE_DOCKER_IMAGE=\${DOCKER_TAG_UPGRADE} \
                            --publish 80:8080 --publish 443:8443 \
                            --volume pmm-volume:/srv \
                            --name pmm-server \
                            \${DOCKER_TAG}
                    fi
                '''
                waitForContainer('pmm-server', 'pmm-managed entered RUNNING state')
                waitForContainer('pmm-server', 'The HTTP API is enabled at :8080.')
                script {
                    env.SERVER_IP = "127.0.0.1"
                    env.PMM_UI_URL = "http://${env.SERVER_IP}/"
                    env.PMM_URL = "http://admin:admin@${env.SERVER_IP}"
                }
            }
        }

        stage('Setup Databases  and PMM Client for PMM-Server') {
            parallel {
                stage('Setup PMM Client') {
                    steps {
                        setupPMM3Client(SERVER_IP, CLIENT_VERSION.trim(), 'pmm', 'no', 'no', 'no', 'upgrade', 'admin', 'no')
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
            }
        }
        stage('Setup Databases for PMM-Server') {
            steps {
                sh '''
                    set -o errexit
                    set -o xtrace

                    pushd /srv/qa-integration/pmm_qa
                    echo "Setting docker based PMM clients"
                    mkdir -m 777 -p /tmp/backup_data
                    python3 -m venv virtenv
                    . virtenv/bin/activate
                    pip install --upgrade pip
                    pip install -r requirements.txt

                    python pmm-framework.py --verbose \
                        --client-version=\${CLIENT_VERSION} \
                        --pmm-server-password=\${ADMIN_PASSWORD} \
                        \${PMM_CLIENTS}
                    popd
                '''
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Setup Custom queries') {
            steps {
                script {
                    if (env.UPGRADE_FLAG == "SETTINGS-METRICS") {
                        sh '''
                            containers=$(docker ps -a)
                            echo $containers
                            psContainerName=$(docker ps -a --format "{{.Names}}" | grep "ps_pmm")
                            echo "$psContainerName"
                            pgsqlContainerName=$(docker ps -a --format "{{.Names}}" | grep "pgsql_pg")
                            echo "$pgsqlContainerName"
                            echo "Creating Custom Queries"
                            git clone https://github.com/Percona-Lab/pmm-custom-queries
                            docker cp pmm-custom-queries/mysql/. $psContainerName:/usr/local/percona/pmm/collectors/custom-queries/mysql/high-resolution/
                            echo "Adding Custom Queries for postgres"
                            docker cp pmm-custom-queries/postgresql/. $pgsqlContainerName:/usr/local/percona/pmm/collectors/custom-queries/postgresql/high-resolution/
                            echo 'node_role{role="my_monitored_server_1"} 1' > node_role.prom
                            sudo cp node_role.prom /usr/local/percona/pmm/collectors/textfile-collector/high-resolution/
                            docker exec $psContainerName pkill -f mysqld_exporter
                            docker exec $pgsqlContainerName pkill -f postgres_exporter
                            docker exec $pgsqlContainerName pmm-admin list
                            docker exec $psContainerName pmm-admin list
                            sudo pkill -f node_exporter
                            sleep 5
                            echo "Setup for Custom Queries Completed along with custom text file collector Metrics"
                            docker ps -a --format "{{.Names}}"

                            docker exec $psContainerName pmm-admin list | grep mysqld_exporter

                            psAgentId=$(docker exec $psContainerName pmm-admin list | grep mysqld_exporter | awk -F' ' '{ print $4 }')
                            psAgentPort=$(docker exec $psContainerName pmm-admin list | grep mysqld_exporter | awk -F' ' '{ print $6 }')
                            echo $psAgentPort
                        '''
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
                script {
                    sh '''
                        export PMM_VERSION=\$(curl --location --user admin:admin 'http://localhost/v1/server/version' | jq -r '.version' | awk -F "-" \'{print \$1}\')
                        sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.py
                        python3 /srv/pmm-qa/pmm-tests/check_upgrade.py -v \$PMM_VERSION -p pre
                    '''
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
        stage('Run pre upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep \${PRE_UPGRADE_FLAG}
                    '''
                }
            }
        }
        stage('Run UI upgrade') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep '@pmm-upgrade'
                    '''
                }
            }
        }
        stage('Run post pmm server upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep ${POST_UPGRADE_FLAG}
                    '''
                }
            }
        }
        stage('Upgrade PMM client') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        containers=\$(docker ps --format "{{ .Names }}")

                        for i in \$containers; do
                            if [[ \$i == *"rs10"* ]]; then
                                docker exec rs101 percona-release enable pmm3-client $CLIENT_REPOSITORY
                                docker exec rs101 dnf install -y pmm-client
                                docker exec rs101 systemctl restart pmm-agent
                            elif [[ \$i == *"mysql_"* ]]; then
                                docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                                docker exec \$i apt install -y pmm-client
                                mysql_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                                docker exec \$i kill \$mysql_process_id
                                docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                            elif [[ \$i == *"pdpgsql"* ]]; then
                                docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                                docker exec \$i apt install -y pmm-client
                                pdpgsql_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                                docker exec \$i kill \$pdpgsql_process_id
                                docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                            elif [[ \$i == *"pgsql"* ]]; then
                                docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                                docker exec \$i apt install -y pmm-client
                                pgsql_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                                docker exec \$i kill \$pgsql_process_id
                                docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                            elif [[ \$i == *"ps_"* ]]; then
                                docker exec \$i percona-release enable pmm3-client $CLIENT_REPOSITORY
                                docker exec \$i apt install -y pmm-client
                                ps_process_id=\$(docker exec \$i ps aux | grep pmm-agent | awk -F " " '{print \$2}')
                                docker exec \$i kill \$ps_process_id
                                docker exec -d \$i pmm-agent --config-file=/usr/local/percona/pmm/config/pmm-agent.yaml
                            fi
                        done
                        sudo percona-release enable pmm3-client $CLIENT_REPOSITORY
                        sudo dnf install -y pmm-client
                    '''
                }
            }
        }
        stage('Run post pmm client upgrade UI tests') {
            steps {
                withCredentials([aws(accessKeyVariable: 'BACKUP_LOCATION_ACCESS_KEY', credentialsId: 'BACKUP_E2E_TESTS', secretKeyVariable: 'BACKUP_LOCATION_SECRET_KEY'), aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh '''
                        ./node_modules/.bin/codeceptjs run-multiple parallel --reporter mocha-multi -c pr.codecept.js --steps --grep \${POST_UPGRADE_FLAG}
                    '''
                }
            }
        }
        stage('Check Packages after Upgrade') {
            steps {
                script {
                    sh '''
                        export PMM_VERSION=\$(curl --location --user admin:admin 'http://localhost/v1/server/version' | jq -r '.version' | awk -F "-" \'{print \$1}\')
                        sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.py
                        python3 /srv/pmm-qa/pmm-tests/check_upgrade.py -v \$PMM_VERSION -p post
                    '''
                }
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
            }
        }
        failure {
            archiveArtifacts artifacts: 'tests/output/parallel_chunk*/*.png'
        }
    }
}
