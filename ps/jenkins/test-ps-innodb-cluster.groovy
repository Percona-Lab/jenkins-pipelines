library changelog: false, identifier: 'lib@add-dist', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/kaushikpuneet07/jenkins-pipelines.git'
]) _

void installDependencies() {
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

    sh '''
        rm -rf package-testing
        git clone -b add-dist https://github.com/kaushikpuneet07/package-testing
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
            . virtenv/bin/activate
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
        label 'min-bookworm-x64'
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
            defaultValue: '8.0.32',
            description: 'Upstream MySQL version'
        )
        string(
            name: 'PS_VERSION',
            defaultValue: '24',
            description: 'Percona part of version'
        )
        string(
            name: 'PS_REVISION',
            defaultValue: 'e5c6e9d2',
            description: 'Short git hash for release'
        )
        choice(
            name: 'TEST_DIST',
            choices: [
                'ubuntu-noble',
                'ubuntu-jammy',
                'ubuntu-focal',  
                'debian-12',
                'debian-11',
                'debian-10',
                'centos-7',
                'oracle-8',
                'oracle-9',
                'rhel-8',
                'rhel-9',
                'rhel-9-arm',
                'debian-12-arm' 
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
