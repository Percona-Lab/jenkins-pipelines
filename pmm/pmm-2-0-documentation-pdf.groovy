pipeline {
    agent {
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for build',
            name: 'BRANCH_NAME')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '14', numToKeepStr: '5'))
    }
    triggers {
        pollSCM 'H/15 * * * *'
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: BRANCH_NAME, url: 'https://github.com/percona/pmm-doc.git'
            }
        }
        stage('Publish') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        sudo yum -y install docker
                        sudo usermod -aG docker ec2-user
                        sudo service docker start
                        echo BRANCH = \${BRANCH_NAME}
                        echo "Building in: " `pwd`
                        sg docker -c "
                            docker pull perconalab/pmm-doc
                            docker run -i -v `pwd`:/doc -e USER_ID=$UID perconalab/pmm-doc make clean latex
                            docker run -i -v `pwd`:/doc -e USER_ID=$UID perconalab/pmm-doc make latexpdf
                        "
                    '''
                }
                stash includes: 'build/latex/*.pdf', name: 'PDF'
                archiveArtifacts 'build/latex/*.pdf'
            }
        }
    }
    post {
        always {
            // stop staging
            script {
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