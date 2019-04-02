pipeline {
    agent {
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: 'git@github.com:percona/pmm-doc',
            description: 'Repository name for build',
            name: 'REPO_NAME')
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
                git poll: false, branch: BRANCH_NAME, url: REPO_NAME
            }
        }
        stage('Publish') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'aws-jenkins', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        sudo yum -y install docker
                        sudo usermod -aG docker ec2-user
                        sudo service docker start
                        cd doc
                        sudo docker build . --tag pmm_2_0_doc_docker_image
                        sudo docker run -i -v `pwd`:/doc -e USER_ID=$UID pmm_2_0_doc_docker_image make clean html
                        if [ "\${PUBLISH_TARGET}" = "www.percona.com" ]; then
                          # production
                          export HOST_IP="10.10.9.210"
                        else
                          # test
                          export HOST_IP="10.10.9.250"
                        fi
                        echo BRANCH=${BRANCH_NAME}
                        rsync --delete-before -avzr -O -e ssh build/html/ jenkins@${HOST_IP}:/www/percona.com/htdocs/doc/percona-monitoring-and-management/2.0/
                    '''
                }
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