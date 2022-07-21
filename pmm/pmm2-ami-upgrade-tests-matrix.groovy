library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runAMIUpgradeJob(String GIT_BRANCH, PMM_VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH) {
    upgradeJob = build job: 'pmm2-ami-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'SERVER_VERSION', value: PMM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

def latestVersion = pmmVersion()

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: latestVersion,
            description: 'dev-latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        choice(
            choices: ['no', 'yes'],
            description: 'Enable Testing Repo for RC',
            name: 'ENABLE_TESTING_REPO')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 1 * * 7')
    }
    stages {
        stage('Run AMI Upgrade Matrix') {
            matrix {
                agent any
                axes {
                    axis {
                        name 'VERSION'
                        values '2.22.0', '2.23.0', '2.24.0', '2.25.0', '2.26.0', '2.27.0', '2.28.0'
                    }
                }
                stages {
                    stage('Upgrade'){
                        steps {
                            script {
                                runAMIUpgradeJob(GIT_BRANCH, VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH)
                            }
                        }
                    }
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
