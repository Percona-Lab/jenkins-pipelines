library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runNodeBuild(String node_to_test) {
    build(
        job: 'proxysql-package-testing',
        parameters: [
            string(name: 'product_to_test', value: product_to_test),
            string(name: 'install_repo', value: params.install_repo),
            string(name: 'node_to_test', value: node_to_test),
            string(name: 'git_repo', value: params.git_repo),
            string(name: 'client_to_test', value: params.client_to_test),
            string(name: 'repo_for_client_to_test', value: params.repo_for_client_to_test)
        ],
        propagate: true,
        wait: true
    )
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
        stage('Run parallel') {
            parallel {
                stage('Debian Stretch') {
                    steps {
                        runNodeBuild('min-stretch-x64')
                    }
                }

                stage('Debian Buster') {
                    steps {
                        runNodeBuild('min-buster-x64')
                    }
                }

                stage('Debian Bullseye') {
                    steps {
                        script{
                            if (env.product_to_test == 'proxysql') {
                                echo 'Proxysql is not available for Debian Bullseye'
                            } else {
                                runNodeBuild('min-bullseye-x64')
                            }
                        }
                    }
                }

                stage('Ubuntu Bionic') {
                    steps {
                        runNodeBuild('min-bionic-x64')
                    }
                }

                stage('Ubuntu Focal') {
                    steps {
                        script{
                            if (env.product_to_test == 'proxysql') {
                                echo 'Proxysql is not available for Ubuntu Focal'
                            } else {
                                runNodeBuild('min-focal-x64')
                            }
                        }
                    }
                }

                stage('Centos 7') {
                    steps {
                        runNodeBuild('min-centos-7-x64')
                    }
                }

                stage('Oracle Linux 8') {
                    steps {
                        script{
                            if (env.product_to_test == 'proxysql') {
                                echo 'Proxysql is not available for Oracle Linux 8'
                            } else {
                                runNodeBuild('min-ol-8-x64')
                            }
                        }
                    }
                }
            }
        }
    }
}
