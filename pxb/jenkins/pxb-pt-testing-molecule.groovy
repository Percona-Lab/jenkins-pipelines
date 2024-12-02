
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

            stage('Checkout') {
                steps {
                    deleteDir()
                    git poll: false, branch: "master", url: "https://github.com/Percona-QA/package-testing.git"
                }
            }

            stage('Prepare') {
                steps {
                    script {
                        installMolecule()
                    }
                }
            }
            stage('RUN TESTS') {
                        steps {
                            script {
                                if (scenario_to_test == 'install') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                                    """
                                } else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${scenario_to_test}" > .env.ENV_VARS
                                    """
                                }

                                def envMap = loadEnvFile('.env.ENV_VARS')
                                withEnv(envMap) {
                                    moleculeParallelTestPXB(pxbPackageTesting(), "molecule/pxb-package-testing/")
                                }

                            }
                        }
            }
        }
    }

def installMolecule() {
    sh """
        sudo yum install -y gcc python3-pip python3-devel libselinux-python3
        sudo yum remove ansible -y
        python3 -m venv virtenv
        . virtenv/bin/activate
        python3 --version
        python3 -m pip install --upgrade pip
        python3 -m pip install --upgrade setuptools
        python3 -m pip install --upgrade setuptools-rust
        python3 -m pip install --upgrade molecule==3.3.0 testinfra pytest molecule-ec2==0.3 molecule[ansible] boto3 boto
    """
}
def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}
