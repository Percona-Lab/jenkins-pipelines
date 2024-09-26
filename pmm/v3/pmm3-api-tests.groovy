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
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION'
        )
        string(
            defaultValue: 'percona:5.7',
            description: 'Percona Server Docker Container Image',
            name: 'MYSQL_IMAGE'
        )
        string(
            defaultValue: 'postgres:12',
            description: 'Postgresql Docker Container Image',
            name: 'POSTGRES_IMAGE'
        )
        string(
            defaultValue: 'percona/percona-server-mongodb:4.4',
            description: 'Percona Server MongoDb Docker Container Image',
            name: 'MONGO_IMAGE'
        )
    }
    options {
        skipDefaultCheckout()
    }
    triggers {
        upstream upstreamProjects: 'pmm3-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
                // fetch API tests from pmm repository
                git poll: false, branch: GIT_BRANCH, url: GIT_URL

                slackSend botUser: true,
                          channel: '#pmm-ci',
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
                    -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com \
                    -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX \
                    -p 80:8080 \
                    -p 443:8443 \
                    -v ${PWD}/managed/testdata/checks:/srv/checks \
                    ${DOCKER_VERSION}

                    docker build -t local/pmm-api-tests .
                    cd api-tests
                    docker-compose up test_db
                    # MYSQL_IMAGE=${MYSQL_IMAGE} docker-compose up -d mysql
                    # MONGO_IMAGE=${MONGO_IMAGE} docker-compose up -d mongo
                    # POSTGRES_IMAGE=${POSTGRES_IMAGE} docker-compose up -d postgres
                    # docker-compose up -d sysbench
                    cd -
                '''
                script {
                    env.VM_IP = "127.0.0.1"
                    env.PMM_URL = "https://admin:admin@${env.VM_IP}"
                }
            }
        }
        stage('Connectivity Check') {
            steps {
                sh '''
                    if ! timeout 100 bash -c "until curl -skf ${PMM_URL}/ping; do sleep 1; done"; then
                        echo "PMM Server did not pass the connectivity check" >&2
                        exit 1
                    fi
                '''
            }
        }
        stage('Run API Test') {
            steps {
                sh '''
                    docker run -e PMM_SERVER_URL=${PMM_URL} \
                               -e PMM_RUN_UPDATE_TEST=0 \
                               -e PMM_SERVER_INSECURE_TLS=1 \
                               -e PMM_RUN_STT_TESTS=0 \
                               --name ${BUILD_TAG} \
                               --network host \
                               local/pmm-api-tests
                '''
            }
        }
    }
    post {
        always {
            sh '''
                docker cp ${BUILD_TAG}:/go/src/github.com/percona/pmm/api-tests/pmm-api-tests-junit-report.xml ./${BUILD_TAG}.xml || true
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
            script {
                if (fileExists("${BUILD_TAG}.xml")) {
                    junit testResults: "${BUILD_TAG}.xml", skipPublishingChecks: true
                }
                if (fileExists("logs.zip")) {
                    archiveArtifacts artifacts: 'logs.zip'
                }
                if (currentBuild.result != 'SUCCESS') {
                    slackSend botUser: true,
                              channel: '#pmm-ci',
                              color: '#FF0000',
                              message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}, owner: @${OWNER}"
                }
            }
        }
        success {
            script {
                slackSend botUser: true,
                          channel: '#pmm-ci',
                          color: '#00FF00',
                          message: "[${JOB_NAME}]: build finished, URL: ${BUILD_URL}, owner: @${OWNER}"

            }
        }
    }
}
