library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'dbaas-cli-docs',
            description: 'Tag/Branch for percona-dbaas-cli repository',
            name: 'GIT_BRANCH')
        choice(
            choices: 'test.percona.com\npercona.com',
            description: 'Publish to test or production server',
            name: 'PUBLISH_TARGET')
        }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-Lab/percona-dbaas-cli.git'
            }
        }
        stage('Generate html files') {
            steps {
                sh '''
                    sg docker -c "
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:0.9.0 make clean html
                    "
                '''
            }
        }
        stage('Publish html files') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        DEST_HOST='jenkins-deploy.jenkins-deploy.web.r.int.percona.com'
                        rsync --delete-before -avzr -O -e "ssh -o StrictHostKeyChecking=no -p2222 -i ${KEY_PATH}"  build/html/ ${USER}@\${DEST_HOST}:/data/websites_data/\${PUBLISH_TARGET}/doc/dbaas-cli
                    '''
                }
            }
        }
    }
    post {
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
