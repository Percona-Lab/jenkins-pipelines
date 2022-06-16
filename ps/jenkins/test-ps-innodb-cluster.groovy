library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installDependencies() {
    sh '''
        export PATH=${PATH}:~/.local/bin
        sudo yum install -y git python3-pip jq tar
        sudo amazon-linux-extras install ansible2
        python3 -m venv venv
        source venv/bin/activate
        python3 -m pip install --upgrade pip setuptools wheel
        python3 -m pip install molecule==2.22 boto boto3 paramiko testinfra
    '''

    sh '''
        rm -rf package-testing
        git clone https://github.com/Percona-QA/package-testing
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
            cd package-testing/molecule/ps-innodb-cluster
            cd server
            export INSTANCE_PRIVATE_IP=\${SERVER_INSTANCE_PRIVATE_IP}
            molecule ${action} -s ${scenario}
            cd -
            cd router
            export INSTANCE_PRIVATE_IP=\${ROUTER_INSTANCE_PRIVATE_IP}
            molecule ${action} -s ${scenario}
            cd -
        """
    }
}

void setInstancePrivateIPEnvironment() {
    env.PS_NODE1_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("ps-node1")).private_ip\' ${SERVER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PS_NODE2_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("ps-node2")).private_ip\' ${SERVER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PS_NODE3_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("ps-node3")).private_ip\' ${SERVER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.MYSQL_ROUTER_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("mysql-router")).private_ip\' ${ROUTER_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
}

pipeline {
    agent {
        label 'micro-amazon'
    }

    options {
        skipDefaultCheckout()
    }

    environment {
        SERVER_INSTANCE_PRIVATE_IP = "${WORKSPACE}/server_instance_private_ip.json"
        ROUTER_INSTANCE_PRIVATE_IP = "${WORKSPACE}/router_instance_private_ip.json"
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
                'debian-11',
                'debian-10',
                'debian-9',
                'centos-7',
                'oracle-linux-8'
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
	        script {
                   currentBuild.displayName = "#${BUILD_NUMBER}-${UPSTREAM_VERSION}-${PS_VERSION}-${TEST_DIST}"
                   currentBuild.description = "${PS_REVISION}-${INSTALL_REPO}"
                }
                installDependencies()
            }
        }

        stage("Create") {
            steps {
                runMoleculeAction("create", params.TEST_DIST)
                setInstancePrivateIPEnvironment()
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
