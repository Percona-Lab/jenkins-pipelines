library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def versionsList = pmmVersion('list').reverse()
def latestVersion = pmmVersion()
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
            defaultValue: 'PMM-7-pmm-migration-v2',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        choice(
            choices: versionsList,
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_VERSION')
        choice(
            choices: versionsList,
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server Tag to be upgraded to via container replacement',
            name: 'PMM_SERVER_TAG')
        string(
            defaultValue: 'admin-password',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')
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
            choices: ['no', 'yes'],
            description: 'Perform Docker-way Upgrade?',
            name: 'PERFORM_DOCKER_WAY_UPGRADE')
        text(
            defaultValue: '--pdpgsql-version 17 --addclient=pdpgsql,1',
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
                // fetch pmm-ui-tests repository
                git poll: false,
                    branch: PMM_UI_GIT_BRANCH,
                    url: 'https://github.com/percona/pmm-ui-tests.git'

                slackSend channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                sh '''
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch ${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo git checkout ${PMM_QA_GIT_COMMIT_HASH}
                    popd
                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium
                '''
            }
        }
        stage('Start Server Instance') {
            steps {
                sh '''
                    docker network create pmm-qa || true
                    PWD=$(pwd) PMM_SERVER_IMAGE=percona/pmm-server:${DOCKER_VERSION} docker-compose up -d
                '''
                waitForContainer('pmm-server', 'pmm-managed entered RUNNING state')
                waitForContainer('pmm-agent_mongo', 'waiting for connections on port 27017')
                waitForContainer('pmm-agent_mysql_5_7', "Server hostname (bind-address):")
                waitForContainer('pmm-agent_postgres', 'PostgreSQL init process complete; ready for start up.')
                sleep 20
                sh """
                    bash -x testdata/db_setup.sh
                """
                script {
                    env.SERVER_IP = "127.0.0.1"
                    env.PMM_UI_URL = "http://${env.SERVER_IP}/"
                    env.PMM_URL = "http://admin:${env.ADMIN_PASSWORD}@${env.SERVER_IP}"
                }
            }
        }
        stage('Change admin password') {
            when {
                expression { getMinorVersion(DOCKER_VERSION) >= 27 }
            }
            steps {
                sh '''
                    docker exec pmm-server change-admin-password ${ADMIN_PASSWORD}
                '''
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.ENABLE_TESTING_REPO == "yes" && env.ENABLE_EXPERIMENTAL_REPO == "no" }
            }
            steps {
                script {
                    sh """
                        set -o errexit
                        set -o xtrace
                        docker exec pmm-server sed -i'' -e 's^/release/^/testing/^' /etc/yum.repos.d/pmm2-server.repo
                        docker exec pmm-server percona-release enable pmm2-client testing
                        docker exec pmm-server yum clean all
                        docker exec pmm-server yum clean metadata
                    """
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.ENABLE_EXPERIMENTAL_REPO == "yes" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                script {
                    sh """
                        set -o errexit
                        set -o xtrace
                        docker exec pmm-server sed -i'' -e 's^/release/^/experimental/^' /etc/yum.repos.d/pmm2-server.repo
                        docker exec pmm-server percona-release enable pmm2-client experimental
                        docker exec pmm-server yum clean all
                        docker exec pmm-server yum clean metadata
                    """
                }
            }
        }
        stage('Enable Release Repo') {
            when {
                expression { env.ENABLE_EXPERIMENTAL_REPO == "no" && env.ENABLE_TESTING_REPO == "no" }
            }
            steps {
                script {
                    sh """
                        set -o errexit
                        set -o xtrace
                        docker exec pmm-server yum clean all
                        docker exec pmm-server yum clean metadata
                    """
                }
            }
        }
        stage('Setup Client for PMM-Server') {
            steps {
                setupPMMClient(SERVER_IP, CLIENT_VERSION.trim(), "pmm2", "no", ENABLE_TESTING_REPO, "no", 'compose_setup', ADMIN_PASSWORD)
                sh """
                    set -o errexit
                    set -o xtrace
                    export PATH=$PATH:/usr/sbin
                    export PMM_CLIENT_VERSION=${CLIENT_VERSION}
                    bash /srv/pmm-qa/pmm-tests/pmm-framework.sh \
                        --download \
                        ${CLIENTS} \
                        --pmm2
                    sleep 20
                """
            }
        }
        stage('Sanity check') {
            steps {
                sh '''
                    echo \${PMM_URL}
                    timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false
                '''
            }
        }
        stage('Migrate pmm2 to pmm3') {
            steps {
                script {
                    sh '''
                        git checkout PMM-7-pmm-migration
                        docker ps -a
                        wget https://raw.githubusercontent.com/percona/pmm/refs/heads/v3/get-pmm.sh
                        chmod +x get-pmm.sh
                        ./get-pmm.sh -n pmm-server -b --network-name pmm-qa

                        echo "Migrate PMM Clients to v3"
                        docker ps -a

                        sudo percona-release enable pmm3-client experimental
                        sudo yum install -y pmm-client
                        pmm-admin list

                        docker exec rs101 percona-release enable pmm3-client experimental
                        docker exec rs101 yum install -y pmm-client
                        docker exec rs101 pmm-admin list

                        docker exec rs102 percona-release enable pmm3-client experimental
                        docker exec rs102 yum install -y pmm-client
                        docker exec rs102 pmm-admin list

                        docker exec rs103 percona-release enable pmm3-client experimental
                        docker exec rs103 yum install -y pmm-client
                        docker exec rs103 pmm-admin list
                    '''
                    env.SERVER_IP = "127.0.0.1"
                    env.PMM_UI_URL = "https://${env.SERVER_IP}/"
                    env.PMM_URL = "https://admin:${env.ADMIN_PASSWORD}@${env.SERVER_IP}"
                }
            }
        }
        stage('Prepare nightly tests on migrated pmm.') {
            steps {
                script {
                    sh """
                        curl -sL https://rpm.nodesource.com/setup_20.x | sudo bash -
                        sudo yum install -y nodejs
                        node --version
                        npm ci
                        npx playwright install
                        envsubst < env.list > env.generated.list
                    """
                }
            }
        }
        stage('Sleep') {
            steps {
                sleep 120
            }
        }
        stage('Run Tests') {
            options {
                timeout(time: 150, unit: "MINUTES")
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh """
                    sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                    export PWD=\$(pwd);
                    npx codeceptjs run --reporter mocha-multi -c pr.codecept.js --grep '@pmm-migration'
                """
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
        }
    }
}
