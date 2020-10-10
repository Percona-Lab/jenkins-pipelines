pipeline {
    agent {
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for build',
            name: 'BRANCH_NAME')
        choice(
            choices: ['test.percona.com', 'percona.com'],
            description: 'Publish to test or production server', 
            name: 'PUBLISH_TARGET')
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
        stage('Doc Prepare') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'publish-doc-percona.com', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        sudo yum -y install docker
                        sudo usermod -aG docker ec2-user
                        sudo service docker start
                        sg docker -c "
                            docker pull perconalab/pmm-doc
                            docker run -i -v `pwd`:/doc -e USER_ID=$UID perconalab/pmm-doc make clean html
                        "
                    '''
                }
                stash name: "html-files", includes: "build/html/**/*.*"
            }
        }
        stage('Doc Publish'){
            agent {
                label 'vbox-01.ci.percona.com'
            }
            steps{
                unstash "html-files"
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        echo BRANCH=${BRANCH_NAME}
                        DEST_HOST='docs-rsync-endpoint.int.percona.com'
                        
                        rsync --delete-before -avzr -O -e "ssh -o StrictHostKeyChecking=no -p2222 -i \${KEY_PATH}"  build/html/ \${USER}@\${DEST_HOST}:/data/websites_data/\${PUBLISH_TARGET}/doc/percona-monitoring-and-management/2.x/
                    '''
                }
            }
        }
    }
    post {
        always {
            // stop staging
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished"
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}"
                }
            }
            deleteDir()
        }
    }
}
