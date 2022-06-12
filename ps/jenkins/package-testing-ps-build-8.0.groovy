library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

setup_rhel_package_tests = { ->
    sh '''
        sudo yum install -y epel-release
        sudo yum -y update
        sudo yum install -y ansible git wget tar
    '''
}

setup_amazon_package_tests = { ->
    sh '''
        sudo amazon-linux-extras install epel
        sudo yum -y update
        sudo yum install -y ansible git wget
    '''
}

setup_stretch_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y dirmngr gnupg2
        echo "deb http://ppa.launchpad.net/ansible/ansible/ubuntu trusty main" | sudo tee -a /etc/apt/sources.list > /dev/null
        sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 93C4A3FD7BB9C367
        sudo apt-get update
        sudo apt-get install -y ansible git wget
    '''
}

setup_debian_package_tests = { ->
    sh '''
        sudo apt-get update
        sudo apt-get install -y ansible git wget
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
    "min-buster-x64": setup_debian_package_tests,
    "min-bullseye-x64": setup_debian_package_tests,
    "min-ol-8-x64": setup_rhel_package_tests,
    "min-centos-7-x64": setup_rhel_package_tests,
    "min-bionic-x64": setup_ubuntu_package_tests,
    "min-focal-x64": setup_ubuntu_package_tests,
    "min-amazon-2-x64": setup_amazon_package_tests,
]

void setup_package_tests() {
    node_setups[params.node_to_test]()
}

List all_nodes = node_setups.keySet().collect()

List all_actions = [
    "install",
    "upgrade",
    "maj-upgrade-to",
]

product_action_playbooks = [
    ps80: [
        install: "ps_80.yml",
        upgrade: "ps_80_upgrade.yml",
        "maj-upgrade-to": "ps_80_major_upgrade_to.yml",
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
        git clone --depth 1 https://github.com/Percona-QA/package-testing
    '''

    sh """
        export install_repo="\${install_repo}"
        export client_to_test="ps80"
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
            choices: ["ps80", "client_test"],
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
            }
        }
    }
}
