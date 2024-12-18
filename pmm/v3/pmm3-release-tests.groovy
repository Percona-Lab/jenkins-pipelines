library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runPackageTest(String PMM_VERSION, String REPO) {
    build job: 'package-testing', parameters: [
        string(name: 'DOCKER_VERSION', value: "percona/pmm-server:${PMM_VERSION}"),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'TESTS', value: 'pmm-client'),
        string(name: 'INSTALL_REPO', value: REPO),
        string(name: 'PMM_VERSION', value: PMM_VERSION)
    ]
}

void runUpgradeTest(String FROM_VERSION, String CURRENT_VERSION) {
    build job: 'pmm-upgrade-tests', propagate: false, parameters: [
        string(name: 'ENABLE_EXPERIMENTAL_REPO', value: 'no'),
        string(name: 'ENABLE_TESTING_REPO', value: 'no'),
        string(name: 'DOCKER_VERSION', value: FROM_VERSION),
        string(name: 'CLIENT_VERSION', value: FROM_VERSION),
        string(name: 'PMM_SERVER_LATEST', value: CURRENT_VERSION),
        string(name: 'GIT_BRANCH', value: "pmm-${CURRENT_VERSION}")
    ]
}

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: '3.0.0',
            description: 'PMM3 Server version',
            name: 'VERSION')
    }
    stages {
        stage('Tests Execution') {
            parallel {
                stage('Test: Upgrade from 3.24.0 version') {
                    steps {
                        runUpgradeTest('3.24.0', VERSION)
                    }
                }
                stage('Test: Upgrade from 3.25.0 version') {
                    steps {
                        runUpgradeTest('3.25.0', VERSION)
                    }
                }
                stage('Test: Upgrade from 3.26.0 version') {
                    steps {
                        runUpgradeTest('3.26.0', VERSION)
                    }
                }
                stage('Test: Upgrade from 3.28.0 version') {
                    steps {
                        runUpgradeTest('3.28.0', VERSION)
                    }
                }
                stage('Test: Package testing with main repo') {
                    steps {
                        runPackageTest(VERSION, 'main')
                    }
                }
                stage('Test: Package testing with tools-main repo') {
                    steps {
                        runPackageTest(VERSION, 'tools-main')
                    }
                }
                stage('Test: Upgrade from pmm-client-main repo') {
                    steps {
                        runPackageTest(VERSION, 'pmm-client-main')
                    }
                }
            }
        }
    }
    post {
        success {
            slackSend botUser: true,
                      channel: '#pmm-notifications',
                      color: '#00FF00',
                      message: "PMM release tests for PMM ${VERSION} succeeded"
        }
        failure {
            slackSend botUser: true,
                      channel: '#pmm-notifications',
                      color: '#FF0000',
                      message: "PMM release tests for PMM ${VERSION} failed"
        }
    }
}
