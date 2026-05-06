library changelog: false, identifier: 'lib@PMM-7-fix-concurrency-builds', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runOVFUpgradeJob(PMM_VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH) {
    build job: 'pmm-ovf-upgrade-tests', parameters: [
        string(name: 'SERVER_VERSION', value: PMM_VERSION),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'ENABLE_EXPERIMENTAL_REPO', value: 'no'),
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
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: latestVersion,
            description: '3-dev-latest PMM Server Version',
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
        cron('0 1 * * 0')
    }
    stages {
        stage('OVF Upgrade Matrix') {
            steps {
                script {
                    parallel parallelStagesMatrix
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
