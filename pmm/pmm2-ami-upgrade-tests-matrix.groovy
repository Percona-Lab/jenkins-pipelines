library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runAMIUpgradeJob(String GIT_BRANCH, SERVER_VERSION, CLIENT_VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH) {
    upgradeJob = build job: 'pmm2-ami-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'SERVER_VERSION', value: SERVER_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH)
    ]
}

pipeline {
    agent {
        label 'large-amazon'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-ui-tests repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        string(
            defaultValue: '2.22.0',
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
            parallel {
                stage('Upgrade from 2.20.0'){
                    steps {
                        script {
                            runAMIUpgradeJob(GIT_BRANCH,'2.20.0', '2.20.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH);
                        }
                    }
                }
                stage('Upgrade from 2.21.0'){
                    steps {
                        script {
                            runAMIUpgradeJob(GIT_BRANCH,'2.21.0', '2.21.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH);
                        }
                    }
                }
                stage('Upgrade from 2.19.0'){
                    steps {
                        script {
                            runAMIUpgradeJob(GIT_BRANCH,'2.19.0', '2.19.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH);
                        }
                    }
                }
                stage('Upgrade from 2.15.1'){
                    steps {
                        script {
                            runAMIUpgradeJob(GIT_BRANCH,'2.15.1', '2.15.1', PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH);
                        }
                    }
                }
                stage('Upgrade from 2.16.0'){
                    steps {
                        script {
                            runAMIUpgradeJob(GIT_BRANCH,'2.16.0', '2.16.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH);
                        }
                    }
                }
                stage('Upgrade from 2.17.0'){
                    steps {
                        script {
                            runAMIUpgradeJob(GIT_BRANCH,'2.17.0', '2.17.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH);
                        }
                    }
                }
                stage('Upgrade from 2.18.0'){
                    steps {
                        script {
                            runAMIUpgradeJob(GIT_BRANCH,'2.18.0', '2.18.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO, PMM_QA_GIT_BRANCH);
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
