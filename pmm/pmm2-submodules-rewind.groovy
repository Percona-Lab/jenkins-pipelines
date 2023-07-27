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
        cron('H/10 * * * *')
    }
    stages {
        stage('Checkout') {
            steps {
                // deleteDir()

                // git branch: 'PMM-2.0', credentialsId: 'GitHub SSH Key', poll: false, url: 'git@github.com:Percona-Lab/pmm-submodules'
                
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        # Configure git to push using ssh
                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
                        
                        mkdir pmm-submodules
                        git clone --branch "PMM-2.0" --single-branch git@github.com:Percona-Lab/pmm-submodules.git pmm-submodules
                        cd pmm-submodules
                        git reset --hard
                        git clean -xdff
                        git submodule update --remote --init --recommend-shallow --jobs 10
                        git submodule status
                        git status --untracked-files=all --ignore-submodules=none
                    '''
                }

                script {
                    def changes_count = sh(returnStdout: true, script: 'cd pmm-submodules && git status --short | wc -l').trim()
                    if (changes_count == '0') {
                        echo "WARNING: everything up-to-date, skip rewinding"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Commit') {
            steps {
                sh '''
                    cd pmm-submodules
                    git config user.email "noreply@percona.com"
                    git config user.name "PMM Jenkins"

                    git commit -a -m "chore: rewind submodules for dev-latest"
                    git show
                '''

                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        export GIT_SSH_COMMAND="/usr/bin/ssh -i ${SSHKEY} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
                        cd pmm-submodules
                        git config --global push.default matching
                        git push
                    '''
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
        unstable {
            script {
                echo 'everything up to date' 
            }
        }
        success {
            script {
                slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: dev-latest rewind successful, URL: ${BUILD_URL}"
            }
        }
        failure {
            script {
                slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
            }
        }
    }
}
