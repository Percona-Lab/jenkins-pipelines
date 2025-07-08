library changelog: false, identifier: 'lib@yum-to-dnf-mod-1', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List all_nodes = [
                'ubuntu-noble',
                'ubuntu-jammy',
                'ubuntu-noble-arm',
                'ubuntu-jammy-arm',
                'ubuntu-focal',
                'debian-12',
                'debian-11',
                'debian-12-arm',
                'debian-11-arm',
                'debian-10',
                'centos-7',
                'ol-8',
                'ol-9',
                'rhel-8',
                'rhel-9',
                'rhel-8-arm',
                'rhel-9-arm'
]

product_to_test = params.product_to_test

List nodes_to_test = []
def job_to_run = ""

nodes_to_test = all_nodes

void runNodeBuild(String node_to_test) {

    if("${params.test_repo}" == "main"){
        test_type = "install"
        echo "${test_type}"
    }
    else{
    //    test_type = "install_and_upgrade"
        echo "${test_type}"
    }


    if (pro_repo == "yes") {
        echo "Testing PRO packages"
        job = "pxc-package-testing-pro-test"
        job_to_run = "${job}"

        test_type = params.test_type_pro
        build(
            job: "${job}",
            parameters: [
                string(name: "product_to_test", value: params.product_to_test),
                string(name: "node_to_test", value: node_to_test),
                string(name: "test_repo", value: params.test_repo),
                string(name: "test_type", value: "${test_type}")
            ],
            propagate: true,
            wait: true
        )


    } else {
        echo "Testing Community packages"
        job = "pxc-package-testing-test"
        job_to_run = "${job}"

        test_type = params.test_type
        build(
            job: "${job}",
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







}

pipeline {
    agent {
        label 'docker'
    }

    parameters {
        choice(
            name: 'product_to_test',
            choices: [
                'pxc84',
                'pxc80',
                'pxc57',
                'pxc-innovation-lts'
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
            choices: ["original","pxc57","EOL"],
            description: "PXC-5.7 packages are located in 2 repos: pxc-57 and original and both should be tested. Choose which repo to use for test."
        )

        choice(
            name: 'test_type',
            choices: [
                'install',
                'install_and_upgrade',
                'min_upgrade',
                'maj_upgrade'
            ],
            description: 'Set test type for testing'
        )

        choice(
            name: 'test_type_pro',
            choices: [
                'install',
                'install_and_upgrade',
                'min_upgrade_pro_pro',
                'min_upgrade_nonpro_pro',
                'min_upgrade_pro_nonpro',
            ],
            description: 'Set test type for testing PRO packages'
        )

        choice(
            name: 'pro_repo',
            choices: [
                'yes',
                'no'
            ],
            description: 'Set if PRO packages should be tested or not'
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

                stage("Debian-12") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("debian-12")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("debian-12")
                    }
                }

                stage("Debian-11-arm") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("debian-11-arm")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("debian-11-arm")
                    }
                }

                stage("Debian-12-arm") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("debian-12-arm")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("debian-12-arm")
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

                stage("rhel-8") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("rhel-8")

                            }
                        }
                    }
                    steps {
                        runNodeBuild("rhel-8")
                    }
                }

                stage("rhel-9") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("rhel-9")
                            
                            }
                        }
                    }

                    steps {
                        runNodeBuild("rhel-9")
                    }
                }

                stage("rhel-8-arm") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("rhel-8-arm")

                            }
                        }
                    }
                    steps {
                        runNodeBuild("rhel-8-arm")
                    }
                }

                stage("rhel-9-arm") {
                    when {
                        expression {
                            allOf{
                                nodes_to_test.contains("rhel-9-arm")
                            
                            }
                        }
                    }

                    steps {
                        runNodeBuild("rhel-9-arm")
                    }
                }

                stage("ubuntu-noble") {
                    when {
                        expression {
                            allOf{                            
                                nodes_to_test.contains("ubuntu-noble")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-noble")
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

                stage("ubuntu-noble-arm") {
                    when {
                        expression {
                            allOf{                            
                                nodes_to_test.contains("ubuntu-noble-arm")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-noble-arm")
                    }
                }

                stage("ubuntu-jammy-arm") {
                    when {
                        expression {
                            allOf{                            
                                nodes_to_test.contains("ubuntu-jammy-arm")

                            }
                        }
                    }

                    steps {
                        runNodeBuild("ubuntu-jammy-arm")
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

            }
        }

    }

    post {
        always {
            script {
                currentBuild.description = "action: ${params.action_to_test} node: ${params.node_to_test}"
                    echo "All tests completed"

                        def awsCredentials = [
                                sshUserPrivateKey(
                                    credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
                                    keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
                                    passphraseVariable: '',
                                    usernameVariable: ''
                                ),
                                aws(
                                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                    credentialsId: '7e252458-7ef8-4d0e-a4d5-5773edcbfa5e',
                                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                                )
                            ]

                        withCredentials(awsCredentials) {

                        def jobName = "${job_to_run}"
                        echo "Listing EC2 instances with JobName tag: ${jobName}"
                        sh """
                        aws ec2 describe-instances --filters "Name=tag:JobName,Values=${jobName}" --query "Reservations[].Instances[].InstanceId" --output text
                        """
                        echo "Deleting EC2 instances with JobName tag: ${jobName}"
                        sh """
                        aws ec2 describe-instances --filters "Name=tag:JobName,Values=${jobName}" --query "Reservations[].Instances[].InstanceId" --output text | xargs -r aws ec2 terminate-instances --instance-ids
                        """
                    }
                }

            }
        }
    }


