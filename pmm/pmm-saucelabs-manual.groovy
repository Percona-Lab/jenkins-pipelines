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
            defaultValue: 'http://user:pass@IP',
            description: 'PMM Server Web Interface URL',
            name: 'PMM_URL')
    }

    stages {
        stage('Preparation') {
            steps {
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${env.BUILD_URL}"

                // clean up workspace and fetch pmm-qa repository
                cleanWs deleteDirs: true, notFailBuild: true
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-QA/pmm-qa.git'

                sh '''
                    export PATH=$PATH:/usr/local/node/bin
                    npm install protractor protractor-jasmine2-screenshot-reporter jasmine-reporters
                '''
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
    }
    post {
        success {
            slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished"

            // proccess test result
            saucePublisher()
            junit '**/testresults/*xmloutput.xml'
            step([$class: 'JUnitResultArchiver', testResults: '**/testresults/*xmloutput.xml', healthScaleFactor: 1.0])
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
        }
    }
}
