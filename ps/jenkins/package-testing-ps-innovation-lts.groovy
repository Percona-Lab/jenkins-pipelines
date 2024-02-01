library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List all_nodes = [
    "min-bullseye-x64",
    "min-bookworm-x64",
    "min-centos-7-x64",
    "min-ol-8-x64",
    "min-focal-x64",
    "min-amazon-2-x64",
    "min-jammy-x64",
    "min-ol-9-x64",
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
        job: 'package-testing-ps-build-innovation-lts',
        parameters: [
            string(name: "product_to_test", value: product_to_test),
            string(name: "install_repo", value: params.install_repo),
            string(name: "node_to_test", value: node_to_test),
            string(name: "action_to_test", value: params.action_to_test),
            string(name: "check_warnings", value: params.check_warnings),
            string(name: "install_mysql_shell", value: params.install_mysql_shell)         
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
            choices: ["all"] + all_nodes,
            description: "Node in which to test the product"
        )

        choice(
            name: "action_to_test",
            choices: ["all", "install", "upgrade", "maj-upgrade-to", "kmip", "kms"],
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
                    currentBuild.displayName = "#${BUILD_NUMBER}-${product_to_test}-${params.install_repo}"
                    currentBuild.description = "action: ${params.action_to_test} node: ${params.node_to_test}"
                }
            }
        }

        stage("Run parallel") {
            parallel {
                stage("Debian Bullseye") {
                    when {
                        expression {
                            nodes_to_test.contains("min-bullseye-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-bullseye-x64")
                    }
                }

                stage("Debian Bookworm") {
                    when {
                        expression {
                            nodes_to_test.contains("min-bookworm-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-bookworm-x64")
                    }
                }

                stage("Centos 7") {
                    when {
                        expression {
                            nodes_to_test.contains("min-centos-7-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-centos-7-x64")
                    }
                }

                stage("Oracle Linux 8") {
                    when {
                        expression {
                            nodes_to_test.contains("min-ol-8-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-ol-8-x64")
                    }
                }

                stage("Ubuntu Focal") {
                    when {
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
                            nodes_to_test.contains("min-amazon-2-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-amazon-2-x64")
                    }
                }
                stage("Ubuntu Jammy") {
                    when {
                        expression {
                            nodes_to_test.contains("min-jammy-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-jammy-x64")
                    }
                }
                stage("Oracle Linux 9") {
                    when {
                        expression {
                            nodes_to_test.contains("min-ol-9-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-ol-9-x64")
                    }
                }
            }
        }
    }
}
