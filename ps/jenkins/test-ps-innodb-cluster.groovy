library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installDependencies() {
    sh '''
        export PATH=${PATH}:~/.local/bin
        sudo yum install -y git python3-pip
        python3 -m venv venv
        source venv/bin/activate
        python3 -m pip install --upgrade pip setuptools wheel
        python3 -m pip install molecule==2.22 ansible boto boto3 paramiko testinfra
    '''

    sh '''
        rm -rf package-testing
        git clone https://github.com/Percona-QA/package-testing
        cd package-testing
        git checkout wip-test-ps-innodb-cluster
    '''
}

void runMoleculeAction(String action, String scenario) {
    def awsCredentials = [
        sshUserPrivateKey(
            credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
            keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
            passphraseVariable: '',
            usernameVariable: ''
        ),
        aws(
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]

    withCredentials(awsCredentials) {
        sh """
            source venv/bin/activate
            export MOLECULE_DEBUG=1
            cd package-testing/molecule/ps-innodb-cluster-server
            molecule ${action} -s ${scenario}
            cd ../ps-innodb-cluster-router
            molecule ${action} -s ${scenario}
        """
    }
}

pipeline {
    agent {
        label 'micro-amazon'
    }

    options {
        skipDefaultCheckout()
    }

    environment {
        PS_NODE1_IP = "10.177.1.50"
        PS_NODE2_IP = "10.177.1.51"
        PS_NODE3_IP = "10.177.1.52"
        MYSQL_ROUTER_IP = "10.177.1.53"
    }

    parameters {
        string(
            name: 'UPSTREAM_VERSION',
            defaultValue: '8.0.23',
            description: 'Upstream MySQL version'
        )
        string(
            name: 'PS_VERSION',
            defaultValue: '14',
            description: 'Percona part of version'
        )
        string(
            name: 'PS_REVISION',
            defaultValue: '3558242',
            description: 'Short git hash for release'
        )
        choice(
            name: 'TEST_DIST',
            choices: [
                'ubuntu-focal',
                'ubuntu-bionic',
                'ubuntu-xenial',
                'debian-10',
                'debian-9',
                'centos-8',
                'centos-7',
                'centos-6'
            ],
            description: 'Distribution to run test'
        )
        choice(
            name: 'INSTALL_REPO',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )
    }

    stages {
        stage("Set up") {
            steps {
                installDependencies()
            }
        }

        stage("Create") {
            steps {
                runMoleculeAction("create", params.TEST_DIST)
            }
        }

        stage("Converge") {
            steps {
                runMoleculeAction("converge", params.TEST_DIST)
            }
        }

        stage("Verify") {
            steps {
                runMoleculeAction("verify", params.TEST_DIST)
            }
        }
    }

    post {
        always {
            script {
                runMoleculeAction("destroy", params.TEST_DIST)
            }
        }
    }
}