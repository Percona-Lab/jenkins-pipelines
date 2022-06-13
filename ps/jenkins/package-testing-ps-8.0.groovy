library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List all_nodes = [
    "min-buster-x64",
    "min-bullseye-x64",
    "min-centos-7-x64",
    "min-ol-8-x64",
    "min-bionic-x64",
    "min-focal-x64",
    "min-amazon-2-x64",
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
        job: 'package-testing-ps80-build',
        parameters: [
            string(name: "product_to_test", value: product_to_test),
            string(name: "install_repo", value: params.install_repo),
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
            choices: ["all"] + all_nodes,
            description: "Node in which to test the product"
        )

        choice(
            name: "action_to_test",
            choices: ["all", "install", "upgrade", "maj-upgrade-to"],
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
                stage("Debian Buster") {
                    when {
                        expression {
                            nodes_to_test.contains("min-buster-x64")
                        }
                    }

                    steps {
                        runNodeBuild("min-buster-x64")
                    }
                }

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

                stage("Ubuntu Bionic") {
                    when {
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
            }
        }
    }
}
