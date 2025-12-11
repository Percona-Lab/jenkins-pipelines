library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void run_package_tests(String GIT_BRANCH, String PLAYBOOK, String INSTALL_REPO)
{
    deleteDir()
    git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-QA/package-testing'
    sh '''
        export install_repo=\${INSTALL_REPO}
        export TARBALL_LINK=\${TARBALL}
        ls playbooks
        ansible-playbook \
        -vvv \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 playbooks/${PLAYBOOK}.yml
    '''
}

void runStaging(String DOCKER_VERSION, CLIENTS) {
    stagingJob = build job: 'pmm3-aws-staging-start', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: '3-dev-latest'),
        string(name: 'DOCKER_ENV_VARIABLE', value: '-e PMM_ENABLE_TELEMETRY=false -e PMM_DATA_RETENTION=48h -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com:443 -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX'),
        string(name: 'CLIENTS', value: CLIENTS),
        string(name: 'NOTIFY', value: 'false'),
        string(name: 'DAYS', value: '1')
    ]
    env.VM_IP = stagingJob.buildVariables.IP
    env.PMM_SERVER_IP = stagingJob.buildVariables.IP
    env.VM_NAME = stagingJob.buildVariables.VM_NAME
    env.ADMIN_PASSWORD = stagingJob.buildVariables.ADMIN_PASSWORD
    env.PMM_URL = "http://admin:${ADMIN_PASSWORD}@${VM_IP}"
}

void runPackageTest(String GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, TESTS, INSTALL_REPO, TARBALL, METRICS_MODE, CLIENTS) {
    packageTestJob = build job: 'pmm3-package-testing', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'PMM_VERSION', value: PMM_VERSION),
        string(name: 'TESTS', value: TESTS),
        string(name: 'INSTALL_REPO', value: INSTALL_REPO),
        string(name: 'TARBALL', value: TARBALL),
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
            description: 'PMM Client tarball link or FB-code',
            name: 'TARBALL')
        choice(
            choices: ['auto', 'push', 'pull'],
            description: 'Select the Metrics Mode for Client',
            name: 'METRICS_MODE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Run package tests') {
            parallel {
                stage('Run \"pmm3-client\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client", INSTALL_REPO, TARBALL, METRICS_MODE, '--help')
                        }
                    }
                }
                stage('Run \"pmm3-client_custom_path\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_custom_path", INSTALL_REPO, TARBALL, METRICS_MODE, '--help ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration", INSTALL_REPO, TARBALL, METRICS_MODE, '--help  ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_auth_config\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_auth_config", INSTALL_REPO, TARBALL, METRICS_MODE, '--help   ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_auth_register\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_auth_register", INSTALL_REPO, TARBALL, METRICS_MODE, ' --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_custom_path\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_custom_path", INSTALL_REPO, TARBALL, METRICS_MODE, '  --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_custom_port\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_custom_port", INSTALL_REPO, TARBALL, METRICS_MODE, '   --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_upgrade\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_upgrade", INSTALL_REPO, TARBALL, METRICS_MODE, '    --help')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_upgrade_custom_path\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_upgrade_custom_path", INSTALL_REPO, TARBALL, METRICS_MODE, '    --help ')
                        }
                    }
                }
                stage('Run \"pmm3-client_integration_upgrade_custom_port\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_integration_upgrade_custom_port", INSTALL_REPO, TARBALL, METRICS_MODE, '    --help  ')
                        }
                    }
                }
                stage('Run \"pmm3-client_upgrade\" package tests') {
                    steps {
                        retry(2) {
                            runPackageTest(GIT_BRANCH, DOCKER_VERSION, PMM_VERSION, "pmm3-client_upgrade", INSTALL_REPO, TARBALL, METRICS_MODE, '    --help   ')
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
