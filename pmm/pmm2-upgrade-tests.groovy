library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: "percona/pmm-server:${DOCKER_VERSION}"),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e DISABLE_TELEMETRY=true -e DATA_RETENTION=48h'),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://admin:admin@${VM_IP}"
    env.PMM_UI_URL = "http://${VM_IP}/"
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void checkUpgrade(String PMM_VERSION, String PRE_POST) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                export PMM_VERSION=${PMM_VERSION}
                echo "Checking";
                sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.sh 
                bash -xe /srv/pmm-qa/pmm-tests/check_upgrade.sh ${PMM_VERSION} ${PRE_POST}
            '
        """
    }
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
            defaultValue: 'PMM-T424',
            description: 'Tag/Branch for grafana-dashboards repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['2.3.0', '2.4.0', '2.5.0', '2.6.0', '2.6.1', '2.7.0', '2.8.0', '2.9.0', '2.9.1', '2.10.0', '2.10.1', '2.11.0', '2.11.1', '2.12.0', '2.13.0', '2.14.0'],
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_VERSION')
        choice(
            choices: ['2.3.0', '2.4.0', '2.5.0', '2.6.0', '2.6.1', '2.7.0', '2.8.0', '2.9.0', '2.9.1', '2.10.0', '2.10.1', '2.11.0', '2.11.1', '2.12.0', '2.13.0', '2.14.0'],
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '2.15.0',
            description: 'dev-latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-qa repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/grafana-dashboards.git'

                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
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
                    sudo mkdir -p /srv/pmm-qa || :
                    pushd /srv/pmm-qa
                        sudo git clone --single-branch --branch \${PMM_QA_GIT_BRANCH} https://github.com/percona/pmm-qa.git .
                        sudo git checkout \${PMM_QA_GIT_COMMIT_HASH}
                        sudo chmod 755 pmm-tests/install-google-chrome.sh
                        bash ./pmm-tests/install-google-chrome.sh
                    popd
                    sudo ln -s /usr/bin/google-chrome-stable /usr/bin/chromium
                '''
            }
        }
        stage('Start staging') {
            steps {
                runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1')
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Sleep') {
            steps {
                sleep 60
            }
        }
        stage('Check Packages before Upgrade') {
            steps {
                checkUpgrade(DOCKER_VERSION, "pre");
            }
        }
        stage('Run Upgrade Tests & Remote Instances Tests') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
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
                        pushd pmm-app/
                        sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                        export PWD=\$(pwd);
                        export CHROMIUM_PATH=/usr/bin/chromium
                        ./node_modules/.bin/codeceptjs run-multiple parallel --debug --steps --reporter mocha-multi -c pr.codecept.js --grep '@pmm-upgrade'
                        popd
                    """
                    }
                }
        }
        stage('Check Packages after Upgrade') {
            steps {
                checkUpgrade(PMM_SERVER_LATEST, "post");
            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip
            '''
            destroyStaging(VM_NAME)
            sh '''
                sudo chmod 777 -R pmm-app/tests/output
                ./pmm-app/node_modules/.bin/mochawesome-merge pmm-app/tests/output/parallel_chunk*/*.json > pmm-app/tests/output/combine_results.json
                ./pmm-app/node_modules/.bin/marge pmm-app/tests/output/combine_results.json --reportDir pmm-app/tests/output/ --inline --cdn --charts
            '''
            uploadAllureArtifacts()
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit 'pmm-app/tests/output/parallel_chunk*/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                    archiveArtifacts artifacts: 'pmm-app/tests/output/combine_results.html'
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    junit 'pmm-app/tests/output/parallel_chunk*/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'combine_results.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
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
