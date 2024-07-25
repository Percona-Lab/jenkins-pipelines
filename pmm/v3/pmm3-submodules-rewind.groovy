pipeline {
    agent {
        label 'cli'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }
    triggers {
        cron('H/15 * * * *')
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
                        
                        # Clone 'v3' branch and update submodules
                        git clone --single-branch --branch v3 git@github.com:Percona-Lab/pmm-submodules .
                        git submodule update --remote --init --jobs 10
                        git submodule status
                        git status --untracked-files=all --ignore-submodules=none
                    '''
                }

                script {
                    def changes_count = sh(returnStdout: true, script: 'git status --short | wc -l').trim()
                    if (changes_count == '0') {
                        echo "WARNING: everything up-to-date, skip rewinding"
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
                        git config user.email "noreply@percona.com"
                        git config user.name "PMM Jenkins"
                        git config --global push.default matching

                        git commit -a -m "chore: rewind submodules for 3-dev-latest"
                        git show                    
                        git push

                        echo "This will trigger pmm3-client-autobuild and pmm3-server-autobuild pipelines."
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
                slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build successful, URL: ${BUILD_URL}"
            }
        }
        failure {
            script {
                slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
            }
        }
        cleanup {
            deleteDir()
        }        
    }
}
