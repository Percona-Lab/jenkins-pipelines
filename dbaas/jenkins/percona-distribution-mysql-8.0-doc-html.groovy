library changelog: false, identifier: 'lib@new-doc-jobs', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-dbaas-cli repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/pdmysql-docs.git',
            description: 'pdmysql repository',
            name: 'GIT_REPO')
        choice(
            choices: ['test.percona.com', 'test.percona.com'],
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
                git poll: false , branch: GIT_BRANCH, url: GIT_REPO
            }
        }
        stage('Generate html files') {
            steps {
                sh '''
                    sg docker -c "
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:2.4.4-5 make clean html
                    "
                '''
            }
        }
        stage('Publish html files') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        DEST_HOST='docs-rsync-endpoint.int.percona.com'
                        rsync --delete-before -avzr -O -e "ssh -o StrictHostKeyChecking=no -p2222 -i ${KEY_PATH}"  build/html/ ${USER}@\${DEST_HOST}:/data/websites_data/\${PUBLISH_TARGET}/doc/percona-distribution-mysql/8.0/
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
