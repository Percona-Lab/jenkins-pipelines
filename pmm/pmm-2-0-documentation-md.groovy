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
            description: 'Publish to test or live website',
            name: 'PUBLISH_TARGET')
        booleanParam(
            defaultValue: false,
            description: "Build PDF",
            name: 'BUILD_PDF'
        )
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '14', numToKeepStr: '5'))
    }
    stages {
        stage('Checkout') {
            steps {
                sh '''
                    sudo chmod 777 -R ./
                '''
                git poll: false, branch: BRANCH_NAME, url: 'https://github.com/percona/pmm-doc.git'
            }
        }
        stage('Env Prepare') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'publish-doc-percona.com',
                                                   keyFileVariable: 'KEY_PATH',
                                                   passphraseVariable: '',
                                                   usernameVariable: 'USER')]) {
                    sh '''
                        sudo yum -y install docker
                        sudo usermod -aG docker ec2-user
                        sudo service docker status || sudo service docker start
                    '''
                }
            }
        }
        stage('Doc Build (HTML)') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'publish-doc-percona.com',
                                                   keyFileVariable: 'KEY_PATH',
                                                   passphraseVariable: '',
                                                   usernameVariable: 'USER')]) {
                    sh '''
                        sg docker -c "
                            docker pull perconalab/pmm-doc-md:latest
                            docker run -i -v `pwd`:/docs -e USER_ID=$UID -e UMASK=0777 perconalab/pmm-doc-md
                        "
                    '''
                }
                stash name: 'html-files', includes: 'site/**/*.*'
            }
        }
        stage('Doc Publish') {
            agent {
                label 'vbox-01.ci.percona.com'
            }
            steps {
                unstash 'html-files'
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        echo BRANCH=${BRANCH_NAME}
                        DEST_HOST='docs-rsync-endpoint.int.percona.com'

                        rsync --delete-before -avzr -O -e "ssh -o StrictHostKeyChecking=no -p2222 -i \${KEY_PATH}"  site/ \${USER}@\${DEST_HOST}:/data/websites_data/\${PUBLISH_TARGET}/doc/percona-monitoring-and-management/2.x/
                    '''
                }
            }
        }
        stage('Doc Build (PDF)') {
            when {expression { params.BUILD_PDF == true }}
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'publish-doc-percona.com',
                                                   keyFileVariable: 'KEY_PATH',
                                                   passphraseVariable: '',
                                                   usernameVariable: 'USER')]) {
                    sh '''
                        sg docker -c "
                            docker pull perconalab/pmm-doc-md:latest
                            docker run -i -v `pwd`:/docs -e USER_ID=$UID -e UMASK=0777 perconalab/pmm-doc-md mkdocs build -f mkdocs-pdf.yml
                        "
                    '''
                }
                archiveArtifacts 'site_pdf/_pdf/*.pdf'
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
            sh '''
                sudo chmod 777 -R ./
            '''
            deleteDir()
        }
    }
}
