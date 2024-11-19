

    library changelog: false, identifier: "lib@toolkit-pt-molecule", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
    ])

    pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {

        PERCONA_TOOLKIT_BRANCH = "${WORKSPACE}/percona-toolkit"
        TMP_DIR = "/tmp"
        LOG_FILE = "${WORKSPACE}/tmp/${params.TESTING_BRANCH}-${params.MYSQL_VERSION}.log"
        MYSQL_BASEDIR = "Percona-Server-${params.MYSQL_MINOR}-Linux.x86_64.glibc${params.GLIBC}"
        PERCONA_TOOLKIT_SANDBOX = "${WORKSPACE}/sandbox/${MYSQL_BASEDIR}"
        DOWNLOAD_URL = "https://downloads.percona.com/downloads/Percona-Server-${params.MYSQL_VERSION}/Percona-Server-${params.MYSQL_MINOR}/binary/tarball/"
        PATH = "/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin:${PERCONA_TOOLKIT_SANDBOX}/bin"
        SSL_PATH = "${WORKSPACE}/sandbox/ssl"
        LD_LIBRARY_PATH = "${SSL_PATH}/lib:${LD_LIBRARY_PATH}"

    }
    parameters {
        choice(
            choices: [
                'debian-10'
            ],
            description: 'Node to run tests on',
            name: 'node_to_test'
        )
        choice(
            choices: [
                '8.0',
                '5.7',
                '8.4',
            ],
            description: 'Major version for Percona Server for MySQL',
            name: 'MYSQL_VERSION'
        )
        choice(
            choices: [
                '8.0.35-27',
                '8.0.36-28',
                '8.0.37-29',
                '8.0.39-30',
                '5.7.43-47',
                '5.7.44-48',
                '8.4.0-1',
                '8.4.2-2',
            ],
            description: 'Minor version for Percona Server for MySQL',
            name: 'MYSQL_MINOR'
        )
        choice(
            choices: [
                '2.17',
                '2.27',
                '2.28',
                '2.31',
                '2.34',
                '2.35',
            ],
            description: "GLIBC version",
            name: 'GLIBC'
        )
        choice(
            choices: [
                'mysql',
                'pxc',
            ],
            description: "Normal MySQL or PXC",
            name: 'APP'
        )
        choice(
            choices: [
                0,
                1,
            ],
            description: "Debug code (PTDEBUG)",
            name: 'PTDEBUG'
        )
        choice(
            choices: [
                0,
                1,
            ],
            description: "Debug test (PTDEVDEBUG)",
            name: 'PTDEVDEBUG'
        )
        string(
            defaultValue: '3.x',
            description: 'Branch for package-testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'prove -vr --trap --timer t',
            description: 'Test command',
            name: 'TEST_CMD'
        )
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
    }

        stages {
            stage('Set Build Name'){
                steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${node_to_test}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    deleteDir()
                    git poll: false, branch: "toolkit-pt-molecule", url: "https://github.com/Percona-QA/package-testing.git"
                }
            }

            stage('Prepare') {
                steps {
                    script {
                        installMolecule()
                    }
                }
            }

            stage('PRINT ENV VARS') {
                steps {
                    script {
                        sh """
                            echo "PERCONA_TOOLKIT_BRANCH: ${PERCONA_TOOLKIT_BRANCH}"
                            echo "TMP_DIR: ${TMP_DIR}"
                            echo "LOG_FILE: ${LOG_FILE}"
                            echo "MYSQL_BASEDIR: ${MYSQL_BASEDIR}"
                            echo "PERCONA_TOOLKIT_SANDBOX: ${PERCONA_TOOLKIT_SANDBOX}"
                            echo "DOWNLOAD_URL: ${DOWNLOAD_URL}"
                            echo "PATH: ${PATH}"
                            echo "SSL_PATH: ${SSL_PATH}"
                            echo "LD_LIBRARY_PATH: ${LD_LIBRARY_PATH}"
                        """
                    }
                }
            }

            stage('RUN TESTS') {
                        steps {
                            script {

                                     sh """
                                        echo PLAYBOOK_VAR="toolkit-testing" > .env.ENV_VARS
                                    """
                    
                                def envMap = loadEnvFile('.env.ENV_VARS')
                                withEnv(envMap) {
                                    moleculeParallelTest(getNodeList(), "molecule/toolkit/")
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
            python3 -m pip install --upgrade PyYaml==5.3.1 molecule==3.3.0 testinfra pytest molecule-ec2==0.3 molecule[ansible] "ansible<10.0.0" "ansible-lint>=5.1.1,<6.0.0" boto3 boto
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

def getNodeList() {
    return [
        params.node_to_test
    ]
}
