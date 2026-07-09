library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label params.AGENT_ARCH == 'arm64' ? 'agent-arm64' : 'agent-amd64'
    }
    parameters {
        choice(
            choices: ['amd64', 'arm64'],
            description: 'CPU architecture of the agent that runs the server container and tests',
            name: 'AGENT_ARCH'
        )
        string(
            defaultValue: 'https://github.com/percona/pmm',
            description: 'Url for pmm repository',
            name: 'GIT_URL'
        )
        string(
            defaultValue: 'main',
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
            description: 'PMM Server docker image (image-name:tag)',
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
                    -p 443:8443 \
                    -v ${PWD}/managed/testdata/checks:/srv/checks \
                    ${DOCKER_VERSION}

                    docker compose -f api-tests/docker-compose.yml up test_db
                    DOCKER_IMAGE=local/pmm-api-tests make -C api-tests docker-build-image
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
                    DOCKER_IMAGE=local/pmm-api-tests \
                    PMM_SERVER_URL=${PMM_URL} \
                    PMM_SERVER_INSECURE_TLS=1 \
                    PMM_RUN_UPDATE_TEST=0 \
                    PMM_RUN_ADVISOR_TESTS=0 \
                    make -C api-tests docker-run-tests
                '''
            }
        }
    }
    post {
        always {
            sh '''
                if docker cp api-tests:/go/pmm/api-tests/pmm-api-tests-junit-report.xml ./api-test-results.xml; then
                  curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                fi
                docker rm -v api-tests || true
            '''
            script {
                if (fileExists("api-test-results.xml")) {
                    junit testResults: "api-test-results.xml", skipPublishingChecks: true
                }
                if (fileExists("logs.zip")) {
                    archiveArtifacts artifacts: 'logs.zip'
                }
            }
        }
        failure {
            script {
                echo "Build failed, owner: @${OWNER}"
            }
        }
        success {
            script {
                echo "Build finished, owner: @${OWNER}"
            }
        }
    }
}
