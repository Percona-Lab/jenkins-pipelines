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
        stage('Generate PDF') {
            steps {
                sh '''
                    sg docker -c "
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:0.9.0 make clean latex
                        docker run -i -v `pwd`:/doc -e USER_ID=$UID ddidier/sphinx-doc:0.9.0 make clean latexpdf
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
