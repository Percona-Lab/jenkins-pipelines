library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runAMIUpgradeJob(String PMM_UI_TESTS_BRANCH, String PMM_VERSION, String PMM_SERVER_LATEST, String REPO_TO_ENABLE, PMM_QA_BRANCH) {
    upgradeJob = build job: 'pmm2-ami-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: PMM_UI_TESTS_BRANCH),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'SERVER_VERSION', value: PMM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'REPO_TO_ENABLE', value: REPO_TO_ENABLE),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_BRANCH)
    ]
}

String enableTestingRepo
String pmmServerLatestVersion
List amiVersions = pmmVersion('ami').keySet() as List
List versions = amiVersions[-5..-1]
def parallelStagesMatrix = versions.collectEntries {String it ->
    ["${it} -> ${pmmServerLatestVersion}" : generateStage(it, PMM_SERVER_LATEST, REPO_TO_ENABLE)]
}

def generateStage(String version, String latest, String repoToEnable) {
    return {
        stage("${version}") {
            runAMIUpgradeJob(PMM_UI_TESTS_BRANCH, version, latest, repoToEnable, PMM_QA_BRANCH)
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
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        choice(
            choices: ['experimental', 'testing', 'release'],
            description: 'Repo to enable (experimental - dev-latest, testing - rc, release - stable)',
            name: 'REPO_TO_ENABLE')
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
