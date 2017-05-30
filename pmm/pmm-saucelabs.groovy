void runStaging(String DOCKER_VERSION, CLIENT_VERSION, CLIENTS) {
    stagingJob = build job: 'pmm-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.PMM_URL = "http://${VM_IP}"
}

void destroyStaging(IP) {
    build job: 'pmm-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

pipeline {
    agent {
        label 'virtualbox'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker version',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'latest',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'pmm-docker', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Preparation') {
            steps {
                slackSend channel: '#pmm-jenkins', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${env.BUILD_URL}"

                // clean up workspace and fetch pmm-qa repository
                cleanWs deleteDirs: true, notFailBuild: true
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-QA/pmm-qa.git'

                sh '''
                    export PATH=$PATH:/usr/local/node/bin
                    npm install protractor protractor-jasmine2-screenshot-reporter jasmine-reporters
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
        stage('Run Test') {
            steps {
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
                            export PATH=$PATH:/usr/local/node/bin:\$(pwd -P)/node_modules/protractor/bin
                            protractor config_saucelabs_debug.js --baseUrl=${PMM_URL} || :
                        """
                    }
                }
            }
        }
        stage('Stop staging') {
            steps {
                destroyStaging(VM_IP)
            }
        }
    }
    post {
        success {
            slackSend channel: '#pmm-jenkins', color: '#00FF00', message: "[${JOB_NAME}]: build finished"

            // proccess test result
            saucePublisher()
            junit '**/testresults/*xmloutput.xml'
            step([$class: 'JUnitResultArchiver', testResults: '**/testresults/*xmloutput.xml', healthScaleFactor: 1.0])
        }
        failure {
            slackSend channel: '#pmm-jenkins', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
        }
    }
}
