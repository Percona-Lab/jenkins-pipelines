library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runPackageTest(String GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, TESTS, INSTALL_REPO, TARBALL, TARBALL_ARM, METRICS_MODE, CLIENTS) {
    packageTestJob = build job: 'pmm3-package-testing-arm', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'PMM_VERSION', value: PMM_VERSION),
        string(name: 'TESTS', value: TESTS),
        string(name: 'INSTALL_REPO', value: INSTALL_REPO),
        string(name: 'TARBALL', value: TARBALL),
        string(name: 'TARBALL_ARM', value: TARBALL_ARM),
        string(name: 'METRICS_MODE', value: METRICS_MODE),
        string(name: 'CLIENTS', value: CLIENTS)
    ]
}

def latestVersion = pmmVersion('v3')[0]

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for package-testing repository',
            name: 'GIT_BRANCH',
            trim: true)
        string(
            defaultValue: '',
            description: 'Commit hash for the branch',
            name: 'GIT_COMMIT_HASH',
            trim: true)
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION',
            trim: true)
        string(
            defaultValue: latestVersion,
            description: 'PMM Version for testing',
            name: 'PMM_VERSION',
            trim: true)
        choice(
            choices: ['experimental', 'testing', 'main', 'pmm-client-main'],
            description: 'Enable Repo for Client Nodes',
            name: 'INSTALL_REPO')
        string(
            defaultValue: '',
            description: 'PMM Client (X64) tarball link or FB-code',
            name: 'TARBALL')
        string(
            defaultValue: '',
            description: 'PMM Client (ARM64) tarball link or FB-code',
            name: 'TARBALL_ARM')
        choice(
            choices: ['auto', 'push', 'pull'],
            description: 'Select the Metrics Mode for Client',
            name: 'METRICS_MODE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
                timeout(time: 120, unit: 'MINUTES')
    }
    stages {
        stage('Run package tests') {
            parallel {
                stage('Run \"pmm3-client\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '--help')
                        }
                    }
                }
                stage('Run \"pmm3-client_custom_path\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_custom_path", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '--help ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '--help  ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_auth_config\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_auth_config", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '--help   ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_auth_register\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_auth_register", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, ' --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_custom_path\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_custom_path", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '  --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_custom_port\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_custom_port", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '   --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_upgrade\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_upgrade", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '    --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_upgrade_custom_path\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_upgrade_custom_path", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '    --help ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_upgrade_custom_port\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_upgrade_custom_port", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '    --help  ')
                        }
                    }
                }
                stage('Run \"pmm3-client_upgrade\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_upgrade", INSTALL_REPO, TARBALL_ARM, METRICS_MODE, '    --help   ')
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}
