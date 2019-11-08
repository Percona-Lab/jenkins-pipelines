void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: "percona/pmm-server:${DOCKER_VERSION}"),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://admin:admin@${VM_IP}"
    env.PMM_UI_URL = "https://${VM_IP}"
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void checkUpgrade(String PMM_VERSION) {
    withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
        sh """
            ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                export PMM_VERSION=${PMM_VERSION}
                echo "Checking";
                sudo chmod 755 /srv/pmm-qa/pmm-tests/check_upgrade.sh
                bash -xe /srv/pmm-qa/pmm-tests/check_upgrade.sh ${PMM_VERSION}
            '
        """
    }
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
        choice(
            choices: ['2.0.0', '2.0.1'],
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_VERSION')
        choice(
            choices: ['2.0.0', '2.0.1'],
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '2.1.0',
            description: 'dev-latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
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
        stage('Start staging') {
            steps {
                runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1 --addclient=mo,2 --with-replica  --addclient=pgsql,1 --addclient=pxc,1 --with-proxysql --pmm2')
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
                checkUpgrade(DOCKER_VERSION);
            }
        }
        stage('Run Upgrade Tests & Remote Instances Tests') {
            steps {
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
                            curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.34.0/install.sh | bash
                            . ~/.nvm/nvm.sh
                            nvm install 10.6.0
                            sudo rm -f /usr/bin/node
                            sudo ln -s ~/.nvm/versions/node/v10.6.0/bin/node /usr/bin/node
                            npm install
                            node -v
                            npm -v
                            echo ${REMOTE_MYSQL_HOST}
                            sed -i 's/{SAUCE_USER_KEY}/${SAUCE_ACCESS_KEY}/g' codecept.json
                            ./node_modules/.bin/codeceptjs run-multiple parallel --steps --debug --reporter mocha-multi -o '{ "helpers": {"WebDriver": {"url": "${PMM_UI_URL}"}}}' --grep @pmm-pre-update
                            ./node_modules/.bin/codeceptjs run-multiple parallel --steps --debug --reporter mocha-multi -o '{ "helpers": {"WebDriver": {"url": "${PMM_UI_URL}"}}}' --grep @pmm-update
                            ./node_modules/.bin/codeceptjs run-multiple parallel --steps --debug --reporter mocha-multi -o '{ "helpers": {"WebDriver": {"url": "${PMM_UI_URL}"}}}' --grep @pmm-post-update
                        """
                    }
                }
            }
        }
        stage('Check Packages after Upgrade') {
            steps {
                checkUpgrade(PMM_SERVER_LATEST);
            }
        }
        stage('Run UI tests after upgrade') {
            steps {
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
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