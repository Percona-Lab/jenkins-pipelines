pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: '5.7.38-31.59',
            description: 'PXC lower version tarball to download for testing',
            name: 'LOWER_PXC_VERSION',
            trim: true)
        choice(
            choices: 'ubuntu:focal',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
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
        label 'micro-amazon'
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
                    sudo killall -9 mysqld || true
                    # Fetch the latest LOWER_PXC binaries
                    rm -rf $ROOT_FS/lower_pxc_latest || true
                    mkdir $ROOT_FS/lower_pxc_latest
                    cd $ROOT_FS/lower_pxc_latest
                    LOWER_PXC_TAR=lower-pxc-latest.tar.gz
                    wget -cO - https://downloads.percona.com/downloads/TESTING/pxc-${LOWER_PXC_VERSION}/Percona-XtraDB-Cluster-${PXC57_PKG_VERSION}.Linux.x86_64.glibc2.17.tar.gz > ${LOWER_PXC_TAR}
                    tar -xzf ${LOWER_PXC_TAR}
                    rm *.tar.gz
                    mv Percona-XtraDB-* Lower-Percona-XtraDB-latest
                '''
               echo 'Downloading Upper_PXC tarball: \$(date -u "+%s")'
               sh '''
                    set -o pipefail
                    ROOT_FS=$(pwd)
                    sudo killall -9 mysqld || true
                    # Fetch the latest Upper_PXC binaries
                    rm -rf $ROOT_FS/upper_pxc_latest || true
                    mkdir $ROOT_FS/upper_pxc_latest
                    cd $ROOT_FS/upper_pxc_latest
                    UPPER_PXC_TAR=upper-pxc-latest.tar.gz
                    wget -cO - https://downloads.percona.com/downloads/TESTING/pxc-${UPPER_PXC_VERSION}/Percona-XtraDB-Cluster_${UPPER_PXC_VERSION}_Linux.x86_64.glibc2.17.tar.gz > ${UPPER_PXC_TAR}
                    tar -xzf ${UPPER_PXC_TAR}
                    rm *.tar.gz
                    mv Percona-XtraDB-* Upper-Percona-XtraDB-latest
                '''
            }
        }
        stage('Execute testcases') {
            steps {
                echo 'Starting cross verification script: \$(date -u "+%s")'
                sh '''
                    set +e
                    exitcode=0
                    rm -rf percona-qa
                    if [ -f /usr/bin/yum ]; then
                    sudo yum install -y git wget
                    else
                    sudo apt install -y git wget
                    fi
                    git clone https://github.com/Percona-QA/percona-qa.git --branch master --depth 1
                    cd percona-qa/pxc-tests
                    ./cross_version_pxc_57_80_test.sh $ROOT_FS/Lower-Percona-XtraDB-latest $ROOT_FS/Upper-Percona-XtraDB-latest
                    set -e
                    if [ $exitcode1 -gt 0 ];then
                      exit 1;
                    else
                      exit 0;
                    fi
                '''
            }
        }
//    }
//    post {
//        always {
//            sh '''
//                echo Finish: \$(date -u "+%s")
//            '''
//        }
//     } 
