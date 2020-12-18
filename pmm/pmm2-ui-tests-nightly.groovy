library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PERCONA_TEST_SAAS_HOST=check-dev.percona.com:443 -e PERCONA_TEST_CHECKS_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PERCONA_TEST_CHECKS_INTERVAL=10s -e PERCONA_TEST_DBAAS=1'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:admin@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}"
    }
    else
    {
        env.PMM_URL = "http://admin:admin@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}"
    }
}

void runStagingClient(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'QUERY_SOURCE', value: 'slowlog'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_CLIENT_IP = stagingJob.buildVariables.IP
    env.VM_CLIENT_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:admin@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}"
    }
    else
    {
        env.PMM_URL = "http://admin:admin@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}"
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
                pmm-app/tests/output/allure aws-jenkins@${MONITORING_HOST}:/home/aws-jenkins/allure-reports
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
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for grafana-dashboard repository',
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
        string (
            defaultValue: '',
            description: 'Value for Server Public IP, to use this instance just as client',
            name: 'SERVER_IP')
    }
    options {
        skipDefaultCheckout()
    }
    triggers { cron('0 0 * * *') }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-qa repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/grafana-dashboards.git'

                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                installDocker()
                sh '''
                    sudo yum -y update --security
                    sudo yum -y install jq svn
                    sudo usermod -aG docker ec2-user
                    sudo service docker start
                    sudo curl -L https://github.com/docker/compose/releases/download/1.21.0/docker-compose-`uname -s`-`uname -m` | sudo tee /usr/local/bin/docker-compose > /dev/null
                    sudo chmod +x /usr/local/bin/docker-compose
                    sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose
                    sudo docker-compose --version
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
                runStagingServer(DOCKER_VERSION, CLIENT_VERSION, '--setup-alertmanager', CLIENT_INSTANCE, SERVER_IP)
            }
        }
        stage('Start Client Instance') {
            steps {
                runStagingClient(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1 --addclient=mo,2 --with-replica --addclient=pgsql,1 --addclient=pxc,3 --with-proxysql --pmm2 --setup-alertmanager --add-annotation --setup-replication-ps-pmm2', 'yes', env.VM_IP)
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
                    curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.34.0/install.sh | bash
                    . ~/.nvm/nvm.sh
                    nvm install 12.14.1
                    sudo rm -f /usr/bin/node
                    sudo ln -s ~/.nvm/versions/node/v12.14.1/bin/node /usr/bin/node
                    pushd pmm-app/
                    npm install
                    node -v
                    npm -v
                    sudo yum install -y gettext
                    envsubst < env.list > env.generated.list
                    popd
                """
            }
        }
        stage('Sleep') {
            steps {
                sh """
                curl --data '{"enable_stt": true, "enable_telemetry": true}' -u admin:admin -X POST ${PMM_UI_URL}/v1/Settings/Change
                curl -u admin:admin -X POST ${PMM_UI_URL}/v1/management/SecurityChecks/Start
                """
                sleep 300
                sh """
                curl --data '{"disable_stt": true, "enable_telemetry": true}' -u admin:admin -X POST ${PMM_UI_URL}/v1/Settings/Change
                """
            }
        }
        stage('Run AMI Setup & UI Tests') {
            when {
                expression { env.AMI_TEST == "yes" }
            }
            steps {
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
                            pushd pmm-app/
                            sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                            export PWD=\$(pwd);
                            sudo docker run --env --net=host -v \$PWD:/tests codeception/codeceptjs:2.6.1 codeceptjs run-multiple parallel --debug --steps --reporter mocha-multi -c pr.codecept.js --grep @pmm-ami
                            sudo docker run --env VM_IP=${VM_IP} --env AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} --env AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} --env-file env.generated.list --net=host -v \$PWD:/tests codeception/codeceptjs:2.6.1 codeceptjs run-multiple parallel --debug --steps --reporter mocha-multi -c pr.codecept.js --grep '(?=.*)^(?!.*@visual-test)'
                            popd
                        """
                    }
                }
            }
        }
        stage('Run UI - QAN Tests') {
            options {
                timeout(time: 30, unit: "MINUTES")
            }
            when {
                expression { env.AMI_TEST == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        pushd pmm-app/
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        sudo docker run --env VM_IP=${VM_IP} --env AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} --env AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} --env-file env.generated.list --net=host -v \$PWD:/tests -v \$PWD/node_modules:/node_modules  codeception/codeceptjs:2.6.1 codeceptjs run-multiple parallel --debug --steps --reporter mocha-multi -c pr.codecept.js --grep '@qan|@nightly'
                        popd
                    """
                }
            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip
                sudo chmod 777 -R pmm-app/tests/output
                ./pmm-app/node_modules/.bin/mochawesome-merge pmm-app/tests/output/parallel_chunk*/*.json > pmm-app/tests/output/combine_results.json
                ./pmm-app/node_modules/.bin/marge pmm-app/tests/output/combine_results.json --reportDir pmm-app/tests/output/ --inline --cdn --charts
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
            uploadAllureArtifacts()
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit 'pmm-app/tests/output/parallel_chunk*/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}  & View Tests Run Report - http://${MONITORING_HOST}:9093/latest-report/"
                    archiveArtifacts artifacts: 'pmm-app/tests/output/combine_results.html'
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    junit 'pmm-app/tests/output/parallel_chunk*/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL} & View Tests Run Report - http://${MONITORING_HOST}:9093/latest-report/"
                    archiveArtifacts artifacts: 'pmm-app/tests/output/combine_results.html'
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'pmm-app/tests/output/parallel_chunk*/*.png'
                    archiveArtifacts artifacts: 'pmm-app/tests/output/video/*.mp4'
                }
            }
            sh '''
                sudo rm -r pmm-app/node_modules/
                sudo rm -r pmm-app/tests/output
            '''
            deleteDir()
        }
    }
}
