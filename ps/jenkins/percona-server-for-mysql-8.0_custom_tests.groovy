/* groovylint-disable DuplicateStringLiteral, GStringExpressionWithinString, LineLength */
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void installCli(String PLATFORM) {
    sh """
        set -o xtrace
        if [ -d aws ]; then
            rm -rf aws
        fi
        if [ ${PLATFORM} = "deb" ]; then
            sudo apt-get update
            sudo apt-get -y install wget curl unzip
        elif [ ${PLATFORM} = "rpm" ]; then
            sudo yum -y install wget curl unzip
        fi
        curl https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o awscliv2.zip
        unzip awscliv2.zip
        sudo ./aws/install || true
    """
}

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        export build_dir=\$(pwd -P)
        cd \${build_dir}
        mkdir debs
        cd debs
        wget https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/libperconaserverclient21-dev_8.0.25-15-2.stretch_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/libperconaserverclient21_8.0.25-15-2.stretch_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/percona-mysql-router_8.0.25-15-2.stretch_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/percona-server-client_8.0.25-15-2.stretch_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/percona-server-common_8.0.25-15-2.stretch_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/percona-server-dbg_8.0.25-15-2.stretch_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/percona-server-server_8.0.25-15-2.stretch_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM84/percona-server-test_8.0.25-15-2.stretch_amd64.deb
        export DEBIAN_FRONTEND="noninteractive"
        sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ./*.deb

        cd /usr/lib/mysql-test/
        if [ -f var ]; then
            sudo rm -rf var
        fi
        if [ -d  \${build_dir}/var ]; then
            sudo rm -rf \${build_dir}/var
        fi
        sudo touch /usr/lib/mysql/plugin/component_keyring_file.cnf
        sudo touch /usr/sbin/mysqld.my
        sudo chmod 777 /usr/lib/mysql/plugin/component_keyring_file.cnf
        sudo chmod 777 /usr/sbin/mysqld.my
        sudo mkdir -p \${build_dir}/var
        sudo chmod 777 \${build_dir}/var
        sudo ln -s \${build_dir}/var var
        sudo sed -i "3533s:mkpath:#mkpath:" ./mtr
        sudo sed -i "4090s:remove:#remove:" ./mtr
        sudo sed -i "3524s:mkpath:#mkpath:" ./mtr
        ./mtr --force --parallel=4 --testcase-timeout=45 --retry=3

    """
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label 'docker'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Run tests') {
            parallel {
                stage('Debian9') {
                    agent {
                        label 'min-stretch-x64'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        buildStage("debian:stretch", "--build_deb=1")
                    }
                }
            }
        }

    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Tests has been finished successfully")
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Tests failed")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
