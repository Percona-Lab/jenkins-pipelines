pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: '5.7.38-31.59',
            description: 'PXC lower version tarball to download for testing',
            name: 'LOWER_PXC_VERSION',
            trim: true)
        choice(
            name: 'TEST_DIST',
            choices: [
                'ubuntu-focal',
                'ubuntu-bionic',
                'debian-11',
                'debian-10',
                'centos-7',
                'oracle-linux-8'
            ],
            description: 'Distribution to run test'
        )
        string(
            defaultValue: '8.0.27-18.1',
            description: 'PXC Upper version tarball to download for testing',
            name: 'UPPER_PXC_VERSION')
        string(
            defaultValue: '5.7.38-rel41-59.1',
            description: 'PXC-5.7 package version',
            name: 'PXC57_PKG_VERSION',
            trim: true)

    }
    agent {
        label 'docker'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Prepare') {
            steps {
                echo 'Downloading LOWER_PXC tarball: \$(date -u "+%s")'
                sh '''
                    set -o pipefail
                    ROOT_FS=$(pwd)
                    echo $TEST_DIST
                    sudo killall -9 mysqld || true
                    # Fetch the latest LOWER_PXC binaries
                    cd $ROOT_FS/
                    rm -rf $ROOT_FS/pxc_5.7_tar || true
                    LOWER_PXC_TAR=lower-pxc-latest.tar.gz
                    wget -qcO - https://downloads.percona.com/downloads/TESTING/pxc-${LOWER_PXC_VERSION}/Percona-XtraDB-Cluster-${PXC57_PKG_VERSION}.Linux.x86_64.glibc2.17.tar.gz > ${LOWER_PXC_TAR}
                    tar -xzf ${LOWER_PXC_TAR}
                    rm *.tar.gz
                    mv Percona-XtraDB-* pxc_5.7_tar
                '''
               echo 'Downloading Upper_PXC tarball: \$(date -u "+%s")'
               sh '''
                    set -o pipefail
                    ROOT_FS=$(pwd)
                    sudo killall -9 mysqld || true
                    # Fetch the latest Upper_PXC binaries
                    cd $ROOT_FS
                    rm -rf $ROOT_FS/pxc_8.0_tar || true
                    UPPER_PXC_TAR=upper-pxc-latest.tar.gz
                    wget -qcO - https://downloads.percona.com/downloads/TESTING/pxc-${UPPER_PXC_VERSION}/Percona-XtraDB-Cluster_${UPPER_PXC_VERSION}_Linux.x86_64.glibc2.17.tar.gz > ${UPPER_PXC_TAR}
                    tar -xzf ${UPPER_PXC_TAR}
                    rm *.tar.gz
                    mv Percona-XtraDB-* pxc_8.0_tar
                '''
            }
        }
        stage('Execute testcases') {
            steps {
                echo 'Starting cross verification script: \$(date -u "+%s")'
                sh '''
                    set +e
                    rm -rf percona-qa
                    if [ -f /usr/bin/yum ]; then
                    sudo yum install -y git wget socat redhat-lsb-core
                    lsb_release -a
                    else
                    sudo apt install -y git wget socat
                    fi
                    ROOT_FS=$PWD
                    git clone https://github.com/Percona-QA/percona-qa.git --branch master --depth 1
                    cd percona-qa/pxc-tests
                    bash -x cross_version_pxc_57_80_test.sh $ROOT_FS/pxc_5.7_tar $ROOT_FS/pxc_8.0_tar || status=$?
                    set -e
                    if [[ "$status" == "1" ]];then
                      echo "Printing Node 1 error logs..."
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_5.7_tar/pxc-node/node1.err
                      echo "Printing Node 2 error logs..."
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_8.0_tar/pxc-node/node2.err
                      exit 1
                    else
                      exit 0
                    fi
                '''
                echo 'Starting cross verification upgrade script: \$(date -u "+%s")'
                sh '''
                    set +e
                    ROOT_FS=$PWD
                    cd percona-qa/pxc-tests
                    bash -x cross_version_pxc_57_80_upgrade_test.sh $ROOT_FS/pxc_5.7_tar $ROOT_FS/pxc_8.0_tar || status=$?
                    set -e
                    if [[ "$status" == "1" ]];then
                      echo "Printing PXC_57 error logs..."
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_5.7_tar/pxc_node_57_1/node_57_1.err
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_5.7_tar/pxc_node_57_2/node_57_2.err
                      echo "Printing PXC_80 error logs..."
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_8.0_tar/pxc_node_80_1/node_80_1.err
                      cat /mnt/jenkins/workspace/qa_pxc_57_80_test-pipeline/pxc_8.0_tar/pxc_node_80_2/node_80_2.err
                      exit 1
                    else
                      exit 0
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
