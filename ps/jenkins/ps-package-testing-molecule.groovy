

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
            choices: ['ps_80', 'ps_84', 'ps_lts_innovation','client_test'],
            description: 'Choose the product version to test: PS8.0 OR ps_lts_innovation',
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
                'install',
                'upgrade',
                'maj_upgrade_to',
                'kmip',
                'kms'
            ],
            description: 'Action To Test',
            name: 'action_to_test'
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
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${action_to_test}"
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
                                if (action_to_test == 'install') {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}" > .env.ENV_VARS
                                    """
                                } else {
                                    sh """
                                        echo PLAYBOOK_VAR="${product_to_test}_${action_to_test}" > .env.ENV_VARS
                                    """
                                }

                                def envMap = loadEnvFile('.env.ENV_VARS')
                                withEnv(envMap) {
                                    moleculeParallelTestPS(psPackageTesting(), "molecule/ps/")
                                }

                            }
                        }
            }
        }
    }

def installMolecule() {
        sh """
            sudo apt update -y
            sudo apt install -y python3 python3-pip python3-dev python3-venv
            python3 -m venv virtenv
            . virtenv/bin/activate
            python3 --version
            python3 -m pip install --upgrade pip
            python3 -m pip install --upgrade setuptools
            python3 -m pip install --upgrade setuptools-rust
            python3 -m pip install --upgrade PyYaml==5.3.1 molecule==3.3.0 pymongo testinfra pytest molecule-ec2==0.3 molecule[ansible] "ansible<10.0.0" "ansible-lint>=5.1.1,<6.0.0" boto3 boto
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
