library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def pmmVersions = pmmVersion('v3')
def latestVersion = pmmVersions[0]

void runUpgradeJob(String PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
    upgradeJob = build job: 'pmm3-upgrade-tests', parameters: [
        string(name: 'PMM_UI_GIT_BRANCH', value: PMM_UI_GIT_BRANCH),
        string(name: 'DOCKER_TAG', value: DOCKER_TAG),
        string(name: 'DOCKER_TAG_UPGRADE', value: DOCKER_TAG_UPGRADE),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_REPOSITORY', value: CLIENT_REPOSITORY),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'QA_INTEGRATION_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
    ]
}

def generateVariants(String PMM_UI_GIT_BRANCH, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, pmmVersions) {
    def results = new HashMap<>();
    def latestVersion = PMM_SERVER_LATEST;

    for (pmmVersion in pmmVersions) {
        if(pmmVersion == latestVersion) {
            results.put(
                "Run matrix upgrade tests from version: \"$pmmVersion\"",
                generateStage(PMM_UI_GIT_BRANCH, "perconalab/pmm-server:${pmmVersion}-rc", 'perconalab/pmm-server:3-dev-latest', 'pmm3-rc', 'testing', PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH)
            )
        } else {
            results.put(
                "Run matrix upgrade tests from version: \"$pmmVersion\"",
                generateStage(PMM_UI_GIT_BRANCH, 'percona/pmm-server:' + pmmVersion, "perconalab/pmm-server:${latestVersion}-rc", pmmVersion, 'release', PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH)
            )
        }
    }

    return results;
}

def generateStage(String PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
    return {
        stage("Run \"$pmmVersion\" upgrade tests") {
            runUpgradeJob(PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH);
        }
    }
}



pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: latestVersion,
            description: 'latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
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
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 1 * * *')
    }
    stages {
        stage('UI tests Upgrade Matrix') {
            steps {
                script {
                    parallel generateVariants(PMM_UI_GIT_BRANCH, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, pmmVersions)
                }
            }
        }
    }
}

