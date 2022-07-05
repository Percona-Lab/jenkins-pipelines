pipeline {
  agent any 
  parameters {
      string(
            defaultValue: '5.7.38-31.59',
            description: 'PXC lower version tarball to download for testing',
            name: 'LOWER_PXC_VERSION',
            trim: true)
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
  stages {
    stage('Cross Version test') {
      parallel {
        stage('Ubuntu Bionic') {
          agent {
            label "min-bionic-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
            junit 'pxc/jenkins/report.xml'
          } //End steps
        } //End stage Ubuntu Bionic
        stage('Ubuntu Focal') {
          agent {
            label "min-focal-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
            junit 'pxc/jenkins/report.xml'
          } //End steps
        } //End stage Ubuntu Focal
        stage('Debian Buster') {
          agent {
            label "min-buster-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
            junit 'pxc/jenkins/report.xml'
          } //End steps
        } //End stage Debian Buster
        stage('Centos7') {
          agent {
            label "min-centos-7-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
            junit 'package-testing/binary-tarball-tests/pxc/report.xml'
          } //End steps
       } //End parallel
    } //End stage Run tests
  } //End stages
} //End pipeline

void run_test() {
  sh '''
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
post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
     } 
