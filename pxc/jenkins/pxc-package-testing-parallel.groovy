library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List all_nodes = [
                'ubuntu-jammy',
                'ubuntu-focal',
                'ubuntu-bionic',
                'debian-11',
                'debian-10',
                'centos-7',
                'ol-8',
                'ol-9',
                'min-amazon-2'
]

product_to_test = params.product_to_test

List nodes_to_test = []

nodes_to_test = all_nodes

void runNodeBuild(String node_to_test) {

    if("${params.test_repo}" == "main"){

        test_type = "install"
        echo "${test_type}"

    }
    else{

        test_type = "install_and_upgrade"
        echo "${test_type}"
    }

    build(
        job: 'wip-pxc-package-testing',
        parameters: [
            string(name: "product_to_test", value: params.product_to_test),
            string(name: "node_to_test", value: node_to_test),
            string(name: "test_repo", value: params.test_repo),
            string(name: "test_type", value: "${test_type}"),
            string(name: "pxc57_repo", value: params.pxc57_repo)            
        ],
        propagate: true,
        wait: true
    )
}

pipeline {
    agent {
        label 'docker'
    }

    parameters {
        choice(
            name: 'product_to_test',
            choices: [
                'pxc80',
                'pxc57'
            ],
            description: 'PXC product_to_test to test'
        )

        choice(
	        name: 'test_repo',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )

        choice(
            name: "pxc57_repo",
            choices: ["original","pxc57" ],
            description: "PXC-5.7 packages are located in 2 repos: pxc-57 and original and both should be tested. Choose which repo to use for test."
        )

    }

    stages {
        stage("Prepare") {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${product_to_test}-${params.test_repo}-all"
                    currentBuild.description = "action: ${params.action_to_test} node: ${params.node_to_test}"
                }
            }
        }

        stage("Run parallel") {
            parallel {
                stage("Debian-10") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("debian-10")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("debian-10")
                    }
                }

                stage("Debian-11") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("debian-11")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("debian-11")
                    }
                }

                stage("Centos 7") {
                    when {
                        expression {
                            allOf{                            
                                nodes_to_test.contains("centos-7")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("centos-7")
                    }
                }

                stage("ol-8") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("ol-8")

                            }
                        }
                    }
                    steps {
                        runNodeBuild("ol-8")
                    }
                }

                stage("ol-9") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("ol-9")
                            
                            }
                        }
                    }

                    steps {
                        runNodeBuild("ol-9")
                    }
                }


                stage("ubuntu-jammy") {
                    when {
                        expression {
                            allOf{                            
                                nodes_to_test.contains("ubuntu-jammy")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-jammy")
                    }
                }

                stage("ubuntu-bionic") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("ubuntu-bionic")
                            
                            }
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-bionic")
                    }
                }

                stage("ubuntu-focal") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("ubuntu-focal")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-focal")
                    }
                }

	            stage("min-amazon-2") {	
                    when {	
                        expression {	
                            allOf{
                                nodes_to_test.contains("min-amazon-2")	

                            }
                        }	
                    }	
                    steps {	
                        runNodeBuild("min-amazon-2")	
                    }	
                }
            }
        }
    }
}
