library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runUpgradeJob(String PMM_UI_GIT_BRANCH, PMM_VERSION, PMM_SERVER_LATEST, REPO_TO_ENABLE, PERFORM_DOCKER_WAY_UPGRADE, PMM_SERVER_TAG) {
    upgradeJob = build job: 'pmm2-upgrade-tests', parameters: [
        string(name: 'PMM_UI_GIT_BRANCH', value: PMM_UI_GIT_BRANCH),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'DOCKER_VERSION', value: PMM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'REPO_TO_ENABLE', value: REPO_TO_ENABLE),
        string(name: 'PERFORM_DOCKER_WAY_UPGRADE', value: PERFORM_DOCKER_WAY_UPGRADE),
        string(name: 'PMM_SERVER_TAG', value: PMM_SERVER_TAG)
    ]
}

def versions = pmmVersion('list')
def parallelStagesMatrix = versions.collectEntries {
    ["${it}" : generateStage(it)]
}

def generateStage(VERSION) {
    return {
        stage("${VERSION}") {
            runUpgradeJob(
                PMM_UI_GIT_BRANCH,
                VERSION,
                PMM_SERVER_LATEST,
                REPO_TO_ENABLE,
                PERFORM_DOCKER_WAY_UPGRADE,
                PMM_SERVER_TAG
            )
        }
    }
}

def latestVersion = pmmVersion()

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: latestVersion,
            description: 'dev-latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: latestVersion,
            description: 'RC PMM Server Version',
            name: 'PMM_SERVER_RC')
        choice(
            choices: ['experimental', 'testing', 'release'],
            description: 'Repo to enable (experimental - dev-latest, testing - rc, release - stable)',
            name: 'REPO_TO_ENABLE')
        choice(
            choices: ['no', 'yes'],
            description: 'Perform Docker way Upgrade using this option',
            name: 'PERFORM_DOCKER_WAY_UPGRADE')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server Tag to be Upgraded to via Docker way Upgrade',
            name: 'PMM_SERVER_TAG')
    }
    options {
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 3 * * *')
    }
    stages{
        stage('Upgrade Matrix'){
            steps{
                script {
                    parallel parallelStagesMatrix
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                }
            }
        }
    }
}
