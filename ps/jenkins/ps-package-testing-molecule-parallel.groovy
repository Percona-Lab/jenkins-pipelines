
    library changelog: false, identifier: "lib@master", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
    ])

    pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        product_to_test = "${params.product_to_test}"
        git_repo = "${params.git_repo}"
        install_repo = "${params.install_repo}"
        action_to_test  = "${params.action_to_test}"
        check_warnings = "${params.check_warnings}"
        install_mysql_shell = "${params.install_mysql_shell}"
    }
    parameters {
        choice(
            choices: ['ps_80','ps_84','ps_lts_innovation','client_test'],
            description: 'Choose the product version to test: PS8.0 OR ps_lts_innovatoin',
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
        string(
            defaultValue: 'master',
            description: 'Branch name',
            name: 'git_branch',
            trim: false
        )
        choice(
            choices: [
                'yes',
                'no',
            ],
            description: 'check_warnings',
            name: 'check_warnings'
        )
        choice(
            choices: [
                'yes',
                'no'
            ],
            description: 'Install MySQL Shell',
            name: 'install_mysql_shell'
        )
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
    }

        stages {
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}"
                    }
                }
            }

            
            stage("RUN"){
                parallel {

                    stage("install") {
                        steps {
                            script {
                                runpsptjob("install")
                            }
                        }
                    }

                    stage("upgrade") {
                        steps {
                            script {
                                runpsptjob("upgrade")
                            }
                        }
                    }

                    stage("kmip") {
                        steps {
                            script {
                                runpsptjob("kmip")
                            }
                        }
                    }

                    stage("major_upgrade_to") {
                        steps {
                            script {
                                runpsptjob("major_upgrade_to")
                            }
                        }
                    }
                    
                    /*
                    stage("kms") {
                        steps {
                            script {
                                runpsptjob("kms")
                            }
                        }
                    }
                    */
                }
            }
        }
    }

void runpsptjob(String action_to_test) {
    build(
        job: 'ps-package-testing-molecule',
        parameters: [
            string(name: "action_to_test", value: action_to_test),
            string(name: "install_repo", value: params.install_repo),
            string(name: "check_warnings", value: check_warnings),
            string(name: "install_mysql_shell", value: params.install_mysql_shell),
            string(name: "product_to_test", value: params.product_to_test),
            string(name: "git_repo", value: params.git_repo),
            string(name: "git_branch", value: params.git_branch)
        ],
        propagate: true,
        wait: true
    )
}
