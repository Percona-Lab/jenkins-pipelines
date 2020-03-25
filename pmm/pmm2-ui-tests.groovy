void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS, CLIENT_INSTANCE, SERVER_IP) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:admin@${SERVER_IP}"
        env.PMM_UI_URL = "https://${SERVER_IP}"
    }
    else
    {
        env.PMM_URL = "http://admin:admin@${VM_IP}"
        env.PMM_UI_URL = "https://${VM_IP}"
    }
}


void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'large-amazon'
    }
    environment {
        MYSQL_HOST=credentials('mysql-remote-host')
        MYSQL_USER=credentials('mysql-remote-user')
        MYSQL_PASSWORD=credentials('mysql-remote-password')
        AWS_MYSQL_USER=credentials('pmm-dev-mysql-remote-user')
        AWS_MYSQL_PASSWORD=credentials('pmm-dev-remote-password')
        AWS_MYSQL57_HOST=credentials('pmm-dev-mysql57-remote-host')
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
    triggers {
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-qa repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/grafana-dashboards.git'

                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"

                sh '''
                    sudo yum -y update --security
                    sudo yum -y install docker jq svn
                    sudo usermod -aG docker ec2-user
                    sudo service docker start
                    sudo docker stop selenoid || true && sudo docker rm selenoid || true
                    sudo docker pull selenoid/vnc_chrome:80.0
                    sudo docker pull selenoid/video-recorder:latest-release
                    sudo docker pull aerokube/selenoid-ui
                    sudo curl -L https://github.com/docker/compose/releases/download/1.21.0/docker-compose-`uname -s`-`uname -m` | sudo tee /usr/local/bin/docker-compose > /dev/null
                    sudo chmod +x /usr/local/bin/docker-compose
                    sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose
                    sudo docker-compose --version
                    echo PWD=${PWD} > .env
                    rm docker-compose.yml
                    sudo svn export https://github.com/percona/pmm-qa.git/trunk/docker-compose.yml
                    sudo svn export https://github.com/percona/pmm-qa.git/trunk/browsers.json
                    sudo svn export https://github.com/percona/pmm-qa.git/trunk/prepare_artifacts_pmm_app.sh
                    sudo chmod 755 docker-compose.yml
                    sudo chmod 755 browsers.json
                    sudo chmod 755 prepare_artifacts_pmm_app.sh
                    sudo docker-compose up -d
                    sleep 20
                    sudo docker ps
                    sudo docker-compose logs
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
        stage('Start staging') {
            steps {
                runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1 --addclient=ms,1 --addclient=md,1 --addclient=mo,2 --with-replica  --addclient=pgsql,1 --addclient=pxc,1 --with-proxysql --pmm2 --setup-alertmanager --disable-tablestats', CLIENT_INSTANCE, SERVER_IP)
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Sleep') {
            steps {
                sleep 300
            }
        }
        stage('Setup Node') {
            steps {
                sh """
                    curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.34.0/install.sh | bash
                    . ~/.nvm/nvm.sh
                    nvm install 10.6.0
                    sudo rm -f /usr/bin/node
                    sudo ln -s ~/.nvm/versions/node/v10.6.0/bin/node /usr/bin/node
                    pushd pmm-app
                    npm install
                    node -v
                    npm -v
                    popd
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
                            sed -i 's/{SAUCE_USER_KEY}/${SAUCE_ACCESS_KEY}/g' codecept.json
                            ./node_modules/.bin/codeceptjs run-multiple parallel --steps --debug --reporter mocha-multi -o '{ "helpers": {"WebDriver": {"url": "${PMM_UI_URL}"}}}' --grep @pmm-ami
                            ./node_modules/.bin/codeceptjs run-multiple parallel --steps --debug --reporter mocha-multi -o '{ "helpers": {"WebDriver": {"url": "${PMM_UI_URL}"}}}' --grep '(?=.*)^(?!.*@visual-test)'
                        """
                    }
                }
            }
        }
        stage('Run UI Tests') {
            when {
                expression { env.AMI_TEST == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'PMM_AWS_DEV', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        pushd pmm-app/
                        ./node_modules/.bin/codeceptjs run-multiple parallel --steps --debug --reporter mocha-multi -o '{ "helpers": {"WebDriver": {"url": "${PMM_UI_URL}"}}}' -c local.codecept.json --grep '(?=.*)^(?!.*@visual-test)'
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
                sudo bash -x ./prepare_artifacts_pmm_app.sh
                sudo docker-compose down || true
                sudo docker stop selenoid || true && sudo docker rm selenoid || true
            '''
            destroyStaging(VM_NAME)
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    saucePublisher()
                    junit 'pmm-app/tests/output/parallel_chunk*/chrome_report.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'parallel_chunk1_*/result.html, parallel_chunk2_*/result.html, parallel_chunk3_*/result.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                    archiveArtifacts artifacts: 'pmm-app/tests/output/parallel_chunk*/result.html'
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    saucePublisher()
                    junit 'pmm-app/tests/output/parallel_chunk*/chrome_report.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'pmm-app/tests/output/', reportFiles: 'parallel_chunk1_*/result.html, parallel_chunk2_*/result.html, parallel_chunk3_*/result.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'pmm-app/tests/output/parallel_chunk*/result.html'
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'pmm-app/tests/output/parallel_chunk*/*.png'
                    archiveArtifacts artifacts: 'pmm-app/tests/output/video/*.mp4'
                }
            }
            sh '''
                sudo rm -r pmm-app/node_modules/
                sudo rm -r video/
                sudo rm -r pmm-app/tests/output
            '''
            deleteDir()
        }
    }
}