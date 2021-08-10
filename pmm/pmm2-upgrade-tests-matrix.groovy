library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
void runUpgradeJob(String GIT_BRANCH, DOCKER_VERSION, CLIENT_VERSION, PMM_SERVER_LATEST, ENABLE_TESTING_REPO) {
    upgradeJob = build job: 'pmm2-upgrade-tests', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: PMM_SERVER_LATEST),
        string(name: 'ENABLE_TESTING_REPO', value: ENABLE_TESTING_REPO)
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
            defaultValue: '2.21.0',
            description: 'dev-latest PMM Server Version',
            name: 'PMM_SERVER_LATEST')
        string(
            defaultValue: '2.21.0',
            description: 'RC PMM Server Version',
            name: 'PMM_SERVER_RC')
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
        cron('0 3 * * *') 
    }
    stages {
        stage('Run Upgrade Matrix-1') {
            parallel {
                stage('Upgrade from 2.20.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.20.0', '2.20.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.9.1'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.9.1', '2.9.1', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.10.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.10.0', '2.10.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.10.1'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.10.1', '2.10.1', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.11.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.11.0', '2.11.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.11.1'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.11.1', '2.11.1', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.12.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.12.0', '2.12.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.13.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.13.0', '2.13.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.14.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.14.0', '2.14.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.15.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.15.0', '2.15.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.15.1'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.15.1', '2.15.1', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.16.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.16.0', '2.16.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.17.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.17.0', '2.17.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.18.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.18.0', '2.18.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
                        }
                    }
                }
                stage('Upgrade from 2.19.0'){
                    steps {
                        script {
                            runUpgradeJob(GIT_BRANCH,'2.19.0', '2.19.0', PMM_SERVER_LATEST, ENABLE_TESTING_REPO );
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
