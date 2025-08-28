
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
        install_repo = "${params.install_repo}"
        scenario_to_test = "${params.scenario_to_test}"
        TESTING_BRANCH = "${params.TESTING_BRANCH}"
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
        string(
            defaultValue: 'master',
            description: 'Branch for package-testing repository',
            name: 'TESTING_BRANCH'
        )
        choice(
            choices: [
                'no',
                'yes'
            ],
            description: 'test upstream packages',
            name: 'upstream'
        )

    }
    options {
        withCredentials(moleculepxbJenkinsCreds())
    }
    stages {
        stage('Set Build Name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-upstream:${upstream}"
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

    if (upstream == "yes") {
        if (product_to_test == "pxb_innovation_lts") {
            server = "ms_innovation_lts"
        } else if (product_to_test == "pxb_80") {
            server = "ms-80"
        } else if (product_to_test == "pxb_84") {
            server = "ms-84"
        } else {
            echo "Not added support for this product version"
        }
    } else if (upstream == "no") {
        if (product_to_test == "pxb_innovation_lts") {
            server = "ps_innovation_lts"
        } else if (product_to_test == "pxb_80") {
            server = "ps-80"
        } else if (product_to_test == "pxb_84") {
            server = "ps-84"
        } else {
            echo "Not added support for this product version"
        }
    }

    build(
        job: 'pxb-package-testing-molecule',
        parameters: [
            string(name: "scenario_to_test", value: scenario_to_test),
            string(name: "server_to_test", value: server),
            string(name: "git_repo", value: git_repo),
            string(name: "install_repo", value: params.install_repo),
            string(name: "product_to_test", value: params.product_to_test),
            string(name: "TESTING_BRANCH", value: params.TESTING_BRANCH)
        ],
        propagate: true,
        wait: true
    )
}
