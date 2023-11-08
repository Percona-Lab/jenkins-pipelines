library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runNodeBuild(String node_to_test) {
    build(
        job: 'pxb-package-testing',
        parameters: [
            string(name: 'product_to_test', value: product_to_test),
            string(name: 'install_repo', value: params.install_repo),
            string(name: 'node_to_test', value: node_to_test),
            string(name: 'git_repo', value: params.git_repo)
        ],
        propagate: true,
        wait: true
    )
}

pipeline {
    agent none

    parameters {
        choice(
            choices: ['pxb81', 'pxb80', 'pxb24'],
            description: 'Choose the product version to test: PXB8.1, PXB8.0 OR PXB2.4',
            name: 'product_to_test'
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
        stage('Run parallel') {
            parallel {
                stage('Debian Buster') {
                    steps {
                        runNodeBuild('min-buster-x64')
                    }
                }

                stage('Debian Bullseye') {
                    steps {
                        runNodeBuild('min-bullseye-x64')
                    }
                }

                stage('Debian Bookworm') {
                    steps {
                        runNodeBuild('min-bookworm-x64')
                    }
                }

                stage('Ubuntu Bionic') {
                    steps {
                        runNodeBuild('min-bionic-x64')
                    }
                }

                stage('Ubuntu Focal') {
                    steps {
                        runNodeBuild('min-focal-x64')
                    }
                }

                stage('Ubuntu Jammy') {
                    steps {
                        runNodeBuild('min-jammy-x64')
                    }
                }

                stage('Centos 7') {
                    steps {
                        runNodeBuild('min-centos-7-x64')
                    }
                }

                stage('Oracle Centos 8') {
                    steps {
                        runNodeBuild('min-ol-8-x64')
                    }
                }

                stage('Oracle Centos 9') {
                    steps {
                        runNodeBuild('min-ol-9-x64')
                    }
                }

            }
        }
    }
}
