library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

product_action_playbooks = [
    pt3: [
        install: 'pt.yml',
        upgrade: 'pt_upgrade.yml',
        pt_with_products: 'pt_with_products.yml'
    ]
]

setup_centos_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum -y update
        sudo yum install -y ansible
    '''
}

setup_ol8_package_tests = { ->
    sh '''
        sudo yum install -y https://dl.fedoraproject.org/pub/epel/epel-release-latest-8.noarch.rpm
        sudo yum -y update
        sudo yum install -y ansible
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
    "min-buster-x64": setup_buster_bullseye_package_tests,
    "min-bullseye-x64": setup_buster_bullseye_package_tests,
    "min-centos-7-x64": setup_centos_package_tests,
    "min-ol-8-x64": setup_ol8_package_tests,
    "min-bionic-x64": setup_ubuntu_package_tests,
    "min-focal-x64": setup_ubuntu_package_tests
]

void setup_package_tests() {
    node_setups[params.node_to_test]()
}

void runPlaybook(String action_to_test) {
    def playbook = product_action_playbooks[params.product_to_test][action_to_test]
    def playbook_path = "package-testing/playbooks/${playbook}"

    sh '''
        echo Start: \$(date -R)
        git clone -b "${git_branch}" --depth 1 "${git_repo}"
    '''

    setup_package_tests()

    sh """
        export install_repo="\${install_repo}"

        ansible-playbook \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 \
        ${playbook_path}

        echo Finish: \$(date -R)
    """
}


pipeline {
    agent {
        label 'docker'
    }

    parameters {
        choice(
            choices: ['pt3'],
            description: 'Product version to test',
            name: 'product_to_test'
        )
        choice(
            choices: [
                'min-centos-7-x64',
                'min-ol-8-x64',
                'min-bionic-x64',
                'min-focal-x64',
                'min-buster-x64',
                'min-bullseye-x64'
            ],
            description: 'Node to run tests on',
            name: 'node_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install percona-toolkit packages from',
            name: 'install_repo'
        )
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: '',
            name: 'git_repo',
            trim: false
        )
        string(
            defaultValue: 'master',
            description: '',
            name: 'git_branch',
            trim: false
        )
        booleanParam(
            name: 'skip_ps57',
            description: "Enable to skip ps 5.7 packages installation tests"
        )
        booleanParam(
            name: 'skip_ps80',
            description: "Enable to skip ps 8.0 packages installation tests"
        )
        booleanParam(
            name: 'skip_pxc57',
            description: "Enable to skip pxc 5.7 packages installation tests"
        )
        booleanParam(
            name: 'skip_pxc80',
            description: "Enable to skip pxc 8.0 packages installation tests"
        )
        booleanParam(
            name: 'skip_psmdb44',
            description: "Enable to skip psmdb 4.4 packages installation tests"
        )
        booleanParam(
            name: 'skip_psmdb50',
            description: "Enable to skip psmdb 5.0 packages installation tests"
        )
        booleanParam(
            name: 'skip_upstream57',
            description: "Enable to skip MySQL 5.7 packages installation tests"
        )
        booleanParam(
            name: 'skip_upstream80',
            description: "Enable to skip MySQL 8.0 packages installation tests"
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
                    when {
                        beforeAgent true
                        expression {
                            params.install_repo != 'main'
                        }
                    }
                    steps {
                        runPlaybook("upgrade")
                    }
                }

                stage('ps57_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(bullseye)/) && !params.skip_ps57
                        }
                    }
                    environment {
                        install_with = 'ps57'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('ps80_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !params.skip_ps80
                        }
                    }
                    environment {
                        install_with = 'ps80'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('pxc57_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(bullseye)/) && !params.skip_pxc57
                        }
                    }
                    environment {
                        install_with = 'pxc57'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('pxc80_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !params.skip_pxc80
                        }
                    }
                    environment {
                        install_with = 'pxc80'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('psmdb44_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !params.skip_psmdb44
                        }
                    }
                    environment {
                        install_with = 'psmdb44'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('psmdb50_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !params.skip_psmdb50
                        }
                    }
                    environment {
                        install_with = 'psmdb50'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('upstream57_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !(params.node_to_test =~ /(ol-8|focal|bullseye)/) && !params.skip_upstream57
                        }
                    }
                    environment {
                        install_with = 'upstream57'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }

                stage('upstream80_and_pt') {
                    agent {
                        label params.node_to_test
                    }
                    when {
                        beforeAgent true
                        expression {
                            !params.skip_upstream80
                        }
                    }
                    environment {
                        install_with = 'upstream80'
                    }
                    steps {
                        runPlaybook("pt_with_products")
                    }
                }
            }
        }
    }
}
