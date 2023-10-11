library changelog: false, identifier: 'lib@master', retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

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

def devLatestVersion = pmmVersion()
def amiVersions = pmmVersion('ami').keySet() as List
def versions = amiVersions[-5..-1]
def parallelStagesMatrix = versions.collectEntries {
    def to = params.PMM_SERVER_LATEST
    ["${it}->${to}" : generateStage(it)]
}

def generateStage(VERSION) {
    return {
        stage("${VERSION} -> ${PMM_SERVER_LATEST}") {
            runAMIUpgradeJob(PMM_UI_TESTS_BRANCH, VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_BRANCH)
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
        string(
                defaultValue: devLatestVersion,
                description: 'Upgrade to version:',
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
    stages{
        stage('AMI Upgrade Matrix'){
            steps{
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
