library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-api-tests repository',
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
            description: 'Author of recent Commit to pmm-managed',
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
                // clean up workspace and fetch pmm-api-tests repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-Lab/pmm-api-tests'
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
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
                sh '''
                    sudo curl -L https://github.com/docker/compose/releases/download/1.29.0/docker-compose-`uname -s`-`uname -m` | sudo tee /usr/local/bin/docker-compose > /dev/null
                    sudo chmod +x /usr/local/bin/docker-compose
                    sudo ln -sfn /usr/local/bin/docker-compose /usr/bin/docker-compose
                    sudo docker-compose --version
                    sudo docker build -t pmm-api-tests .
                    git clone --single-branch --branch \${GIT_BRANCH_PMM_AGENT} https://github.com/percona/pmm-agent
                    cd pmm-agent
                    sudo PMM_SERVER_IMAGE=\${DOCKER_VERSION} MONGO_IMAGE=\${MONGO_IMAGE} MYSQL_IMAGE=\${MYSQL_IMAGE} POSTGRES_IMAGE=\${POSTGRES_IMAGE} docker-compose up -d
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
                    sudo docker run -e PMM_SERVER_URL=\${PMM_URL} -e PMM_RUN_UPDATE_TEST=1 -e PMM_RUN_STT_TESTS=0 --name ${BUILD_TAG} --network host pmm-api-tests
                '''
            }
        }
    }
    post {
        always {
            sh '''
                sudo docker cp ${BUILD_TAG}:/go/src/github.com/Percona-Lab/pmm-api-tests/pmm-api-tests-junit-report.xml ./${BUILD_TAG}.xml || true
                sudo chmod 777 ./${BUILD_TAG}.xml || true
                sudo chmod 777 -R pmm-agent || true
                sudo docker stop ${BUILD_TAG} || true
                sudo docker rm ${BUILD_TAG} || true
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                cd pmm-agent
                sudo docker-compose down
                sudo docker rm -f $(sudo docker ps -a -q) || true
                sudo docker volume rm $(sudo docker volume ls -q) || true
            '''
            junit '${BUILD_TAG}.xml'
            script {
                archiveArtifacts artifacts: 'logs.zip'
                if (currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}, owner: @${OWNER}"
                }
            }
            deleteDir()
        }
    }
}
