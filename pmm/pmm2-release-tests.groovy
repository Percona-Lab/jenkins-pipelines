library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runPackageTest(String PMM_VERSION, String REPO) {
    build job: 'package-testing', parameters: [
        string(name: 'DOCKER_VERSION', value: "percona/pmm-server:${PMM_VERSION}"),
        string(name: 'CLIENT_VERSION', value: PMM_VERSION),
        string(name: 'TESTS', value: 'pmm2-client'),
        string(name: 'INSTALL_REPO', value: REPO),
        string(name: 'PMM_VERSION', value: PMM_VERSION)
    ]
}

void runUpgradeTest(String FROM_VERSION, String CURRENT_VERSION) {
    build job: 'pmm2-upgrade-tests', propagate: false, parameters: [
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
            defaultValue: '2.0.0',
            description: 'PMM2 Server version',
            name: 'VERSION')
    }
    stages {
        stage('Tests Execution') {
            parallel {
                stage('Test: Upgrade from 2.24.0 version') {
                    steps {
                        runUpgradeTest('2.24.0', VERSION)
                    }
                }
                stage('Test: Upgrade from 2.25.0 version') {
                    steps {
                        runUpgradeTest('2.25.0', VERSION)
                    }
                }
                stage('Test: Upgrade from 2.26.0 version') {
                    steps {
                        runUpgradeTest('2.26.0', VERSION)
                    }
                }
                stage('Test: Upgrade from 2.28.0 version') {
                    steps {
                        runUpgradeTest('2.28.0', VERSION)
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
                stage('Test: Upgrade from pmm2-client-main repo') {
                    steps {
                        runPackageTest(VERSION, 'pmm2-client-main')
                    }
                }
            }
        }
    }
    post {
        success {
            slackSend botUser: true,
                      channel: '#pmm-ci',
                      color: '#00FF00',
                      message: "PMM release tests for PMM ${VERSION} succeeded"
        }
        failure {
            slackSend botUser: true,
                      channel: '#pmm-ci',
                      color: '#FF0000',
                      message: "PMM release tests for PMM ${VERSION} failed"
        }
    }
}
