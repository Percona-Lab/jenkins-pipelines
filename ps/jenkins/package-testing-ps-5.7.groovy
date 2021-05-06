library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List all_nodes = [
    "min-stretch-x64",
    "min-buster-x64",
    "min-centos-6-x64",
    "min-centos-7-x64",
    "min-centos-8-x64",
    "min-xenial-x64",
    "min-bionic-x64",
    "min-focal-x64",
    "micro-amazon",
]

Map product_nodes = [
    ps56: [
        "min-stretch-x64",
        "min-centos-6-x64",
        "min-centos-7-x64",
        "min-xenial-x64",
        "min-bionic-x64",
        "micro-amazon",
    ],
    ps57: all_nodes,
    ps80: all_nodes,
    client_test: all_nodes,
]

product_to_test = params.product_to_test

List nodes_to_test = []
if (params.node_to_test == "all") {
    nodes_to_test = all_nodes
} else {
    nodes_to_test = [params.node_to_test]
}

void runNodeBuild(String node_to_test) {
    build(
        job: 'package-testing-ps57-build',
        parameters: [
            string(name: "product_to_test", value: product_to_test),
            string(name: "install_repo", value: params.install_repo),
            string(name: "client_to_test", value: params.client_to_test),
            string(name: "node_to_test", value: node_to_test),
            string(name: "action_to_test", value: params.action_to_test)
        ],
        propagate: true,
        wait: true
    )
}

pipeline {
    agent none

    parameters {
        choice(
            name: "product_to_test",
            choices: ["ps57", "ps56", "client_test"],
            description: "Product for which the packages will be tested"
        )

        choice(
            name: "install_repo",
            choices: ["testing", "main", "experimental"],
            description: "Repo to use in install test"
        )

        choice(
            name: "client_to_test",
            choices: ["ps57", "ps56"],
            description: "Client to check (only when client_test is selected)"
        )

        choice(
            name: "node_to_test",
            choices: ["all"] + all_nodes,
            description: "Node in which to test the product"
        )

        choice(
            name: "action_to_test",
            choices: ["all", "install", "upgrade", "maj-upgrade-to", "maj-upgrade-from"],
            description: "Action to test on the product"
        )
    }

    stages {
        stage("Prepare") {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${product_to_test}-${params.install_repo}"
                    currentBuild.description = "action: ${params.action_to_test} node: ${params.node_to_test}"
                }
            }
        }

        stage("Run parallel") {
            parallel {
                stage("Debian Stretch") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-stretch-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-stretch-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-stretch-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-stretch-x64")
                    }
                }

                stage("Debian Buster") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-buster-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-buster-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-buster-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-buster-x64")
                    }
                }

                stage("Centos 6") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-centos-6-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-centos-6-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-centos-6-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-centos-6-x64")
                    }
                }

                stage("Centos 7") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-centos-7-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-centos-7-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-centos-7-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-centos-7-x64")
                    }
                }

                stage("Centos 8") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-centos-8-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-centos-8-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-centos-8-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-centos-8-x64")
                    }
                }

                stage("Ubuntu Xenial") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-xenial-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-xenial-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-xenial-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-xenial-x64")
                    }
                }

                stage("Ubuntu Bionic") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-bionic-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-bionic-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-bionic-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-bionic-x64")
                    }
                }

                stage("Ubuntu Focal") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("min-focal-x64")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("min-focal-x64"))
                        ) }
                        expression {
                            nodes_to_test.contains("min-focal-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-focal-x64")
                    }
                }

                stage("Amazon Linux") {
                    when {
                        expression {
                            product_nodes[product_to_test].contains("micro-amazon")
                        }
                        expression { (
                            (product_to_test != "client_test") ||
                            (product_nodes[client_to_test].contains("micro-amazon"))
                        ) }
                        expression {
                            nodes_to_test.contains("micro-amazon")
                        }
                    }

                    steps {
                        runNodeBuild("micro-amazon")
                    }
                }
            }
        }
    }
}
