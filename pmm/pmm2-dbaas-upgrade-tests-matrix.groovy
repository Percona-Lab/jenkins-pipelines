library changelog: false, identifier: 'lib@PMM-9610', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runDbaaSUpgradeTests(String GIT_BRANCH, DOCKER_VERSION, CLIENT_VERSION, PMM_SERVER_LATEST, VERSION_SERVICE_VERSION, PMM_REPOSITORY, PMM_QA_GIT_BRANCH) {
    upgradeJob = build job: 'pmm2-dbaas-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'VERSION_SERVICE_VERSION', value: VERSION_SERVICE_VERSION),
        string(name: 'PMM_REPOSITORY', value: PMM_REPOSITORY),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

def latestVersion = pmmVersion()
def versionsList = pmmVersion('dbaas')

def parallelStagesMatrix = versionsList.collectEntries {
    ["${it}" : generateStage(it)]
}

def generateStage(VERSION) {
    return {
        stage("${VERSION}") {
            runDbaaSUpgradeTests(
                GIT_BRANCH,
                VERSION,
                VERSION,
                PMM_SERVER_LATEST,
                VERSION_SERVICE_VERSION,
                PMM_REPOSITORY,
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
            choices: ['dev','prod'],
            description: 'Prod or Dev version service',
            name: 'VERSION_SERVICE_VERSION')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['Experimental', 'Testing'],
            description: "Select Testing (RC Tesing) or Experimental (dev-latest testing) Repository",
            name: 'PMM_REPOSITORY')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 1 * * 7')
    }
    stages{
        stage('Upgrade DbaaS E2E Matrix'){
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
