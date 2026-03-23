library changelog: false, identifier: 'lib@PMM-7-fix-new-navigation-upgrade', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def pmmVersions = pmmVersion('v3')[-5..-1]
def latestVersion = pmmVersion('v3').last()
def oldVersions = pmmVersion('v3-old')

void runUpgradeJob(String PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, latestVersion) {
    upgradeJob = build job: 'pmm3-upgrade-tests', parameters: [
        string(name: 'PMM_UI_PRE_UPGRADE_GIT_BRANCH', value: PMM_UI_PRE_UPGRADE_GIT_BRANCH),
        string(name: 'PMM_UI_GIT_BRANCH', value: PMM_UI_GIT_BRANCH),
        string(name: 'DOCKER_TAG', value: DOCKER_TAG),
        string(name: 'DOCKER_TAG_UPGRADE', value: DOCKER_TAG_UPGRADE),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_REPOSITORY', value: CLIENT_REPOSITORY),
        string(name: 'PMM_SERVER_LATEST', value: latestVersion),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'QA_INTEGRATION_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
    ]
}

def generateVariants(String PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, pmmVersions, oldVersions, latestVersion) {
    def results = new HashMap<>();

    for (pmmVersion in pmmVersions) {
        if(pmmVersion == pmmVersions.last()) {
            def pmmClientVersion = 'pmm3-rc';
            results.put(
                "Run matrix upgrade tests from version: \"$pmmVersion\"",
                generateStage("pmm-${pmmVersion}", PMM_UI_GIT_BRANCH, "perconalab/pmm-server:${pmmVersion}-rc", 'perconalab/pmm-server:3-dev-latest', pmmClientVersion, 'testing', PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, "3.8.0")
            )
        } else {
            def pmmClientVersion = pmmVersion in oldVersions ? "https://downloads.percona.com/downloads/pmm3/${pmmVersion}/binary/tarball/pmm-client-${pmmVersion}-x86_64.tar.gz" : pmmVersion;
            println pmmClientVersion
            results.put(
                "Run matrix upgrade tests from version: \"$pmmVersion\"",
                generateStage("pmm-${pmmVersion}", PMM_UI_GIT_BRANCH, 'percona/pmm-server:' + pmmVersion, "perconalab/pmm-server:${pmmVersions.last()}-rc", pmmClientVersion, 'release', PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, latestVersion)
            )
        }
    }

    return results;
}

def generateStage(String PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, latestVersion) {
    return {
        stage("Run \"$pmmVersion\" upgrade tests") {
            runUpgradeJob(PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, latestVersion);
        }
    }
}



pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'pmm-3.7.0-rc',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: 'PMM-7-fix-check-client-upgrade',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('UI tests Upgrade Matrix') {
            steps {
                script {
                    parallel generateVariants(PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, pmmVersions, oldVersions, latestVersion)
                }
            }
        }
    }
}

