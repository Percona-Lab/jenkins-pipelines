pipeline {
    agent {
        label 'sphinx-1.4'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for build',
            name: 'BRANCH_NAME')
        choice(
            choices: ['new.percona.com', 'www.percona.com'], 
            description: 'Publish to test or production server', 
            name: 'PUBLISH_TARGET')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '14', numToKeepStr: '5'))
        timestamps()
    }
    triggers {
        pollSCM 'H/15 * * * *'
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: BRANCH_NAME, url: 'https://github.com/percona/pmm.git'
            }
        }
        stage('Publish') {
            steps {
                sh """
                    if [ "${PUBLISH_TARGET}" = "www.percona.com" ]; then
                      # production
                      HOST_IP="10.10.9.210"
                    else
                      # test
                      HOST_IP="10.10.9.250"
                    fi
                    echo BRANCH=${BRANCH_NAME}
                    echo "Building in: " `pwd`

                    cd doc
                    make clean html
                    rsync --delete-before -avzr -O -e ssh build/html/ jenkins@${HOST_IP}:/www/percona.com/htdocs/doc/percona-monitoring-and-management/
                """
            }
        }
    }
    post {
        always {
            // stop staging
            script {
                publishers {
                    warnings(['sphinx'], ['sphinx': '**/*.log']) {}
                }
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished"
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
            deleteDir()
        }
    }
}