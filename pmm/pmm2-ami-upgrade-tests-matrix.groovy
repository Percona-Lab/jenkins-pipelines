library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runAMIUpgradeJob(String PMM_UI_TESTS_BRANCH, String PMM_VERSION, String PMM_SERVER_LATEST, String ENABLE_TESTING_REPO, PMM_QA_BRANCH) {
    upgradeJob = build job: 'pmm2-ami-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: PMM_UI_TESTS_BRANCH),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'SERVER_VERSION', value: PMM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_BRANCH)
    ]
}

String enableTestingRepo
String pmmServerLatestVersion
List amiVersions = pmmVersion('ami').keySet() as List
List versions = amiVersions[-5..-1]
def parallelStagesMatrix = versions.collectEntries {String it ->
    // Skip versions "2.40.1" and "2.42.0"
    if (it == "2.40.1" || it == "2.42.0") {
        return [:]
    }
    if ("${params.UPGRADE_TO}" == "dev-latest") {
        enableTestingRepo = 'no'
        pmmServerLatestVersion = pmmVersion()
    } else {
        enableTestingRepo = 'yes'
        pmmServerLatestVersion = pmmVersion('rc')
    }
    ["${it} -> ${pmmServerLatestVersion}" : generateStage(it, pmmServerLatestVersion, enableTestingRepo)]
}

def generateStage(String version, String latest, String enableRepo) {
    return {
        stage("${version}") {
            runAMIUpgradeJob(PMM_UI_TESTS_BRANCH, version, latest, enableRepo, PMM_QA_BRANCH)
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
