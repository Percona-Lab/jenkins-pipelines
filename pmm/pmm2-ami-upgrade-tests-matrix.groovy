library changelog: false, identifier: 'lib@PMM-7-jobs-improve', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _


def enableTestingRepo
def pmmServerLatestVersion
List amiVersions = pmmVersion('ami').keySet() as List
def versions = amiVersions[-5..-1]

void runAMIUpgradeJob(String PMM_UI_TESTS_BRANCH, PMM_VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_BRANCH) {
    upgradeJob = build job: 'pmm2-ami-upgrade-tests', parameters: [
            string(name: 'GIT_BRANCH', value: PMM_UI_TESTS_BRANCH),
            string(name: 'CLIENT_VERSION', value: PMM_VERSION),
            string(name: 'SERVER_VERSION', value: PMM_VERSION),
            string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
            string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
            string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_BRANCH)
    ]
}

def parallelStagesMatrix = versions.collectEntries { it ->
//    String ver = pmmServerLatestVersion
//    String repo = enableTestingRepo
//    ["${it}" : generateStage(it, "${ver}", "${repo}")]
    ["${it}" : generateStage(it)]
}

def generateStage(VERSION) {
    return {
        stage("${VERSION}") {
            runAMIUpgradeJob(PMM_UI_TESTS_BRANCH, VERSION, "2.41.0", "no", PMM_QA_BRANCH)
//            runAMIUpgradeJob(PMM_UI_TESTS_BRANCH, VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_BRANCH)
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
            name: 'PMM_UI_TESTS_BRANCH')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_BRANCH')
        choice(
                choices: ['dev-latest', 'release candidate'],
                description: 'Upgrade to:',
                name: 'UPGRADE_TO')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 1 * * 7')
    }
    stages{
        stage('Process choices') {
            steps {
                script {
                    if ("${params.UPGRADE_TO}" == "dev-latest") {
                        enableTestingRepo = 'no'
                        pmmServerLatestVersion = pmmVersion()
                    } else {
                        enableTestingRepo = 'yes'
                        pmmServerLatestVersion = pmmVersion('rc')
                    }
                    echo "Starting with the following parameters: 'ENABLE_TESTING_REPO' = '${enableTestingRepo}'; " +
                            "'PMM_SERVER_LATEST' = '${pmmServerLatestVersion}'"
                }
            }
        }
        stage('AMI Upgrade Matrix'){
            steps{
                script {
                    echo parallelStagesMatrix
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
