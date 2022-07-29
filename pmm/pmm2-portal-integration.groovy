library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def latestVersion = pmmVersion()
def versionsList = pmmVersion('list_with_old')
def getMinorVersion(VERSION) {
    return VERSION.split("\\.")[1].toInteger()
}

pipeline {
    agent {
        label 'docker-farm'
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
            description: 'Tag/Branch for UI Tests Repo integration with Portal',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker tag to test for integration with Portal',
            name: 'DOCKER_TAG')
        choice(
            choices: versionsList,
            description: 'PMM Server Version to test for integration with Portal',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
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
                script {
                    currentBuild.description = "Integration with Portal for PMM ${env.DOCKER_VERSION}"
                }
                // fetch pmm-ui-tests repository
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/pmm-ui-tests.git'

                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Start Server Instance') {
            steps {
                sh """
                    PWD=\$(pwd) PMM_SERVER_IMAGE=\${DOCKER_TAG} docker-compose -f docker-compose.yml up -d pmm-server
                """
                waitForContainer('pmm-server', 'pmm-managed entered RUNNING state')
                script {
                    env.ADMIN_PASSWORD = "admin"
                    env.SERVER_IP = "127.0.0.1"
                    env.PMM_UI_URL = "http://${env.SERVER_IP}/"
                    env.PMM_URL = "http://admin:${env.ADMIN_PASSWORD}@${env.SERVER_IP}"
                }
            }
        }
        stage('Sanity check') {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Sleep') {
            steps {
                sleep 30
            }
        }
        stage('Run Portal integration tests') {
            steps {
                sh """
                    npm ci
                    envsubst < env.list > env.generated.list
                    sed -i 's+http://localhost/+${PMM_UI_URL}/+g' pr.codecept.js
                    export PWD=\$(pwd);
                    export CHROMIUM_PATH=/usr/bin/chromium
                    npx codeceptjs run --steps --reporter mocha-multi -c pr.codecept.js tests/portal/integration_test.js
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
            sh '''
                echo --- pmm-managed logs from pmm-server --- >> pmm-managed-full.log
                docker exec pmm-server cat /srv/logs/pmm-managed.log >> pmm-managed-full.log || true
                docker-compose down
                docker rm -f $(sudo docker ps -a -q) || true
                docker volume rm $(sudo docker volume ls -q) || true
                sudo chown -R ec2-user:ec2-user . || true
            '''
            script {
                archiveArtifacts artifacts: 'pmm-managed-full.log'
                junit 'tests/output/*.xml'
                archiveArtifacts artifacts: 'logs.zip'
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
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
