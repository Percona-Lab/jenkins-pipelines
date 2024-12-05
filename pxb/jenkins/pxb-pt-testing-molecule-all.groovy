
    library changelog: false, identifier: "lib@master", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
    ])

    pipeline {
    agent {
        label 'min-ol-8-x64'
    }
    environment {
        product_to_test = "${params.product_to_test}"
        node_to_test = "${params.server_to_test}"
        install_repo = "${params.install_repo}"
        server_to_test  = "${params.server_to_test}"
        scenario_to_test = "${params.scenario_to_test}"
    }
    parameters {
        choice(
            choices: ['pxb_80', 'pxb_innovation_lts', 'pxb_84'],
            description: 'Choose the product version to test: PXB8.0, PXB8.4 OR pxb_innovation_lts',
            name: 'product_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install packages and run the tests',
            name: 'install_repo'
        )
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: 'repo name',
            name: 'git_repo',
            trim: false
        )
        choice(
            choices: [
                'ps_innovation_lts',
                'ms_innovation_lts',
                'ps-80',
                'ms-80',
                'ps-84',
                'ms-84'
            ],
            description: 'Server to test',
            name: 'server_to_test'
        )
        choice(
            choices: [
                'all',
                'install',
                'upgrade',
                'upstream',
                'kmip',
                'kms'
            ],
            description: 'Scenario To Test',
            name: 'scenario_to_test'
        )

    }
    options {
        withCredentials(moleculepxbJenkinsCreds())
    }

        stages {
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${server_to_test}-${scenario_to_test}"
                    }
                }
            }

            
            stage("RUN"){
                parallel {

                    stage("install") {
                        steps {
                            script {
                                runpxbptjob("install")
                            }
                        }
                    }

                    stage("upgrade") {
                        steps {
                            script {
                                runpxbptjob("upgrade")
                            }
                        }
                    }

                    stage("kmip") {
                        steps {
                            script {
                                runpxbptjob("kmip")
                            }
                        }
                    }

                    stage("kms") {
                        steps {
                            script {
                                runpxbptjob("kms")
                            }
                        }
                    }
                
                }
                

            }


        }
    }

void runpxbptjob(String scenario_to_test) {
    build(
        job: 'pxb-package-testing-molecule',
        parameters: [
            string(name: "scenario_to_test", value: scenario_to_test),
            string(name: "server_to_test", value: params.server_to_test),
            string(name: "git_repo", value: git_repo),
            string(name: "install_repo", value: params.install_repo),
            string(name: "product_to_test", value: params.product_to_test)
        ],
        propagate: true,
        wait: true
    )
}
