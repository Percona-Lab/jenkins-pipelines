library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/percona-lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'jenkins'
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
            defaultValue: 'bullseye,bookworm,focal,jammy,noble',
            description: 'Debian and Ubuntu release codenames(coma separated)',
            name: 'DEB_CODE_NAMES')
        string(
            defaultValue: '5',
            description: 'Limit',
            name: 'LIMIT')
        choice(
            choices: 'NO\nYES',
            description: 'PRO build repo',
            name: 'PROBUILD')
        choice(
            choices: 'YES\nNO',
            description: 'Create YUM repo',
            name: 'CREATEYUM')
        choice(
            choices: 'YES\nNO',
            description: 'Create APT repo',
            name: 'CREATEAPT')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create YUM repo') {
            when {
                expression { env.CREATEYUM == 'YES' }
            }
            steps {
                createRepo(REPO_NAME, 'yum', COMPONENTS, CENTOS_VERSIONS, DEB_CODE_NAMES, LIMIT, PROBUILD)
            }
        }
        stage('Create APT repo') {
            when {
                expression { env.CREATEAPT == 'YES' }
            }
            steps {
                createRepo(REPO_NAME, 'apt', COMPONENTS, CENTOS_VERSIONS, DEB_CODE_NAMES, LIMIT, PROBUILD)
            }
        }
        stage('Sync repo to production') {
            steps {
                syncRepo(REPO_NAME, PROBUILD)
            }
        }

    }
    post {
        always {
            script {
                currentBuild.description = "Repo: ${REPO_NAME}"
            }
        }
    }
}
