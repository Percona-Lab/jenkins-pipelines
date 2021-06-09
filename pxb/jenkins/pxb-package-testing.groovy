library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

product_action_playbooks = [
    pxb80: [
        install: 'pxb_80.yml',
        upgrade: 'pxb_80_upgrade.yml',
        upstream: 'pxb_80_upstream.yml',
        tarball: 'pxb_80_tarball.yml'
    ],
    pxb24: [
        install: 'pxb_24.yml',
        upgrade: 'pxb_24_upgrade.yml',
        upstream: 'pxb_24_upstream.yml',
        tarball: 'pxb_24_tarball.yml'
    ]
]

void runPlaybook(String action_to_test) {
    def playbook = product_action_playbooks[params.product_to_test][action_to_test]
    def playbook_path = "package-testing/playbooks/${playbook}"

    sh '''
        git clone --depth 1 "${git_repo}"

        if [ -f /usr/bin/yum ]; then
            sudo yum -y update
            sudo yum -y install epel-release
            sudo yum -y install ansible
        fi

        if [ -f /usr/bin/apt-get ]; then
            sudo DEBIAN_FRONTEND=noninteractive apt-get update
            sudo DEBIAN_FRONTEND=noninteractive apt-get -y install python
            curl https://bootstrap.pypa.io/pip/2.7/get-pip.py -o get-pip.py
            sudo python get-pip.py
            sudo python -m pip install ansible
        fi
    '''

    sh """
        export install_repo="\${install_repo}"

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
            choices: ['pxb80', 'pxb24'],
            description: 'Choose the product version to test: PXB8.0 OR PXB2.4',
            name: 'product_to_test'
        )
        choice(
            choices: [
                'min-centos-7-x64',
                'min-centos-8-x64',
                'min-xenial-x64',
                'min-bionic-x64',
                'min-focal-x64',
                'min-stretch-x64',
                'min-buster-x64'
            ],
            description: 'Node in which to test the product',
            name: 'node_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo from which to install packages and run the tests',
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
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.product_to_test}-${params.install_repo}-${params.node_to_test}"
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

                stage('Upstream') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        runPlaybook("upstream")
                    }
                }

                stage('Tarball') {
                    agent {
                        label params.node_to_test
                    }

                    steps {
                        runPlaybook("tarball")
                    }
                }
            }
        }
    }
}
