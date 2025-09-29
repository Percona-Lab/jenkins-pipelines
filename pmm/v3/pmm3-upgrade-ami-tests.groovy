library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def versionsList = pmmVersion('v3-ami')
def amiVersions = versionsList.values()
def versions = versionsList.keySet()
def latestVersion = versions[versions.size() - 1]

void runUpgradeJob(String PMM_UI_GIT_BRANCH, AMI_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
    upgradeJob = build job: 'pmm3-upgrade-ami-test-runner', parameters: [
        string(name: 'PMM_UI_GIT_BRANCH', value: PMM_UI_GIT_BRANCH),
        string(name: 'AMI_TAG', value: AMI_TAG),
        string(name: 'DOCKER_TAG_UPGRADE', value: DOCKER_TAG_UPGRADE),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_REPOSITORY', value: CLIENT_REPOSITORY),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'QA_INTEGRATION_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
    ]
}

def generateVariants(String PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, versionsList, latestVersion) {
    def results = new HashMap<>();

    for (version in versionsList.keySet()) {
        if(versionsList[version] == latestVersion) {
            results.put("Run \"$versionsList[version]\" upgrade tests", generateStage(PMM_UI_GIT_BRANCH, versionsList[version], 'perconalab/pmm-server:3-dev-latest', 'pmm3-rc', 'testing', latestVersion, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH))
        } else {
            results.put("Run \"$versionsList[version]\" upgrade tests", generateStage(PMM_UI_GIT_BRANCH, versionsList[version], "perconalab/pmm-server:${latestVersion}-rc", versionsList[version], 'release', latestVersion, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH))
        }
    }



    return results;
}

def generateStage(String PMM_UI_GIT_BRANCH, amiVersion, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
    return {
        stage("Run \"$amiVersion\" upgrade tests") {
            runUpgradeJob(PMM_UI_GIT_BRANCH, amiVersion, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH);
        }
    }
}


pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'PMM Server Version to upgrade to, if empty docker tag will be used from version service.',
            name: 'DOCKER_TAG_UPGRADE')
        choice(
            choices: ["experimental", "testing", "release"],
            description: 'PMM client repository',
            name: 'CLIENT_REPOSITORY')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: 'v3',
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
        stage('UI tests Upgrade Matrix') {
            steps {
                script {
                    parallel generateVariants(PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, versionsList, latestVersion)
                }
            }
        }
    }
}

