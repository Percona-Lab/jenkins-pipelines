pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: '5.7.38-31.59',
            description: 'PXC lower version tarball to download for testing',
            name: 'PXC_LOWER_VERSION_TAR',
            trim: true)
        string(
            defaultValue: '8.0.27-18.1',
            description: 'PXC Upper version tarball to download for testing',
            name: 'PXC_UPPER_VERSION_TAR')
        string(
            defaultValue: '5.7.38-rel41-59.1',
            description: 'PXC-5.7 package version',
            name: 'PXC57_PKG_VERSION',
            trim: true)
        string(
            defaultValue: 'https://github.com/Percona-QA/percona-qa',
            description: 'URL to Percona-QA repository',
            name: 'PERCONA_QA_REPO',
            trim: true)
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for Percona-QA repository',
            name: 'BRANCH',
            trim: true)
        choice(
            choices: [
                'min-centos-7-x64',
                'min-ol-8-x64',
                'min-bionic-x64',
                'min-focal-x64',
                'min-buster-x64',
                'min-bullseye-x64'
            ],
            description: 'Node to run tests',
            name: 'node_to_test'
        )
    }
    agent any
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
stages {
        stage("Build Distribution") {
            agent { label params.node_to_test }
    stages {
        stage('Prepare') {
            steps {
                sh 'echo Downloading PXC LOWER VERSION tarball: \$(date -u)'
                sh '''
                    echo "Installing dependencies..."
                    if [ -f /usr/bin/yum ]; then 
                    sudo yum -y update
                    sudo yum install -y git wget tar socat
                    else
                    lsb_release -a
                    sudo apt install -y git wget socat curl gnupg2 numactl
                    curl -O https://repo.percona.com/apt/percona-release_latest.generic_all.deb
                    sudo apt install -y ./percona-release_latest.generic_all.deb
                    sudo apt-get update
                    fi
                    ROOT_FS=$(pwd)
                    sudo killall -9 mysqld || true
                    # Fetch the latest LOWER_PXC binaries
                    cd $ROOT_FS/
                    rm -rf $ROOT_FS/pxc_5.7_tar || true
                    LOWER_PXC_TAR=lower-pxc-latest.tar.gz
                    wget -qcO - https://downloads.percona.com/downloads/TESTING/pxc-${PXC_LOWER_VERSION_TAR}/Percona-XtraDB-Cluster-${PXC57_PKG_VERSION}.Linux.x86_64.glibc2.17.tar.gz > ${LOWER_PXC_TAR}
                    tar -xzf ${LOWER_PXC_TAR}
                    rm *.tar.gz
                    mv Percona-XtraDB-* pxc_5.7_tar
                '''
               sh 'echo Downloading PXC UPPER VERSION tarball: \$(date -u)'
               sh '''
                    ROOT_FS=$(pwd)
                    sudo killall -9 mysqld || true
                    # Fetch the latest Upper_PXC binaries
                    cd $ROOT_FS
                    rm -rf $ROOT_FS/pxc_8.0_tar || true
                    UPPER_PXC_TAR=upper-pxc-latest.tar.gz
                    wget -qcO - https://downloads.percona.com/downloads/TESTING/pxc-${PXC_UPPER_VERSION_TAR}/Percona-XtraDB-Cluster_${PXC_UPPER_VERSION_TAR}_Linux.x86_64.glibc2.17.tar.gz > ${UPPER_PXC_TAR}
                    tar -xzf ${UPPER_PXC_TAR}
                    rm *.tar.gz
                    mv Percona-XtraDB-* pxc_8.0_tar
                '''
            }
        }
        stage('Execute testcases') {
            steps {
                sh 'echo Starting cross verification script: $(date -u)'
                sh '''
                    set +e
                    rm -rf percona-qa
                    ROOT_FS=$PWD
                    git clone ${PERCONA_QA_REPO} --branch ${BRANCH} --depth 1
                    cd percona-qa/pxc-tests
                    bash -x cross_version_pxc_57_80_test.sh $ROOT_FS/pxc_5.7_tar $ROOT_FS/pxc_8.0_tar
                    error_code_1=$?
                    set -e
                    if [ "$error_code_1" = "0" ]; then
                      exit 0
                    else
                      echo "#############################"
                      echo "Printing PXC_57 error logs..."
                      echo "#############################"
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_5.7_tar/pxc-node/node1.err
                      echo "#############################"
                      echo "Printing PXC_80 error logs..."
                      echo "#############################"
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_8.0_tar/pxc-node/node2.err
                      exit 1
                    fi
                '''
                sh 'echo Starting cross verification upgrade script: $(date -u)'
                sh '''
                    set +e
                    ROOT_FS=$PWD
                    cd percona-qa/pxc-tests
                    bash -x cross_version_pxc_57_80_upgrade_test.sh $ROOT_FS/pxc_5.7_tar $ROOT_FS/pxc_8.0_tar
                    error_code_2=$?
                    set -e
                    if [ "$error_code_2" = "0" ]; then
                      exit 0
                    else
                      echo "#############################"
                      echo "Printing PXC_57 error logs..."
                      echo "#############################"
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_5.7_tar/pxc_node_57_1/node_57_1.err
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_5.7_tar/pxc_node_57_2/node_57_2.err
                      echo "#############################"
                      echo "Printing PXC_80 error logs..."
                      echo "#############################"
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_8.0_tar/pxc_node_80_1/node_80_1.err
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_8.0_tar/pxc_node_80_2/node_80_2.err
                      exit 1
                    fi
                    if [ "$error_code_1" = "0" -a "$error_code_2" = "0" ]; then
                      exit 0
                    else
                      exit 1
                    fi
                '''
           }
        }
    }
    post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
     } 
  }
}
}
