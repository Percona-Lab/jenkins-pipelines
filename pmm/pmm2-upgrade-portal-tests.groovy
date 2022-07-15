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
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PERCONA_TEST_SAAS_HOST=check-dev.percona.com -e PERCONA_TEST_PLATFORM_ADDRESS=https://check-dev.percona.com -e PERCONA_TEST_CHECKS_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PERCONA_TEST_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PERCONA_TEST_CHECKS_INTERVAL=10s'),
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

def versionsList = pmmVersion('list').reverse()

def getPMMServerVersion(String PMM_SERVER_VERSION, String PMM_SERVER_VERSION_CUSTOM) {
    return PMM_CLIENT_VERSION == "custom" ? PMM_SERVER_VERSION_CUSTOM : PMM_SERVER_VERSION
}

def getPMMClientVersion(String PMM_CLIENT_VERSION, String PMM_CLIENT_VERSION_CUSTOM) {
    return PMM_CLIENT_VERSION == "custom" ? PMM_CLIENT_VERSION_CUSTOM : PMM_CLIENT_VERSION
}

pipeline {
    agent {
        label 'agent-amd64'
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
            choices: versionsList + ['custom'],
            description: 'Docker tag for PMM Server Version',
            name: 'PMM_SERVER_VERSION')
        string(
            defaultValue: '',
            description: 'Custom version of PMM Server',
            name: 'PMM_SERVER_VERSION_CUSTOM')
        choice(
            choices: ['perconalab/pmm-server', 'percona/pmm-server'],
            description: "Docker hub for PMM Server",
            name: 'PMM_DOCKER_HUB')
        choice(
            choices: versionsList + ['custom'],
            description: 'PMM Client Version',
            name: 'PMM_CLIENT_VERSION')
        string(
            defaultValue: '',
            description: 'Custom version of PMM Client',
            name: 'PMM_CLIENT_VERSION_CUSTOM')
        choice(
            choices: ['Experimental', 'Testing'],
            description: "Select Testing (RC Tesing) or Experimental (dev-latest testing) Repository",
            name: 'PMM_REPOSITORY')
        string(
            defaultValue: 'admin-password',
            description: 'Change pmm-server admin user default password.',
            name: 'ADMIN_PASSWORD')
        choice(
            choices: ['no', 'yes'],
            description: "Enable Slack notification as direct message, not just pmm-ci channel",
            name: 'NOTIFY')
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
                    which chromium-browser
                    sudo yum -y install mysql
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
        stage('Start Server') {
            steps {
                runStagingServer("$PMM_DOCKER_HUB:" + getPMMServerVersion(PMM_SERVER_VERSION, PMM_SERVER_VERSION_CUSTOM), getPMMClientVersion(PMM_CLIENT_VERSION, PMM_CLIENT_VERSION_CUSTOM), "no", '127.0.0.1', ADMIN_PASSWORD)
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
                timeout(time: 30, unit: "MINUTES")
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
                    npm ci
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
                    npm ci
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
                    archiveArtifacts artifacts: 'logs.zip'
                    if ("${NOTIFY}" == "yes") {
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')
                    slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                    }
                } else {
                    junit 'tests/output/*.xml'
                    publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'tests/output/', reportFiles: 'result.html', reportName: 'HTML Report', reportTitles: ''])
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    archiveArtifacts artifacts: 'logs.zip'
                    archiveArtifacts artifacts: 'tests/output/*.png'
                    if ("${NOTIFY}" == "yes") {
                    def OWNER_EMAIL = sh(returnStdout: true, script: "cat OWNER_EMAIL").trim()
                    def OWNER_SLACK = slackUserIdFromEmail(botUser: true, email: "${OWNER_EMAIL}", tokenCredentialId: 'JenkinsCI-SlackBot-v2')
                    slackSend botUser: true, channel: "@${OWNER_SLACK}", color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                    }
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
