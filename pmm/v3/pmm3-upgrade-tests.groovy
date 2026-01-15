library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runUpgradeJob(String PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, UPGRADE_FLAG) {
    upgradeJob = build job: 'pmm3-upgrade-test-runner', parameters: [
        string(name: 'PMM_UI_PRE_UPGRADE_GIT_BRANCH', value: PMM_UI_PRE_UPGRADE_GIT_BRANCH),
        string(name: 'PMM_UI_GIT_BRANCH', value: PMM_UI_GIT_BRANCH),
        string(name: 'DOCKER_TAG', value: DOCKER_TAG),
        string(name: 'DOCKER_TAG_UPGRADE', value: DOCKER_TAG_UPGRADE),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_REPOSITORY', value: CLIENT_REPOSITORY),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'QA_INTEGRATION_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
        string(name: 'UPGRADE_FLAG', value: UPGRADE_FLAG)
    ]
}

def generateVariants(String PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
    def results = new HashMap<>();
    def UPGRADE_VARIANTS = ["SSL", "EXTERNAL SERVICES", "MONGO BACKUP", "CUSTOM PASSWORD", "CUSTOM DASHBOARDS", "ANNOTATIONS-PROMETHEUS", "ADVISORS-ALERTING", "SETTINGS-METRICS"]
    for (UPGRADE_VARIANT in UPGRADE_VARIANTS) {
        results.put("Run \"$UPGRADE_VARIANT\" upgrade tests", generateStage(PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, UPGRADE_VARIANT))
    }

    return results;
}

def generateStage(String PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, LABEL) {
    return {
        stage("Run \"$LABEL\" upgrade tests") {
            retry(2) {
                runUpgradeJob(PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, LABEL);
            }
        }
    }
}

def versionsList = pmmVersion('v3')[-5..-1]
def oldestVersion = versionsList.first()
def latestVersion = versionsList.last()

pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: "pmm-$oldestVersion",
            description: 'Tag/Branch for UI Tests repository for pre upgrade',
            name: 'PMM_UI_PRE_UPGRADE_GIT_BRANCH')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for UI Tests repository for post upgrade',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: "percona/pmm-server:$oldestVersion",
            description: 'PMM Server Version to test for Upgrade',
            name: 'DOCKER_TAG')
        string(
            defaultValue: '',
            description: 'PMM Server Version to upgrade to, if empty docker tag will be used from version service.',
            name: 'DOCKER_TAG_UPGRADE')
        string(
            defaultValue: "$oldestVersion",
            description: 'PMM Client Version to test for Upgrade',
            name: 'CLIENT_VERSION')
        choice(
            choices: ["experimental", "testing", "release"],
            description: 'PMM client repository',
            name: 'CLIENT_REPOSITORY')
        string(
            defaultValue: "$latestVersion",
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    triggers {
        cron('0 3 * * *')
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    currentBuild.description = "Upgrade for PMM from ${env.DOCKER_TAG.split(":")[1]} to ${env.PMM_SERVER_LATEST}."
                }
            }
        }
        stage('UI tests Upgrade Matrix') {
            steps {
                script {
                    parallel generateVariants(PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH)
                }
            }
        }
    }
}

