library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runUpgradeJob(String PMM_UI_GIT_BRANCH, DOCKER_TAG, CLIENT_VERSION, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, UPGRADE_FLAG) {
    upgradeJob = build job: 'pmm3-upgrade-test-runner', parameters: [
        string(name: 'PMM_UI_GIT_BRANCH', value: PMM_UI_GIT_BRANCH),
        string(name: 'DOCKER_TAG', value: DOCKER_TAG),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'QA_INTEGRATION_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
        string(name: 'UPGRADE_FLAG', value: UPGRADE_FLAG)
    ]
}

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM-7-upgrade-job-pmm3',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: 'perconalab/pmm-server:202410302129',
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_TAG')
        string(
            defaultValue: "3-dev-latest",
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        choice(
            choices: ["3.0.0"],
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: 'PMM-13481',
            description: 'Tag/Branch for qa-integration repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
    }
    options {
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 3 * * *')
    }
    stages {
        stage('UI tests Upgrade Matrix') {
            parallel {
                stage('Run SSL upgrade tests'){
                    steps {
                        script {
                            runUpgradeJob(PMM_UI_GIT_BRANCH, DOCKER_TAG, CLIENT_VERSION, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, 'SSL');
                        }
                    }
                }
                stage('Run custom password upgrade tests'){
                    steps {
                        script {
                            runUpgradeJob(PMM_UI_GIT_BRANCH, DOCKER_TAG, CLIENT_VERSION, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, 'CUSTOM PASSWORD');
                        }
                    }
                }
                stage('Run custom password upgrade tests'){
                    steps {
                        script {
                            runUpgradeJob(PMM_UI_GIT_BRANCH, DOCKER_TAG, CLIENT_VERSION, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, 'CUSTOM DASHBOARDS');
                        }
                    }
                }
            }
        }
    }
}
