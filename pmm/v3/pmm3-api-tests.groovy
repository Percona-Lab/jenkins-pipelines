library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/pmm',
            description: 'Url for pmm repository',
            name: 'GIT_URL'
        )
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm repository',
            name: 'GIT_BRANCH'
        )
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH'
        )
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker image version (image-name:version-tag)',
            name: 'DOCKER_VERSION'
        )
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Prepare') {
            steps {
                // fetch API tests from pmm repository
                git poll: false, changelog: false, branch: GIT_BRANCH, url: GIT_URL

                slackSend botUser: true,
                          channel: '#pmm-notifications',
                          color: '#0000FF',
                          message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                script {
                    // Set envvars OWNER, OWNER_SLACK
                    getPMMBuildParams('pmm-')
                }
            }
        }
        stage('Checkout Commit') {
            when {
                expression { env.GIT_COMMIT_HASH.trim().length() > 0 }
            }
            steps {
                sh 'git checkout ' + env.GIT_COMMIT_HASH
            }
        }
        stage('API Tests Setup') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                    '''
                }
                sh '''
                    docker run -d \
                    -e PMM_DEBUG=1 \
                    -p 80:8080 \
                    -p 443:8443 \
                    -v ${PWD}/managed/testdata/checks:/srv/checks \
                    ${DOCKER_VERSION}

                    docker build -f api-tests/Dockerfile -t local/pmm-api-tests .
                '''
                script {
                    env.PMM_URL = "https://admin:admin@127.0.0.1"
                }
            }
        }
        stage('Connectivity Check') {
            steps {
                sh '''
                    if ! timeout 100 bash -c "until curl -skf ${PMM_URL}/ping; do sleep 1; done"; then
                        echo "PMM Server did not pass the connectivity check" >&2
                        curl -skf ${PMM_URL}/ping
                    fi
                '''
            }
        }
        stage('Run API Test') {
            steps {
                sh '''
                    docker run -e PMM_SERVER_URL=${PMM_URL} \
                               -e PMM_SERVER_INSECURE_TLS=1 \
                               -e PMM_RUN_UPDATE_TEST=0 \
                               -e PMM_RUN_ADVISOR_TESTS=0 \
                               --name pmm3-api-tests \
                               --network host \
                               local/pmm-api-tests
                '''
            }
        }
    }
    post {
        always {
            sh '''
                if docker cp pmm3-api-tests:/go/pmm/api-tests/pmm-api-tests-junit-report.xml ./pmm3-api-tests.xml; then
                  curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                fi
            '''
            script {
                if (fileExists("pmm3-api-tests.xml")) {
                    junit testResults: "pmm3-api-tests.xml", skipPublishingChecks: true
                }
                if (fileExists("logs.zip")) {
                    archiveArtifacts artifacts: 'logs.zip'
                }
            }
        }
        failure {
            script {
                slackSend botUser: true,
                          channel: '#pmm-notifications',
                          color: '#FF0000',
                          message: "[${JOB_NAME}]: build failed, URL: ${BUILD_URL}, owner: @${OWNER}"
            }
        }
        success {
            script {
                slackSend botUser: true,
                          channel: '#pmm-notifications',
                          color: '#00FF00',
                          message: "[${JOB_NAME}]: build finished, URL: ${BUILD_URL}, owner: @${OWNER}"

            }
        }
    }
}
