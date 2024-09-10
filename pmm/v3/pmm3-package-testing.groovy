library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runStaging(String DOCKER_VERSION, CLIENTS) {
    stagingJob = build job: 'aws-staging-start-pmm3', parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_VERSION),
        string(name: 'CLIENT_VERSION', value: 'pmm-latest'),
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

void destroyStaging(IP) {
    build job: 'aws-staging-stop', parameters: [
        string(name: 'VM', value: IP),
    ]
}

void setup_rhel_package_tests()
{
    sh '''
        sudo yum install -y epel-release
        sudo yum -y update
        sudo yum install -y ansible git wget
    '''
}

void setup_debian_package_tests()
{
    sh '''
        sudo apt-get install -y dirmngr gnupg2
        echo "deb http://ppa.launchpad.net/ansible/ansible/ubuntu trusty main" | sudo tee -a /etc/apt/sources.list > /dev/null
        sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 93C4A3FD7BB9C367
        sudo apt update -y
        sudo apt-get install -y ansible git wget
    '''
}

void setup_ubuntu_package_tests()
{
    sh '''
        sudo apt update -y
        sudo apt install -y software-properties-common
        sudo apt-add-repository --yes --update ppa:ansible/ansible
        sudo apt-get install -y ansible git wget
    '''
}

void run_package_tests(String GIT_BRANCH, String TESTS, String INSTALL_REPO)
{
    deleteDir()
    git poll: false, branch: GIT_BRANCH, url: 'https://github.com/Percona-QA/package-testing'
    sh '''
        export install_repo=\${INSTALL_REPO}
        git clone https://github.com/Percona-QA/ppg-testing
        ansible-playbook \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 playbooks/\${TESTS}.yml
    '''
}

def latestVersion = pmmVersion()

pipeline {
    agent {
        label 'agent-amd64-ol9'
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
        string(
            defaultValue: 'pmm-client',
            description: 'Name of Playbook? ex: pmm-client_integration, pmm-client_integration_custom_path',
            name: 'TESTS',
            trim: true)
        choice(
            choices: ['experimental', 'testing', 'main', 'pmm-client-main'],
            description: 'Enable Repo for Client Nodes',
            name: 'INSTALL_REPO')
        choice(
            choices: ['auto', 'push', 'pull'],
            description: 'Select the Metrics Mode for Client',
            name: 'METRICS_MODE')
    }
    options {
        skipDefaultCheckout()
    }
    stages {
        stage('Setup Server Instance') {
            steps {
                runStaging(DOCKER_VERSION, '--addclient=ps,1')
            }
        }
        stage('Execute Package Tests') {
            parallel {
                stage('ol-8-x64') {
                    agent {
                        label 'min-ol-8-x64'
                    }
                    steps{
                        setup_rhel_package_tests()
                        run_package_tests(GIT_BRANCH, TESTS, INSTALL_REPO)
                    }
                    post {
                        always {
                            deleteDir()
                        }
                    }
                }
                stage('ol-9-x64') {
                    when {
                        expression { env.TESTS == "pmm-client" || env.TESTS == "pmm-client_upgrade" }
                    }
                    agent {
                        label 'min-ol-9-x64'
                    }
                    steps{
                        setup_rhel_package_tests()
                        run_package_tests(GIT_BRANCH, TESTS, INSTALL_REPO)
                    }
                    post {
                        always {
                            deleteDir()
                        }
                    }
                }
                stage('focal-x64') {
                    agent {
                        label 'min-focal-x64'
                    }
                    steps{
                        setup_ubuntu_package_tests()
                        run_package_tests(GIT_BRANCH, TESTS, INSTALL_REPO)
                    }
                    post {
                        always {
                            deleteDir()
                        }
                    }
                }
                stage('jammy-x64') {
                    agent {
                        label 'min-jammy-x64'
                    }
                    when {
                        expression { env.TESTS == "pmm-client" }
                    }
                    steps{
                        setup_ubuntu_package_tests()
                        run_package_tests(GIT_BRANCH, TESTS, INSTALL_REPO)
                    }
                    post {
                        always {
                            deleteDir()
                        }
                    }
                }
                stage('buster-x64') {
                    agent {
                        label 'min-buster-x64'
                    }
                    steps{
                        setup_debian_package_tests()
                        run_package_tests(GIT_BRANCH, TESTS, INSTALL_REPO)
                    }
                    post {
                        always {
                            deleteDir()
                        }
                    }
                }
                stage('bullseye-x64') {
                    when {
                        expression { env.TESTS == "pmm-client" || env.TESTS == "pmm-client_upgrade" }
                    }
                    agent {
                        label 'min-bullseye-x64'
                    }
                    steps {
                        setup_debian_package_tests()
                        run_package_tests(GIT_BRANCH, TESTS, INSTALL_REPO)
                    }
                    post {
                        always {
                            deleteDir()
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            sh '''
                curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
            '''
            script {
                if(env.VM_NAME)
                {
                    archiveArtifacts artifacts: 'logs.zip'
                    destroyStaging(VM_NAME)
                }
                if (currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                }
            }
        }
    }
}
