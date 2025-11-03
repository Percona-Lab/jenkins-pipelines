library changelog: false, identifier: 'lib@PMM-14156', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def versionsList = pmmVersion('v3-ami')
def amiVersions = versionsList.values().toList()[-6..-1]
def versions = versionsList.keySet().toList()[-6..-1]
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

def generateVariants(String PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, CLIENT_REPOSITORY, versionsList, latestVersion) {
    def results = new HashMap<>();
    def upgradeVersions = versionsList.keySet().toList()[-6..-1];
    if(CLIENT_REPOSITORY != 'experimental') {
        upgradeVersions.pop();
    }
    println upgradeVersions;
    println versionsList.keySet().last();
    println versionsList.keySet()[versionsList.keySet().size() - 1];
    def iterator = 0;

    for (version in upgradeVersions) {
        def upgradeVersion = versionsList[version];


        if(version == latestVersion && CLIENT_REPOSITORY == 'experimental') {
            results.put("Upgrade AMI PMM from ${version} (AMI tag: ${upgradeVersion}) to repo: experimental.", generateStage(PMM_UI_GIT_BRANCH, upgradeVersion, 'perconalab/pmm-server:3-dev-latest', 'pmm3-rc', 'experimental', versionsList.keySet().last(), PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, iterator++))
        } else {
            if(CLIENT_REPOSITORY != 'experimental') {
                latestVersion = versionsList.keySet()[versionsList.keySet().size() - 1]
                if(version == versionsList.keySet()[versionsList.keySet().size() - 1]) {
                    continue;
                }
            }

            def upgradeDockerTag;
            def latestUpgradeVersion;

            if(CLIENT_REPOSITORY == 'experimental') {
                upgradeDockerTag = 'perconalab/pmm-server:3-dev-latest'
                latestUpgradeVersion = versionsList.keySet()[versionsList.keySet().size() - 1]
            } else if (CLIENT_REPOSITORY == 'testing') {
                upgradeDockerTag = "perconalab/pmm-server:${latestVersion}-rc"
                latestUpgradeVersion = versionsList.keySet()[versionsList.keySet().size() - 2]
            }

            if(upgradeVersion != versionsList.last()) {
                results.put("Upgrade AMI PMM from ${version} (AMI tag: ${upgradeVersion}) to repo: testing.", generateStage(PMM_UI_GIT_BRANCH, upgradeVersion, upgradeDockerTag, version, CLIENT_REPOSITORY, latestUpgradeVersion, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, iterator++))
            }

        }
    }

    return results;
}

def generateStage(String PMM_UI_GIT_BRANCH, amiVersion, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, int iterator) {
    return {
        stage("Upgrade AMI PMM from ${CLIENT_VERSION} (AMI tag: ${amiVersion}) to repo: ${CLIENT_REPOSITORY}.") {
//             retry(2) {
                runUpgradeJob(PMM_UI_GIT_BRANCH, amiVersion, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH);
//             }
        }
    }
}


pipeline {
    agent {
        label 'docker'
    }
    parameters {
        string(
            defaultValue: 'PMM-14156',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ["testing", "experimental", "release"],
            description: 'PMM client repository',
            name: 'CLIENT_REPOSITORY')
        string(
            defaultValue: 'PMM-14156-ami-ovf',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
    }
    options {
        timeout(time: 300, unit: 'MINUTES')
    }
    stages {
        stage('UI tests Upgrade Matrix') {
            steps {
                script {
                    parallel generateVariants(PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, CLIENT_REPOSITORY, versionsList, latestVersion)
                }
            }
        }
    }
}

