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
            defaultValue: 'https://github.com/percona/percona-backup-mongodb.git',
            description: 'Git URL',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona-backup-mongodb repository',
            name: 'GIT_BRANCH')
        choice(
            choices: ['test.percona.com', 'percona.com'],
            description: 'Publish to test or production server',
            name: 'PUBLISH_TARGET')
        booleanParam(
            defaultValue: false,
            description: "Build PDF",
            name: 'BUILD_PDF'
        )
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: 'https://github.com/percona/percona-backup-mongodb.git'
            }
        }
        stage('Generate html files') {
            steps {
                sh '''
                    cd doc
                    sg docker -c "
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:0.9.0 make clean html
                    "
                '''
                stash name: 'html-files', includes: 'doc/build/html/'
            }
        }
        stage('Publish html files') {
            agent {
                label 'vbox-01.ci.percona.com'
            }
            steps {
                unstash 'html-files'
                withCredentials([sshUserPrivateKey(credentialsId: 'jenkins-deploy', keyFileVariable: 'KEY_PATH', passphraseVariable: '', usernameVariable: 'USER')]) {
                    sh '''
                        DEST_HOST='docs-rsync-endpoint.int.percona.com'
                        rsync --delete-before -avzr -O -e "ssh -o StrictHostKeyChecking=no -p2222 -i ${KEY_PATH}"  doc/build/html/ ${USER}@\${DEST_HOST}:/data/websites_data/\${PUBLISH_TARGET}/doc/percona-backup-mongodb
                    '''
                }
            }
        }
        stage('Generate PDF') {
            when {expression { params.BUILD_PDF == true }}
            steps {
                sh '''
                    cd doc
                    sg docker -c "
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:0.9.0 make clean latex
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:0.9.0 make clean latexpdf
                    "
                '''
                archiveArtifacts 'doc/build/latex/*.pdf'
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
