library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'source-builder'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Get updated index.html') {
            steps {
                updateRepoIndex()
                stash allowEmpty: true, includes: "new-index.html", name: "NewIndexHtml"
            }
        }
        stage('Sync repo/index.html if needed') {
            steps {
                unstash "NewIndexHtml"
                syncRepoIndex()
            }
        }

    }
}
