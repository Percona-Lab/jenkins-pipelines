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
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
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
        stage('Run Grafana Test') {
            steps {
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
                            export PATH=$PATH:/usr/local/node/bin:\$(pwd -P)/node_modules/protractor/bin
                            protractor config_grafana_saucelabs.js --baseUrl=${PMM_URL} || :
                        """
                    }
                }
            }
        }
        stage('Run QAN Test') {
            steps {
                sauce('SauceLabsKey') {
                    sauceconnect(options: '', sauceConnectPath: '') {
                        sh """
                            export PATH=$PATH:/usr/local/node/bin:\$(pwd -P)/node_modules/protractor/bin
                            protractor config_qan_saucelabs.js --baseUrl=${PMM_URL} || :
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
            junit '**/testresults/*xmloutput*.xml'
            step([$class: 'JUnitResultArchiver', testResults: '**/testresults/*xmloutput*.xml', healthScaleFactor: 1.0])
            publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportDir: 'screenshots', reportFiles: 'pmm-qan-report.html, pmm-test-grafana-report.html', reportName: 'HTML Report', reportTitles: ''])
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed"
        }
    }
}
