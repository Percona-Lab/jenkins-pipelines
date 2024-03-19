library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'source-builder'
    }
    parameters {
        string(
            defaultValue: '',
            description: 'Enter name for the new repo(i.e. psmdb-44)',
            name: 'REPO_NAME')
        string(
            defaultValue: 'release,testing,experimental,laboratory',
            description: 'Repo components to create(coma separated)',
            name: 'COMPONENTS')
        string(
            defaultValue: '7,8,9',
            description: 'Centos versions(coma separated)',
            name: 'CENTOS_VERSIONS')
        string(
            defaultValue: 'buster,bullseye,bookworm,bionic,focal,jammy',
            description: 'Debian and Ubuntu release codenames(coma separated)',
            name: 'DEB_CODE_NAMES')
        string(
            defaultValue: '5',
            description: 'Limit',
            name: 'LIMIT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create YUM repo') {
            steps {
                createRepo(REPO_NAME, 'yum', COMPONENTS, CENTOS_VERSIONS, DEB_CODE_NAMES, LIMIT)
            }
        }
        stage('Create APT repo') {
            steps {
                createRepo(REPO_NAME, 'apt', COMPONENTS, CENTOS_VERSIONS, DEB_CODE_NAMES, LIMIT)
            }
        }
        stage('Sync repo to production') {
            steps {
                syncRepo(REPO_NAME)
            }
        }

    }
}
