library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/percona/pmm',
            description: 'Url for pmm repository',
            name: 'GIT_URL')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm repository',
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
        string(
            defaultValue: 'percona:5.7',
            description: 'Percona Server Docker Container Image',
            name: 'MYSQL_IMAGE')
        string(
            defaultValue: 'postgres:12',
            description: 'Postgresql Docker Container Image',
            name: 'POSTGRES_IMAGE')
        string(
            defaultValue: 'percona/percona-server-mongodb:4.2',
            description: 'Percona Server MongoDb Docker Container Image',
            name: 'MONGO_IMAGE')
        string(
            defaultValue: '',
            description: 'Author of recent Commit to pmm',
            name: 'OWNER')
        string (
            defaultValue: 'master',
            description: 'Branch for pmm-agent Repo, used for docker-compose setup',
            name: 'GIT_BRANCH_PMM_AGENT')
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
                // fetch API tests from pmm repository
                git poll: false,
                    branch: GIT_BRANCH,
                    url: GIT_URL

                slackSend botUser: true,
                          channel: '#pmm-ci',
                          color: '#FFFF00',
                          message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
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

        stage('API Tests Setup')
        {
            steps{
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                    """
                }
                sh '''
                    docker run -d \
                    -e ENABLE_ALERTING=1 \
                    -e PMM_DEBUG=1 \
                    -e PERCONA_TEST_CHECKS_INTERVAL=10s \
                    -e ENABLE_BACKUP_MANAGEMENT=1 \
                    -e PERCONA_TEST_DBAAS=0 \
                    -e PERCONA_TEST_SAAS_HOST=check-dev.percona.com \
                    -e PERCONA_TEST_PLATFORM_ADDRESS=https://check-dev.percona.com \
                    -e PERCONA_TEST_CHECKS_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX \
                    -e PERCONA_TEST_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX \
                    -p 80:80 \
                    -p 443:443 \
                    -v \${PWD}/managed/testdata/checks:/srv/checks \
                    \${DOCKER_VERSION}

                    docker build -t pmm-api-tests .
                    cd api-tests
                    docker-compose up test_db
                    MYSQL_IMAGE=\${MYSQL_IMAGE} docker-compose up -d mysql
                    MONGO_IMAGE=\${MONGO_IMAGE} docker-compose up -d mongo
                    POSTGRES_IMAGE=\${POSTGRES_IMAGE} docker-compose up -d postgres
                    docker-compose up -d sysbench
                    cd ../
                '''
                script {
                    env.VM_IP = "127.0.0.1"
                    env.PMM_URL = "http://admin:admin@${env.VM_IP}"
                }
            }
        }
        stage('Sanity Check')
        {
            steps {
                sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
            }
        }
        stage('Run API Test') {
            steps {
                sh '''
                    docker run -e PMM_SERVER_URL=\${PMM_URL} \
                               -e PMM_RUN_UPDATE_TEST=0 \
                               -e PMM_RUN_STT_TESTS=0 \
                               --name ${BUILD_TAG} \
                               --network host \
                               pmm-api-tests
                '''
            }
        }
    }
    post {
        always {
            sh '''
                docker cp ${BUILD_TAG}:/go/src/github.com/percona/pmm/api-tests/pmm-api-tests-junit-report.xml ./${BUILD_TAG}.xml || true
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                sudo chown -R ec2-user:ec2-user api-tests || true
            '''
            junit '${BUILD_TAG}.xml'
            script {
                archiveArtifacts artifacts: 'logs.zip'
                if (currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true,
                              channel: '#pmm-ci',
                              color: '#00FF00',
                              message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                } else {
                    slackSend botUser: true,
                              channel: '#pmm-ci',
                              color: '#FF0000',
                              message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}, owner: @${OWNER}"
                }
            }
        }
    }
}
