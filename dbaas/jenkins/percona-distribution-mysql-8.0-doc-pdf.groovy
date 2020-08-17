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
         }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: GIT_BRANCH, url: GIT_REPO
            }
        }
        stage('Generate PDF') {
            steps {
                sh '''
                    sg docker -c "
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:2.4.4-5 make clean latex
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:2.4.4-5 make clean latexpdf
                    "
                '''
                archiveArtifacts 'build/latex/*.pdf'
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
