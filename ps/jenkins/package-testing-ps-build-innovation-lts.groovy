library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

setup_rhel_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum install -y python3-pip
        sudo yum -y update
        sudo pip3 install ansible==2.10.0
        sudo yum install -y git wget tar
    '''
}

setup_rhel8_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum install -y python3-pip
        sudo yum -y update
        sudo pip3 install ansible==2.10.0
        sudo yum install -y git wget tar
    '''
}

setup_amazon_package_tests = { ->
    sh '''
        sudo amazon-linux-extras install epel
        sudo yum -y update
        sudo yum install -y ansible git wget
    '''
}

setup_debian_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y software-properties-common
        sudo apt-get install -y python3 python3-pip
        echo $PATH
        python3 -m pip install --user ansible==2.9.27
        ~/.local/bin/ansible --version
        sudo ln -s ~/.local/bin/ansible /usr/bin/ansible
        sudo ln -s ~/.local/bin/ansible-playbook /usr/bin/ansible-playbook
    '''
}

setup_debian_bookworm_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y software-properties-common
        sudo apt-get install -y python3 pipx
        pipx install ansible==2.9.27
        pipx ensurepath
        echo $PATH
        sudo ln -s /home/admin/.local/bin/ansible /usr/bin/ansible
        sudo ln -s /home/admin/.local/bin/ansible-playbook /usr/bin/ansible-playbook
    '''
}

setup_ubuntu_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y software-properties-common
        sudo apt-add-repository --yes --update ppa:ansible/ansible
        sudo apt-get install -y ansible git wget
    '''
}

node_setups = [
    "min-bullseye-x64": setup_debian_package_tests,
    "min-bookworm-x64": setup_debian_bookworm_package_tests,
    "min-ol-8-x64": setup_rhel8_package_tests,
    "min-centos-7-x64": setup_rhel_package_tests,
    "min-focal-x64": setup_ubuntu_package_tests,
    "min-amazon-2-x64": setup_amazon_package_tests,
    "min-jammy-x64": setup_ubuntu_package_tests,
    "min-ol-9-x64": setup_rhel_package_tests,
]

void setup_package_tests() {
    node_setups[params.node_to_test]()
}

List all_nodes = node_setups.keySet().collect()

List all_actions = [
    "install",
    "upgrade",
    "maj-upgrade-to",
    "kmip",
    "kms"
]

product_action_playbooks = [
    "innovation-lts": [
        install: "ps_lts_innovation.yml",
        upgrade: "ps_lts_innovation_upgrade.yml",
        "maj-upgrade-to": "ps_lts_innovation_major_upgrade_to.yml",
        kmip: "ps_lts_innovation_kmip.yml",
        kms: "ps_lts_innovation_kms.yml"
    ],
    client_test: [
        install: "client_test.yml",
        upgrade: "client_test_upgrade.yml",
    ]
]

Map product_actions = product_action_playbooks.collectEntries { key, value ->
    [key, value.keySet().collect()]
}

List actions_to_test = []
if (params.action_to_test == "all") {
    actions_to_test = all_actions
} else {
    actions_to_test = [params.action_to_test]
}

void runPlaybook(String action_to_test) {
    def playbook = product_action_playbooks[params.product_to_test][action_to_test]
    def playbook_path = "package-testing/playbooks/${playbook}"

    setup_package_tests()

    sh '''
        git clone -b master --depth 1 https://github.com/Percona-QA/package-testing
    '''

    sh """
        export install_repo="\${install_repo}"
        export client_to_test="innovation-lts"
        export check_warning="\${check_warnings}"
        export install_mysql_shell="\${install_mysql_shell}"
        ansible-playbook \
        --connection=local \
        --inventory 127.0.0.1, \
        --limit 127.0.0.1 \
        ${playbook_path}
    """
}

pipeline {
    agent none

    options {
        skipDefaultCheckout()
    }

    parameters {
        choice(
            name: "product_to_test",
            choices: ["innovation-lts", "client_test"],
            description: "Product for which the packages will be tested"
        )

        choice(
            name: "install_repo",
            choices: ["testing", "main", "experimental"],
            description: "Repo to use in install test"
        )

        choice(
            name: "node_to_test",
            choices: all_nodes,
            description: "Node in which to test the product"
        )

        choice(
            name: "action_to_test",
            choices: ["all"] + all_actions,
            description: "Action to test on the product"
        )

        choice(
            name: "check_warnings",
            choices: ["yes", "no"],
            description: "check warning in client_test"
        )

        choice(
            name: "install_mysql_shell",
            choices: ["yes", "no"],
            description: "install and check mysql-shell for innovation-lts"
        )
        
    }

    stages {
        stage("Prepare") {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.product_to_test}-${params.install_repo}-${params.node_to_test}"
                    currentBuild.description = "action: ${params.action_to_test}"
                }
            }
        }

        stage("Run parallel") {
            parallel {
                stage("Install") {
                    agent {
                        label params.node_to_test
                    }

                    when {
                        beforeAgent true
                        expression {
                            product_actions[params.product_to_test].contains("install")
                        }
                        expression {
                            actions_to_test.contains("install")
                        }
                    }

                    steps {
                        runPlaybook("install")
                    }
                }

                stage("Upgrade") {
                    agent {
                        label params.node_to_test
                    }

                    when {
                        beforeAgent true
                        expression {
                            product_actions[params.product_to_test].contains("upgrade")
                        }
                        expression {
                            actions_to_test.contains("upgrade")
                        }
                    }

                    steps {
                        runPlaybook("upgrade")
                    }
                }

                stage("Major upgrade to") {
                    agent {
                        label params.node_to_test
                    }

                    when {
                        beforeAgent true
                        expression {
                            product_actions[params.product_to_test].contains("maj-upgrade-to")
                        }
                        expression {
                            actions_to_test.contains("maj-upgrade-to")
                        }
                    }

                    steps {
                        runPlaybook("maj-upgrade-to")
                    }
                }

                stage("Kmip") {
                    agent {
                        label params.node_to_test
                    }

                    when {
                        beforeAgent true
                        expression {
                            product_actions[params.product_to_test].contains("kmip")
                        }
                        expression {
                            actions_to_test.contains("kmip")
                        }
                    }

                    steps {
                        runPlaybook("kmip")
                    }
                }

                stage("KMS") {
                    agent {
                        label params.node_to_test
                    }

                    when {
                        beforeAgent true
                        expression {
                            product_actions[params.product_to_test].contains("kms")
                        }
                        expression {
                            actions_to_test.contains("kms")
                        }
                    }

                    steps {
                        runPlaybook("kms")
                    }
                }
            }
        }
    }
}
