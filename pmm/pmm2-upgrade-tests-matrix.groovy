library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runUpgradeJob(String GIT_BRANCH, PMM_VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, ENABLE_EXPERIMENTAL_REPO, PERFORM_DOCKER_WAY_UPGRADE, PMM_SERVER_TAG) {
    upgradeJob = build job: 'pmm2-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'DOCKER_VERSION', value: PMM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'ENABLE_EXPERIMENTAL_REPO', value: ENABLE_EXPERIMENTAL_REPO),
        string(name: 'PERFORM_DOCKER_WAY_UPGRADE', value: PERFORM_DOCKER_WAY_UPGRADE),
        string(name: 'PMM_SERVER_TAG', value: PMM_SERVER_TAG)
    ]
}


def versions = pmmVersion('list_with_old')
def parallelStagesMatrix = versions.collectEntries {
    ["${it}" : generateStage(it)]
}

def generateStage(VERSION) {
    return {
        stage("${VERSION}") {
            runUpgradeJob(
                GIT_BRANCH,
                VERSION,
                PMM_SERVER_LATEST,
                ENABLE_TESTING_REPO,
                ENABLE_EXPERIMENTAL_REPO,
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
            name: 'GIT_BRANCH')
        string(
            defaultValue: latestVersion,
            description: 'dev-latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: latestVersion,
            description: 'RC PMM Server Version',
            name: 'PMM_SERVER_RC')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo for RC',
            name: 'ENABLE_TESTING_REPO')
        choice(
            choices: ['yes', 'no'],
            description: 'Enable EXPERIMENTAL Repo for Dev-latest',
            name: 'ENABLE_EXPERIMENTAL_REPO')
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
            deleteDir()
        }
    }
}
