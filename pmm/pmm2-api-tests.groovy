void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.PMM_URL = "http://${VM_IP}"
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
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: '', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                // clean up workspace and fetch pmm-qa repository
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-Lab/pmm-api-tests'
                
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"

                sh '''
                    sudo yum group install -y "Development Tools"
                    wget https://dl.google.com/go/go1.11.4.linux-amd64.tar.gz
                    tar -xzf go1.11.4.linux-amd64.tar.gz
                    sudo mv go /usr/local
                    export GOROOT=/usr/local/go
                    export GOPATH=/usr/local/go
                    export PATH=$GOPATH/bin:$GOROOT/bin:$PATH
                    go env
                    sudo ln -s \$(pwd -P) $GOPATH/src/pmm-api-tests
                    sudo curl https://raw.githubusercontent.com/golang/dep/master/install.sh | sh
                    cd $GOPATH/src/pmm-api-tests/
                    dep ensure -v
                    make
                '''
            }
        }
        stage('Start staging') {
            steps {
                runStaging(DOCKER_VERSION, CLIENT_VERSION, '--addclient=ps,1')
            }
        }
        stage('Sanity check') {
            steps {
                sh "curl --silent --insecure '${PMM_URL}/prometheus/targets' | grep localhost:9090"
            }
        }
        stage('Sleep') {
            steps {
                sleep 100
            }
        }
        stage('Run API Test') {
            steps {
                sh '''
                    export GOPATH=/usr/local/go
                    cd $GOPATH/src/pmm-api-tests/
                    ./inventory.test -pmm.server-url=\${PMM_URL} -test.v
                '''
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -r /usr/local/go
            '''
            // stop staging
            destroyStaging(VM_NAME)
            script {
                if (currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished"
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
            deleteDir()
        }
    }
}
