library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runPackageTestingJob(String GIT_BRANCH, DOCKER_VERSION, CLIENT_VERSION, PMM_VERSION, TESTS, METRICS_MODE, INSTALL_REPO) {
    upgradeJob = build job: 'pmm2-package-testing', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'PMM_VERSION', value: PMM_VERSION),
        string(name: 'TESTS', value: TESTS),
        string(name: 'INSTALL_REPO', value: INSTALL_REPO),
        string(name: 'METRICS_MODE', value: METRICS_MODE)
    ]
}

def latestVersion = pmmVersion()

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for package-testing repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH')
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM Client version(dev-latest|pmm2-rc|image-name:version-tag)',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: latestVersion,
            description: 'PMM Version for testing',
            name: 'PMM_VERSION')
        choice(
            choices: ['experimental', 'testing', 'main', 'tools-main', 'pmm2-client-main'],
            description: 'Enable Repo for Client Nodes',
            name: 'INSTALL_REPO')
        choice(
            choices: ['auto', 'push', 'pull'],
            description: 'Select the Metrics Mode for Client',
            name: 'METRICS_MODE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 4 * * *')
    }
    stages {
        stage('Integration Playbook'){
            steps {
                script {
                    runPackageTestingJob(GIT_BRANCH, DOCKER_VERSION, CLIENT_VERSION, PMM_VERSION, 'pmm2-client_integration', METRICS_MODE, INSTALL_REPO);
                }
            }
        }
        stage('Integration Upgrade Playbook'){
            steps {
                script {
                    runPackageTestingJob(GIT_BRANCH, DOCKER_VERSION, CLIENT_VERSION, PMM_VERSION, 'pmm2-client_integration_upgrade', METRICS_MODE, INSTALL_REPO);
                }
            }
        }
        stage('Integration Upgrade Playbook with Custom Path'){
            steps {
                script {
                    runPackageTestingJob(GIT_BRANCH, DOCKER_VERSION, CLIENT_VERSION, PMM_VERSION, 'pmm2-client_integration_upgrade_custom_path', METRICS_MODE, INSTALL_REPO);
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL} "
                } else {
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                }
            }
            deleteDir()
        }
    }
}
