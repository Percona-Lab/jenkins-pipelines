void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'PS_VERSION', value: '5.6'),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_DEBUG=1 -e PERCONA_TEST_SAAS_HOST=check-dev.percona.com:443 -e PERCONA_TEST_CHECKS_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX -e PERCONA_TEST_CHECKS_INTERVAL=10s -e PERCONA_TEST_IA=1 -e PERCONA_TEST_DBAAS=1'),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://admin:admin@${VM_IP}"
}

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'micro-amazon'
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
            defaultValue: '',
            description: 'Author of recent Commit to pmm-managed',
            name: 'OWNER')
        string (
            defaultValue: '',
            description: 'Value for Server Public IP, to use this instance just as client',
            name: 'SERVER_IP')
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
        stage('Start staging') {
            steps {
                script {
                    if (!env.SERVER_IP) {
                        runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1')
                    }
                    else
                    {
                        env.VM_IP = env.SERVER_IP
                        env.PMM_URL = "http://admin:admin@${env.VM_IP}"
                    }
                }
            }
        }
        stage('Setup Step') {
            parallel {
                stage('Setup Docker')
                {
                    steps{
                        sh '''
                            sudo yum -y install docker
                            sudo usermod -aG docker ec2-user
                            sudo service docker start
                            sudo docker build -t pmm-api-tests .
                        '''
                    }
                }
                stage('Sanity Check')
                {
                    steps {
                        sh 'timeout 100 bash -c \'while [[ "$(curl -s -o /dev/null -w \'\'%{http_code}\'\' \${PMM_URL}/ping)" != "200" ]]; do sleep 5; done\' || false'
                    }
                }
            }
        }
        stage('Run API Test') {
            steps {
                sh '''
                    sudo docker run -e PMM_SERVER_URL=\${PMM_URL} -e PMM_RUN_UPDATE_TEST=1 --name ${BUILD_TAG} pmm-api-tests
                '''
            }
        }
    }
    post {
        always {
            sh '''
                sudo docker ps -a
                sudo docker cp ${BUILD_TAG}:/go/src/github.com/Percona-Lab/pmm-api-tests/pmm-api-tests-junit-report.xml ./${BUILD_TAG}.xml
                ls -al
                sudo docker stop ${BUILD_TAG}
                sudo docker rm ${BUILD_TAG}
            '''
            junit '${BUILD_TAG}.xml'
            script {
                if (currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}, owner: @${OWNER}"
                }
                // stop staging
                if(env.VM_NAME)
                {
                    destroyStaging(VM_NAME)
                }
            }

            deleteDir()
        }
    }
}
