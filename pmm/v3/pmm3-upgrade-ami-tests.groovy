library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

def versionsList = pmmVersion('v3-ami')
def amiVersions = versionsList.values().toList()[-5..-1]
def versions = versionsList.keySet().toList()[-5..-1]
def latestVersion = versions[versions.size() - 1]

void runUpgradeJob(String PMM_UI_PRE_UPGRADE_GIT_BRANCH, PMM_UI_GIT_BRANCH, AMI_TAG, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
//     upgradeJob = build job: 'pmm3-upgrade-ami-test-runner', parameters: [\
//         string(name: 'PMM_UI_PRE_UPGRADE_GIT_BRANCH', value: PMM_UI_PRE_UPGRADE_GIT_BRANCH),
//         string(name: 'PMM_UI_GIT_BRANCH', value: PMM_UI_GIT_BRANCH),
//         string(name: 'AMI_TAG', value: AMI_TAG),
//         string(name: 'DOCKER_TAG_UPGRADE', value: DOCKER_TAG_UPGRADE),
//         string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
//         string(name: 'CLIENT_REPOSITORY', value: CLIENT_REPOSITORY),
//         string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
//         string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
//         string(name: 'QA_INTEGRATION_GIT_BRANCH', value: QA_INTEGRATION_GIT_BRANCH),
//     ]
}

def generateVariants(String PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, CLIENT_REPOSITORY, versionsList, latestVersion) {
    def results = new HashMap<>();
    def upgradeVersions = versionsList.keySet().toList()[-6..-1];

    println upgradeVersions

    for (pmmVersion in versionsList) {
        def upgradeVersion = versionsList[pmmVersion];
        if(pmmVersion == upgradeVersions.last()) {
            results.put(
                "Upgrade AMI PMM from ${pmmVersion} (AMI tag: ${upgradeVersion}) to: 'perconalab/pmm-server:3-dev-latest'",
                generateStage(
                    PMM_UI_GIT_BRANCH,
                    upgradeVersion,
                    'perconalab/pmm-server:3-dev-latest',
                    pmmVersion,
                    CLIENT_REPOSITORY,
                    latestVersion,
                    PMM_QA_GIT_BRANCH,
                    QA_INTEGRATION_GIT_BRANCH
                )
            )
        } else {
            results.put(
                "Upgrade AMI PMM from ${pmmVersion} (AMI tag: ${upgradeVersion}) to: perconalab/pmm-server:${upgradeVersions.last()}-rc",
                generateStage(
                    PMM_UI_GIT_BRANCH,
                    upgradeVersion,
                    "perconalab/pmm-server:${upgradeVersions.last()}-rc",
                    pmmVersion,
                    CLIENT_REPOSITORY,
                    latestVersion,
                    PMM_QA_GIT_BRANCH,
                    QA_INTEGRATION_GIT_BRANCH
                )
            )
        }
    }

    println results

    return results;
}

def generateStage(String PMM_UI_GIT_BRANCH, amiVersion, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH) {
    return {
        stage("Upgrade AMI PMM from ${CLIENT_VERSION} (AMI tag: ${amiVersion}) to repo: ${CLIENT_REPOSITORY}.") {
            retry(2) {
                runUpgradeJob("pmm-$CLIENT_VERSION", PMM_UI_GIT_BRANCH, amiVersion, DOCKER_TAG_UPGRADE, CLIENT_VERSION, CLIENT_REPOSITORY, PMM_SERVER_LATEST, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH);
            }
        }
    }
}


pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM-14156',
            description: 'Tag/Branch for UI Tests repository',
            name: 'PMM_UI_GIT_BRANCH')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ["testing", "experimental", "release"],
            description: 'PMM client repository for upgrade',
            name: 'CLIENT_REPOSITORY')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for qa-integration repository',
            name: 'QA_INTEGRATION_GIT_BRANCH')
    }
    options {
        timeout(time: 300, unit: 'MINUTES')
    }
    stages {
        stage('UI tests Upgrade Matrix') {
            steps {
                println versionsList
                println amiVersions
                println versions
                script {
                    parallel generateVariants(PMM_UI_GIT_BRANCH, PMM_QA_GIT_BRANCH, QA_INTEGRATION_GIT_BRANCH, CLIENT_REPOSITORY, versionsList, latestVersion)
                }
            }
        }
    }
}

