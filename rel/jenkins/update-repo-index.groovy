library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    //agent {
    //    label 'source-builder'
    //}
    agent any
    parameters {
        string(
            defaultValue: '',
            description: 'Enter comma-separated names for additional repos to create links to (i.e. psmdb-50,ppg-14.0)',
            name: 'REPO_LINKS')
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
                updateRepoIndex(REPO_LINKS.split(','))
                stash allowEmpty: true, includes: "new-index.html", name: "NewIndexHtml"
            }
        }
        stage('Sync repo/index.html if needed') {
            steps {
                unstash "NewIndexHtml"
                syncRepoIndex()
            }
        }
        stage('Cleanup') {
            steps {
                deleteDir()
            }
        }
    }
}
