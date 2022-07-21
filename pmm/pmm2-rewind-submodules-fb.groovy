pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: '',
            description: 'Tag/Branch for pmm-submodules repository like PMM-5260, PMM-2.0 etc',
            name: 'GIT_BRANCH'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }
    stages {
        stage('Checkout') {
            steps {
                git branch: GIT_BRANCH, credentialsId: 'GitHub SSH Key', poll: false, url: 'git@github.com:Percona-Lab/pmm-submodules'
                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        echo "/usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
                        chmod 755 github-ssh.sh
                        export GIT_SSH=$(pwd -P)/github-ssh.sh

                        git reset --hard
                        git clean -xdff
                        git submodule update --remote --init --recommend-shallow --jobs 10
                        git submodule status
                    '''
                }

                script {
                    def changes_count = sh(returnStdout: true, script: '''
                        git status --short | wc -l
                    ''').trim()
                    if (changes_count == '0') {
                        echo "WARNING: everything up-to-date, skip rewind"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Commit') {
            steps {
                sh """
                    git config --global user.email "dev-services@percona.com"
                    git config --global user.name "PMM Jenkins"

                    git commit -a -m "rewind submodules"
                    git show
                """

                withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
                    sh '''
                        echo "/usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
                        chmod 755 github-ssh.sh
                        export GIT_SSH=$(pwd -P)/github-ssh.sh

                        git config --global push.default matching
                        git push
                    '''
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build successful"
                } else if (currentBuild.result == 'UNSTABLE') {
                    echo 'everything up to date'
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
        }
    }
}
