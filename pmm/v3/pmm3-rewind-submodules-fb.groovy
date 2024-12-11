pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: '',
            description: 'Tag/Branch for pmm-submodules repository like PMM-5260, PMM-3.0 etc',
            name: 'GIT_BRANCH'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

                        git clone --single-branch --branch ${GIT_BRANCH} git@github.com:Percona-Lab/pmm-submodules .
                        git reset --hard
                        git clean -xdff
                        git submodule update --remote --init --recommend-shallow --jobs 10
                        git submodule status
                    '''
                }
                script {
                    def changes_count = sh(returnStdout: true, script: '''git status --short | wc -l''').trim()
                    if (changes_count == '0') {
                        echo "WARNING: everything up-to-date, skip rewind"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Commit') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
                        git config --global user.email "dev-services@percona.com"
                        git config --global user.name "PMM Jenkins"
                        git config --global push.default matching

                        git commit -a -m "chore: rewind submodules for FB"
                        git show
                        git push
                    '''
                }
            }
        }
    }
    post {
        unstable {
            script {
                echo 'INFO: everything up to date'
            }
        }
        success {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: build successful, URL: ${BUILD_URL}"
            }
        }
        failure {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
            }
        }
        cleanup {
            deleteDir()
        }
    }
}
