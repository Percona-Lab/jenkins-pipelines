library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installDependencies() {
    sh '''
        export PATH=${PATH}:~/.local/bin
        sudo yum install -y git python3-pip jq
        sudo amazon-linux-extras install ansible2
        python3 -m venv venv
        source venv/bin/activate
        python3 -m pip install setuptools wheel
        python3 -m pip install molecule==2.22 boto boto3 paramiko
    '''

    sh '''
        rm -rf package-testing
        git clone https://github.com/Percona-QA/package-testing
    '''
}

void runMoleculeAction(String action, String version, String scenario) {
    def awsCredentials = [
        sshUserPrivateKey(
            credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
            keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
            passphraseVariable: '',
            usernameVariable: ''
        ),
        aws(
            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
            credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
        )
    ]

    withCredentials(awsCredentials) {
        sh """
            source venv/bin/activate

            export MOLECULE_DEBUG=1
            export install_repo=${params.INSTALL_REPO}

            cd package-testing/molecule/pxc

            cd ${version}-bootstrap
            export INSTANCE_PRIVATE_IP=\${BOOTSTRAP_INSTANCE_PRIVATE_IP}
            molecule ${action} -s ${scenario}
            cd -

            cd ${version}-common
            export INSTANCE_PRIVATE_IP=\${COMMON_INSTANCE_PRIVATE_IP}
            molecule ${action} -s ${scenario}
            cd -
        """
    }
}

void setInstancePrivateIPEnvironment() {
    env.PXC1_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("pxc1")).private_ip\' ${BOOTSTRAP_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PXC2_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("pxc2")).private_ip\' ${COMMON_INSTANCE_PRIVATE_IP}',
        returnStdout: true
    ).trim()
    env.PXC3_IP = sh(
        script: 'jq -r \'.[] | select(.instance | startswith("pxc3")).private_ip\' ${COMMON_INSTANCE_PRIVATE_IP}',
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
        BOOTSTRAP_INSTANCE_PRIVATE_IP = "${WORKSPACE}/bootstrap_instance_private_ip.json"
        COMMON_INSTANCE_PRIVATE_IP = "${WORKSPACE}/common_instance_private_ip.json"
    }

    parameters {
        choice(
            name: 'VERSION',
            choices: [
                'pxc80',
                'pxc57',
                'pxc56'
            ],
            description: 'PXC version to test'
        )
        choice(
            name: 'TEST_DIST',
            choices: [
                'ubuntu-focal',
                'ubuntu-bionic',
                'ubuntu-xenial',
                'debian-11',
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
                runMoleculeAction("create", params.VERSION, params.TEST_DIST)
                setInstancePrivateIPEnvironment()
            }
        }

        stage("Converge") {
            steps {
                runMoleculeAction("converge", params.VERSION, params.TEST_DIST)
            }
        }
    }

    post {
        always {
            script {
                runMoleculeAction("destroy", params.VERSION, params.TEST_DIST)
            }
        }
    }
}
