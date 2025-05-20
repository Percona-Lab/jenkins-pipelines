library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def pmmVersions = pmmVersion('v3')
def pmmVersionsOld = pmmVersion()

void runUpgradeJob(String PMM_UI_GIT_BRANCH, DOCKER_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, UPGRADE_FLAG) {
    upgradeJob = build job: 'pmm3-upgrade-test-runner', parameters: [
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

def generateVariants(String PMM_UI_GIT_BRANCH, DOCKER_TAG_UPGRADE, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
    def results = new HashMap<>();
    echo "Version: ${pmmVersions}"
    echo "Old Version: ${pmmVersionsOld}"
    for (pmmVersion in pmmVersions) {
        results.put(
            "Run matrix upgrade tests from version: \"$version\"",
            {
                stage("Run \"$LABEL\" upgrade tests") {
                    retry(2) {
                        runUpgradeJob(PMM_UI_GIT_BRANCH, 'percona/pmm-server:' + version, DOCKER_TAG_UPGRADE, version, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, LABEL);
                    }
                }
            }
        )
    }

    return results;
}

// def latestVersion = pmmVersion('v3')[0]

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
            defaultValue: '',
            description: 'PMM Server Version to upgrade to, if empty docker tag will be used from version service.',
            name: 'DOCKER_TAG_UPGRADE')
        choice(
            choices: ["experimental", "testing", "release"],
            description: 'PMM client repository',
            name: 'CLIENT_REPOSITORY')
        string(
            defaultValue: '3.2.0',
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
        cron('0 3 * * *')
    }
    stages {
        stage('UI tests Upgrade Matrix') {
            steps {
                script {
                    echo "Version: ${pmmVersions}"
                    parallel generateVariants(PMM_UI_GIT_BRANCH, DOCKER_TAG_UPGRADE, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH)
                }
            }
        }
    }
}

