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
        REMOTE_MYSQL_HOST=credentials('mysql-remote-host')
        REMOTE_MYSQL_USER=credentials('mysql-remote-user')
        REMOTE_MYSQL_PASSWORD=credentials('mysql-remote-password')
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
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
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-qa repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'

                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"

                sh '''
                    sudo yum -y update --security
                    sudo yum -y install docker
                    sudo usermod -aG docker ec2-user
                    sudo service docker start
                    sudo docker stop selenoid || true && sudo docker rm selenoid || true
                    sudo curl -s https://aerokube.com/cm/bash | bash \
                    && sudo ./cm selenoid start --vnc --tmpfs 1000 --browsers chrome
                    sleep 20
                    sudo docker ps
                    sudo docker logs selenoid
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
                runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1 --addclient=mo,2 --with-replica  --addclient=pgsql,1 --addclient=pxc,1 --with-proxysql --pmm2 --setup-alertmanager', CLIENT_INSTANCE, SERVER_IP)
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
        stage('Setup Node') {
            steps {
                sh """
                    curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.34.0/install.sh | bash
                    . ~/.nvm/nvm.sh
                    nvm install 10.6.0
                    sudo rm -f /usr/bin/node
                    sudo ln -s ~/.nvm/versions/node/v10.6.0/bin/node /usr/bin/node
                    npm install
                    node -v
                    npm -v
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
                            export INSTANCE_IP=${SERVER_IP}
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
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
                            export INSTANCE_IP=${SERVER_IP}
                            sed -i 's/{SAUCE_USER_KEY}/${SAUCE_ACCESS_KEY}/g' codecept.json
                            ./node_modules/.bin/codeceptjs run-multiple parallel --steps --debug --reporter mocha-multi -o '{ "helpers": {"WebDriver": {"url": "${PMM_UI_URL}"}}}' --grep '(?=.*)^(?!.*@visual-test)'
                        """
                    }
                }
            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip
                sudo docker stop selenoid || true && sudo docker rm selenoid || true
            '''
            destroyStaging(VM_NAME)
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    saucePublisher()
                    junit 'tests/output/parallel_chunk*/chrome_report.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'tests/output/', reportFiles: 'parallel_chunk1_*/result.html, parallel_chunk2_*/result.html, parallel_chunk3_*/result.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                    archiveArtifacts artifacts: 'tests/output/parallel_chunk*/result.html'
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    saucePublisher()
                    junit 'tests/output/parallel_chunk*/chrome_report.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'tests/output/', reportFiles: 'parallel_chunk1_*/result.html, parallel_chunk2_*/result.html, parallel_chunk3_*/result.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'tests/output/parallel_chunk*/result.html'
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'tests/output/parallel_chunk*/*.png'
                }
            }
            sh '''
                sudo rm -r node_modules/
            '''
            deleteDir()
        }
    }
}