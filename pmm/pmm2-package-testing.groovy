library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

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
        sudo apt update
        sudo apt install software-properties-common
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
pipeline {
    agent any
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
            defaultValue: '',
            description: 'IP of the Server Instance for Client to Connect',
            name: 'PMM_SERVER_IP')
        string(
            defaultValue: '2.17.0',
            description: 'PMM Version for testing',
            name: 'PMM_VERSION')
        choice(
            choices: ['pmm2-client', 'pmm2-client_upgrade', 'pmm2-client_integration_upgrade', 'pmm2-client_integration'],
            description: 'Type of Tests?',
            name: 'TESTS')
        choice(
            choices: ['testing', 'experimental', 'main'],
            description: 'Enable Repo?',
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
        stage('Execute Package Tests') {
            parallel {
                stage('centos-7-x64') {
                    agent {
                        label 'min-centos-7-x64'
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
                stage('centos-8-x64') {
                    agent {
                        label 'min-centos-8-x64'
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
                stage('bionic-x64') {
                    agent {
                        label 'min-bionic-x64'
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
                stage('xenial-x64') {
                    agent {
                        label 'min-xenial-x64'
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
                stage('stretch-x64') {
                    agent {
                        label 'min-stretch-x64'
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
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == 'SUCCESS') {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
                }
            }
        }
    }
}