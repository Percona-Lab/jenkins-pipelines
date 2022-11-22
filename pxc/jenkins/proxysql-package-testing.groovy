library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

product_action_playbooks = [
    proxysql: [
        install: 'proxysql.yml',
        upgrade: 'proxysql_upgrade.yml',
    ],
    proxysql2: [
        install: 'proxysql2.yml',
        upgrade: 'proxysql2_upgrade.yml',
    ]
]

setup_centos_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum -y update
        sudo yum install -y ansible
    '''
}

setup_stretch_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y dirmngr gnupg2
        echo "deb http://ppa.launchpad.net/ansible/ansible/ubuntu trusty main" | sudo tee -a /etc/apt/sources.list > /dev/null
        sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 93C4A3FD7BB9C367
        sudo apt-get update
        sudo apt-get install -y ansible
    '''
}

setup_buster_bullseye_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y ansible
    '''
}

setup_ubuntu_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y software-properties-common
        sudo apt-add-repository --yes --update ppa:ansible/ansible
        sudo apt-get install -y ansible
    '''
}

node_setups = [
    "min-stretch-x64": setup_stretch_package_tests,
    "min-buster-x64": setup_buster_bullseye_package_tests,
    "min-bullseye-x64": setup_buster_bullseye_package_tests,
    "min-centos-7-x64": setup_centos_package_tests,
    "min-ol-8-x64": setup_centos_package_tests,
    "min-bionic-x64": setup_ubuntu_package_tests,
    "min-focal-x64": setup_ubuntu_package_tests,
]

void setup_package_tests() {
    node_setups[params.node_to_test]()
}

void runPlaybook(String action_to_test) {
    def playbook = product_action_playbooks[params.product_to_test][action_to_test]
    def playbook_path = "package-testing/playbooks/${playbook}"

    sh '''
        git clone --depth 1 "${git_repo}"
    '''

    setup_package_tests()

    sh """
        export install_repo="\${install_repo}"
        export client_to_test="\${client_to_test}"
        export repo_for_client_to_test="\${repo_for_client_to_test}"

        ansible-playbook \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 \
        ${playbook_path}
    """
}


pipeline {
    agent none

    parameters {
        choice(
            choices: ['proxysql', 'proxysql2'],
            description: 'Choose the product version to test: proxysql OR proxysql2',
            name: 'product_to_test'
        )
        choice(
            choices: [
                'min-centos-7-x64',
                'min-ol-8-x64',
                'min-bionic-x64',
                'min-focal-x64',
                'min-stretch-x64',
                'min-buster-x64',
                'min-bullseye-x64'
            ],
            description: 'Node to run tests',
            name: 'node_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install proxysql packages from',
            name: 'install_repo'
        )
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: '',
            name: 'git_repo',
            trim: false
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '15'))
        skipDefaultCheckout()
    }

    stages {
        stage("Prepare") {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.product_to_test}-${params.install_repo}-${params.node_to_test}-${params.client_to_test}-${params.repo_for_client_to_test}"
                }
            }
        }

        stage('Run parallel') {
            parallel {
                stage('Install') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        runPlaybook("install")
                    }
                }

                stage('Upgrade') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        runPlaybook("upgrade")
                    }
                }

            }
        }
    }
}
