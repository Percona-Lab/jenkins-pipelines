library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runNodeBuild(String node_to_test) {
    build(
        job: 'pt-package-testing',
        parameters: [
            string(name: 'product_to_test', value: product_to_test),
            string(name: 'install_repo', value: params.install_repo),
            string(name: 'node_to_test', value: node_to_test),
            string(name: 'git_repo', value: params.git_repo),
            string(name: 'git_branch', value: params.git_branch),
            booleanParam(name: 'skip_ps57', value: params.skip_ps57),
            booleanParam(name: 'skip_ps80', value: params.skip_ps80),
            booleanParam(name: 'skip_pxc57', value: params.skip_pxc57),
            booleanParam(name: 'skip_pxc80', value: params.skip_pxc80),
            booleanParam(name: 'skip_upstream57', value: params.skip_upstream57),
            booleanParam(name: 'skip_upstream80', value: params.skip_upstream80),
            booleanParam(name: 'skip_psmdb44', value: params.skip_psmdb44),
            booleanParam(name: 'skip_psmdb50', value: params.skip_psmdb50)
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
            choices: ['pt3'],
            description: 'Product version to test',
            name: 'product_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install percona toolkit packages from',
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

                stage('Centos 7') {
                    steps {
                        runNodeBuild('min-centos-7-x64')
                    }
                }

                stage('Oracle Linux 8') {
                    steps {
                        runNodeBuild('min-ol-8-x64')
                    }
                }
            }
        }
    }
}
