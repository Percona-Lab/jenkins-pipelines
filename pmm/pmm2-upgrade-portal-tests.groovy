library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runStagingServer(String DOCKER_VERSION, CLIENT_VERSION, CLIENT_INSTANCE, SERVER_IP, ADMIN_PASSWORD = "admin") {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: ""),
        string(name: 'CLIENT_INSTANCE', value: CLIENT_INSTANCE),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PERCONA_TEST_SAAS_HOST=check-dev.percona.com -e PERCONA_TEST_CHECKS_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PERCONA_TEST_CHECKS_INTERVAL=10s'),
        string(name: 'SERVER_IP', value: SERVER_IP),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1'),
        string(name: 'ADMIN_PASSWORD', value: ADMIN_PASSWORD)
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    def clientInstance = "yes";
    if ( CLIENT_INSTANCE == clientInstance ) {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${SERVER_IP}"
        env.PMM_UI_URL = "http://${SERVER_IP}/"
    }
    else
    {
        env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
        env.PMM_UI_URL = "http://${VM_IP}/"
    }
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'docker'
    }
    environment {
        PORTAL_USER_EMAIL=credentials('PORTAL_USER_EMAIL')
        PORTAL_USER_PASSWORD=credentials('PORTAL_USER_PASSWORD')
        OKTA_TOKEN=credentials('OKTA_TOKEN')
        SERVICENOW_LOGIN=credentials('SERVICENOW_LOGIN')
        SERVICENOW_PASSWORD=credentials('SERVICENOW_PASSWORD')
        SERVICENOW_DEV_URL=credentials('SERVICENOW_DEV_URL')
        OAUTH_DEV_CLIENT_ID=credentials('OAUTH_DEV_CLIENT_ID')
        PORTAL_BASE_URL=credentials('PORTAL_BASE_URL')
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
        choice(
            choices: ['2.27.0', '2.26.0', '2.25.0'],
            description: "PMM Server Version",
            name: 'PMM_SERVER_VERSION')
        choice(
            choices: ['2.27.0', '2.26.0', '2.25.0'],
            description: "PMM Client Version",
            name: 'PMM_CLIENT_VERSION')
        choice(
            choices: ['Production', 'Testing', 'Experimental'],
            description: "Select Repository for testing",
            name: 'PMM_REPOSITORY')
        string(
            defaultValue: 'admin-password',
            description: 'Change pmm-server admin user default password.',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-ui-tests repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-ui-tests.git'
                wrap([$class: 'BuildUser']) {
                    sh """
                        echo "\${BUILD_USER_EMAIL}" > OWNER_EMAIL
                        echo "\${BUILD_USER_EMAIL}" | awk -F '@' '{print \$1}' > OWNER_FULL
                    """
                }
                script {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                }
                sh '''
                    sudo yum -y update --security
                    sudo yum -y install php php-mysqlnd php-pdo jq svn bats mysql
                    curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
                    sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
                    sudo amazon-linux-extras install epel -y
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
                runStagingServer("perconalab/pmm-server:$PMM_SERVER_VERSION", "$PMM_CLIENT_VERSION" , "no", '127.0.0.1', ADMIN_PASSWORD)
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Setup Node') {
            steps {
                setupNodejs()
                sh """
                    sudo yum install -y gettext
                    envsubst < env.list > env.generated.list
                """
            }
        }
        stage('Sleep') {
            steps {
                sleep 60
            }
        }
        stage('Enable Testing Repo') {
            when {
                expression { env.PMM_REPOSITORY == "Testing"}
            }
            steps{
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                            docker exec ${VM_NAME}-server sed -i'' -e 's^/release/^/testing/^' /etc/yum.repos.d/pmm2-server.repo
                            docker exec ${VM_NAME}-server percona-release enable percona testing
                            docker exec ${VM_NAME}-server yum clean all
                            docker restart ${VM_NAME}-server
                        '
                    """
                }
            }
        }
        stage('Enable Experimental Repo') {
            when {
                expression { env.PMM_REPOSITORY == "Experimental"}
            }
            steps{
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh """
                        ssh -i "${KEY_PATH}" -o ConnectTimeout=1 -o StrictHostKeyChecking=no ${USER}@${VM_IP} '
                            docker exec ${VM_NAME}-server yum update -y percona-release
                            docker exec ${VM_NAME}-server sed -i'' -e 's^/release/^/experimental/^' /etc/yum.repos.d/pmm2-server.repo
                            docker exec ${VM_NAME}-server percona-release enable percona experimental
                            docker exec ${VM_NAME}-server yum clean all
                            docker restart ${VM_NAME}-server
                        '
                    """
                }
            }
        }
        stage('Run UI pre-upgrade Tests') {
            options {
                timeout(time: 60, unit: "MINUTES")
            }
            steps {
                sh """
                    sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                    export PWD=\$(pwd);
                    export PATH=\$PATH:/usr/sbin
                    if [[ \$PMM_CLIENT_VERSION != dev-latest ]]; then
                        export PATH="`pwd`/pmm2-client/bin:$PATH"
                    fi
                    export CHROMIUM_PATH=/usr/bin/chromium
                    touch portalCredentials
                    ./node_modules/.bin/codeceptjs run --steps --reporter mocha-multi -c pr.codecept.js --grep '@pre-pmm-portal-upgrade'
                """
            }
        }
        stage('Run UI way Upgrade') {
            options {
                timeout(time: 60, unit: "MINUTES")
            }
            steps {
                sh """
                    npm install
                    envsubst < env.list > env.generated.list
                    sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                    export PWD=\$(pwd);
                    export CHROMIUM_PATH=/usr/bin/chromium
                    ./node_modules/.bin/codeceptjs run --debug --steps --reporter mocha-multi -c pr.codecept.js --grep '@pmm-portal-upgrade'  --override '{ "helpers": { "Playwright": { "getPageTimeout": 60000 }}}'
                """
            }
        }
        stage('Run UI post-upgrade Tests') {
            options {
                timeout(time: 60, unit: "MINUTES")
            }
            steps {
                sh """
                    sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                    export PWD=\$(pwd);
                    export PATH=\$PATH:/usr/sbin
                    if [[ \$PMM_CLIENT_VERSION != dev-latest ]]; then
                        export PATH="`pwd`/pmm2-client/bin:$PATH"
                    fi
                    export CHROMIUM_PATH=/usr/bin/chromium
                    ./node_modules/.bin/codeceptjs run --steps --reporter mocha-multi -c pr.codecept.js --grep '@post-pmm-portal-upgrade'
                """
            }
        }
    }
    post {
        always {
            // stop staging
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
            script {
                if(env.VM_IP)
                {
                    destroyStaging(VM_IP)
                }
            }
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    junit 'tests/output/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'tests/output/', reportFiles: 'result.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'tests/output/result.html'
                    archiveArtifacts artifacts: 'logs.zip'
                } else {
                    junit 'tests/output/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'tests/output/', reportFiles: 'result.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'tests/output/result.html'
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'tests/output/*.png'
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