library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runUITests(DOCKER_VERSION, CLIENT_VERSION, GIT_BRANCH, PMM_QA_GIT_BRANCH) {
    uiTestJob = build job: 'pmm2-ui-tests', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

void runUITestsNightly(DOCKER_VERSION, CLIENT_VERSION, GIT_BRANCH, PMM_QA_GIT_BRANCH) {
    runUITestsNightly = build job: 'pmm2-ui-test-nightly', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

void dbaase2eTests(DOCKER_VERSION, CLIENT_VERSION, GIT_BRANCH, PMM_QA_GIT_BRANCH) {
    dbaase2eTests = build job: 'pmm2-dbaas-e2e-tests', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

void runOVFTests(CLIENT_VERSION, GIT_BRANCH) {
    ovfTests = build job: 'pmm2-ovf-image-test', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH)
    ]
}

void runUpgradeTests(DOCKER_VERSION, CLIENT_VERSION, GIT_BRANCH, PMM_SERVER_TAG) {
    runUpgradeTests = build job: 'pmm2-upgrade-tests', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'PMM_SERVER_TAG', value: PMM_SERVER_TAG)
    ]
}

void runAMIUpgradeTests(DOCKER_VERSION, CLIENT_VERSION, GIT_BRANCH, PMM_QA_GIT_BRANCH) {
    runAMIUpgradeTests = build job: 'pmm2-ami-upgrade-tests', parameters: [
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'SERVER_VERSION', value: DOCKER_VERSION),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

def versionsList = pmmVersion('list')

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        choice(
            choices: versionsList,
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_VERSION')
        choice(
            choices: versionsList,
            description: 'PMM2 Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server Tag to be Upgraded to via Docker way Upgrade',
            name: 'PMM_SERVER_TAG')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client Tag, which client to be installed',
            name: 'PMM_CLIENT_TAG')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Trigger Test Jobs') {
            parallel {
                stage('Start UI Tests') {
                    steps {
                        runUITests(PMM_SERVER_TAG, PMM_CLIENT_TAG, GIT_BRANCH, PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Start DBaaS E2E Tests') {
                    steps {
                        dbaase2eTests(PMM_SERVER_TAG, PMM_CLIENT_TAG, GIT_BRANCH, PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Start Upgrade Tests') {
                    steps {
                        runUpgradeTests(DOCKER_VERSION, CLIENT_VERSION, GIT_BRANCH, PMM_SERVER_TAG)
                    }
                }
                stage('Start AMI Upgrade') {
                    steps {
                        runAMIUpgradeTests(DOCKER_VERSION, CLIENT_VERSION, GIT_BRANCH, PMM_QA_GIT_BRANCH)
                    }
                }
                stage('Start OVF Image Tests') {
                    steps {
                        runOVFTests(PMM_CLIENT_TAG, GIT_BRANCH)
                    }
                }
                stage('Start UI Tests Nightly') {
                    steps {
                        runUITestsNightly(PMM_SERVER_TAG, PMM_CLIENT_TAG, GIT_BRANCH, PMM_QA_GIT_BRANCH)
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
            }
        }
        failure {
            script {
                slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL} "
            }
        }
    }
}
