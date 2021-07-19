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
        mkdir test
        wget https://raw.githubusercontent.com/EvgeniyPatlan/percona-server/dev/PS-7757-8.0-zenfs/build-ps/percona-server-8.0_builder.sh -O ps_builder.sh || https://raw.githubusercontent.com/EvgeniyPatlan/percona-server/dev/PS-7757-8.0-zenfs/build-ps/percona-server-8.0_builder.sh -o ps_builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        set -o xtrace
        cd \${build_dir}
        sudo apt-get install -y chrpath ghostscript
        mkdir debs
        cd debs
        wget https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/libperconaserverclient21_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/libperconaserverclient21-dev_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/percona-server-client-zenfs_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/percona-server-common-zenfs_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/percona-server-dbg-zenfs_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/percona-server-rocksdb-zenfs_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/percona-server-server-zenfs_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/percona-server-source-zenfs_8.0.23-14-2.hirsute_amd64.deb https://downloads.percona.com/downloads/TESTING/issue-CUSTOM83/percona-server-test-zenfs_8.0.23-14-2.hirsute_amd64.deb
        export DEBIAN_FRONTEND="noninteractive"
        sudo DEBIAN_FRONTEND=noninteractive apt-get -y install ./*.deb
        wget https://jenkins.percona.com/downloads/nullblk-zoned.sh
        sudo chmod +x nullblk-zoned.sh
        sudo mv nullblk-zoned.sh /usr/bin
        for nulldevice in 0 1; do
            sudo bash -c "echo 0 > /sys/kernel/config/nullb/nullb$nulldevice/power" || true
            sudo rmdir /sys/kernel/config/nullb/nullb$nulldevice || true 

            sudo nullblk-zoned $nulldevice 512 128 124 0 32 12 12
            sudo chown 27:27 /dev/nullb$nulldevice
            sudo chmod 600 /dev/nullb$nulldevice
        done


        ZEN_FS_DOCKER_FLAG="--device=/dev/nullb0 --device=/dev/nullb1"
        unset AUX_PATH_0 AUX_PATH_1
        AUX_PATH_0=/tmp/zenfs_disk_dir_0
        AUX_PATH_1=/tmp/zenfs_disk_dir_1
        sudo rm -rf /tmp/zenfs* $AUX_PATH_0 $AUX_PATH_1 || true

        sudo zenfs mkfs --zbd nullb0 --aux_path $AUX_PATH_0
        sudo zenfs mkfs --zbd nullb1 --aux_path $AUX_PATH_1

        for nulldevice in 0 1; do
            sudo zenfs ls-uuid
            sudo zenfs df --zbd nullb$nulldevice
            sudo zenfs list --zbd nullb$nulldevice

            sudo blkzone report /dev/nullb$nulldevice
            sudo zbd report /dev/nullb$nulldevice
        done

        sudo chown -R 27:27 $AUX_PATH_0 $AUX_PATH_1 
        sudo chmod -R 770 $AUX_PATH_0 $AUX_PATH_1
        

        cd /usr/lib/mysql-test/
        mkdir -p var
        sudo chmod 777 var
        ./mtr --debug-server --force --retry=0 --max-test-fail=0 --testcase-timeout=45 \
  --after-failure-hook="rm -rf ; rm -rf $AUX_PATH_0  $AUX_PATH_1; /usr/bin/zenfs mkfs --zbd nullb0 --aux_path $AUX_PATH_0 --force; /usr/bin/zenfs mkfs --zbd nullb1 --aux_path $AUX_PATH_1 --force" \
  --defaults-extra-file=include/zenfs_nullb_emulated.cnf --suite=rocksdb | tee mtr_rocksdbzenfs_debug.log

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
parameters {
        string(defaultValue: 'https://github.com/EvgeniyPatlan/percona-server.git', description: 'github repository for build', name: 'GIT_REPO')
        string(defaultValue: 'dev/PS-7757-8.0-zenfs', description: 'Tag/Branch for percona-server repository', name: 'BRANCH')
        string(defaultValue: '0', description: 'PerconaFT repository', name: 'PERCONAFT_REPO')
        string(defaultValue: 'Percona-Server-8.0.23-14', description: 'Tag/Branch for PerconaFT repository', name: 'PERCONAFT_BRANCH')
        string(defaultValue: '0', description: 'TokuBackup repository', name: 'TOKUBACKUP_REPO')
        string(defaultValue: 'Percona-Server-8.0.23-14', description: 'Tag/Branch for TokuBackup repository', name: 'TOKUBACKUP_BRANCH')
        string(defaultValue: '2', description: 'RPM version', name: 'RPM_RELEASE')
        string(defaultValue: '2', description: 'DEB version', name: 'DEB_RELEASE')
        choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
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
                stage('Ubuntu Hirsute(21.04)') {
                    agent {
                        label 'min-hirsute-x64-zenfs'
                    }
                    steps {
                        cleanUpWS()
                        installCli("deb")
                        buildStage("ubuntu:hirsute", "--build_deb=1")
                    }
                }
            }
        }

    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${BRANCH}")
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${BRANCH}")
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
