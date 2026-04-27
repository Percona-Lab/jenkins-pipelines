library changelog: false, identifier: 'lib@PMM-7-fix-upgrade-3-7-0', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def pmmVersions = pmmVersion('v3')[-5..-1]
def latestVersion = pmmVersion('v3').last()

void runUpgradeJob(String DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_QA_GIT_BRANCH, latestVersion) {
    build job: 'pmm3-docker-way-upgrade-tests', parameters: [
        string(name: 'DOCKER_TAG', value: DOCKER_TAG),
        string(name: 'DOCKER_TAG_UPGRADE', value: DOCKER_TAG_UPGRADE),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'CLIENT_REPOSITORY', value: CLIENT_REPOSITORY),
        string(name: 'PMM_SERVER_LATEST', value: latestVersion),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
    ]
}

def generateVariants(PMM_QA_GIT_BRANCH, pmmVersions, latestVersion) {
    def results = new HashMap<>();

// 3.0.0, 3.1.0, 3.2.0, 3.3.0, 3.3.1, 3.4.0, 3.4.1, 3.5.0
    for (pmmVersion in pmmVersions) {
        if(pmmVersion == pmmVersions.last()) {
            results.put(
                "Run matrix upgrade tests from version: \"$pmmVersion\"",
                generateStage(pmmVersion.toString(), "perconalab/pmm-server:${pmmVersion}-rc", 'perconalab/pmm-server:3-dev-latest', 'pmm3-rc', 'testing', PMM_QA_GIT_BRANCH, latestVersion)
            )
        } else {
            results.put(
                "Run matrix upgrade tests from version: \"$pmmVersion\"",
                generateStage(pmmVersion.toString(), 'percona/pmm-server:' + pmmVersion, "perconalab/pmm-server:${pmmVersions.last()}-rc", pmmVersion, 'release', PMM_QA_GIT_BRANCH, latestVersion)
            )
        }
    }

    return results;
}

def generateStage(String stageLabel, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_QA_GIT_BRANCH, latestVersion) {
    return {
        stage("Run \"$stageLabel\" upgrade tests") {
            runUpgradeJob(DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_QA_GIT_BRANCH, latestVersion);
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
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('UI tests Upgrade Matrix') {
            steps {
                script {
                    parallel generateVariants(PMM_QA_GIT_BRANCH, pmmVersions, latestVersion)
                }
            }
        }
    }
}
