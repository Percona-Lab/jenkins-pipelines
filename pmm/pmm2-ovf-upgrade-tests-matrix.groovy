library changelog: false, identifier: 'lib@PMM-8016', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runOVFUpgradeJob(String GIT_BRANCH, PMM_VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH) {
    upgradeJob = build job: 'pmm2-ovf-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'SERVER_VERSION', value: PMM_VERSION),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

def latestVersion = pmmVersion()
def versionsList = pmmVersion('ovf');


def parallelStagesMatrix = versionsList.collectEntries {
    ["${it}" : generateStage(it)]
}

def generateStage(VERSION) {
    return {
        stage("${VERSION}") {
            runOVFUpgradeJob(
                GIT_BRANCH,
                VERSION,
                PMM_SERVER_LATEST,
                ENABLE_TESTING_REPO,
                PMM_QA_GIT_BRANCH
            )
        }
    }
}

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
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: latestVersion,
            description: 'dev-latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo for RC',
            name: 'ENABLE_TESTING_REPO')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 1 * * 7')
    }
    stages {
        stage('Upgrade OVF Upgrade Matrix'){
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
