pipeline {
   agent {
        label 'docker'
    }
  parameters {
    string(name: 'LOWER_PXC_VERSION', defaultValue: '5.7.38-31.59', description: 'PXC lower version tarball to download for testing')
    string(name: 'UPPER_PXC_VERSION', defaultValue: '8.0.27-18.1', description: 'PXC Upper version tarball to download for testing')
    string(name: 'PXC57_PKG_VERSION', defaultValue: '5.7.38-rel41-59.1', description: 'PXC-5.7 package version')
    string(name: 'PERCONA_QA_REPO', defaultValue: 'https://github.com/Percona-QA/percona-qa', description: 'URL to Percona-QA repository')
    string(name: 'BRANCH', defaultValue: 'master', description: 'Tag/Branch for Percona-QA repository')
    booleanParam( 
      defaultValue: false,									
      name: 'BUILD_TYPE_MINIMAL'
    )
  }
  stages {
    stage('Cross Version Test') {
      parallel {
        stage('Ubuntu Bionic') {
          agent {
            label "min-bionic-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
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
          } //End steps
        } //End stage Ubuntu Focal
        stage('Debian Bullseye') {
          agent {
            label "min-bullseye-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
          } //End steps
        } //End stage Debian Bullseye
        stage('Centos7') {
          agent {
            label "min-centos-7-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
          } //End steps
        } //End stage CentOS7
        stage('Oracle Linux 8') {
          agent {
            label "min-ol-8-x64"
          }
          steps {
            withCredentials([usernamePassword(credentialsId: 'JenkinsAPI', passwordVariable: 'JENKINS_API_PWD', usernameVariable: 'JENKINS_API_USER')]) {
              run_test()
            }
          } //End steps
        } //End stage Oracle Linux 8
       } //End parallel
    } //End stage Run tests
  } //End stages
} //End pipeline

void run_test() {
  sh 'echo Downloading LOWER_PXC tarball: \$(date -u)'
               sh '''
                    echo "Installing dependencies..."
                    if [ -f /usr/bin/yum ]; then 
                    sudo yum -y update
                    sudo yum install -y git wget tar socat redhat-lsb-core
                    lsb_release -a
                    else
                    sudo apt-get update
                    sudo apt install -y git wget ansible socat curl numactl
                    lsb_release -a
                    fi
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
  sh 'echo Downloading Upper_PXC tarball: \$(date -u)'
               sh '''
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
                      cat /mnt/jenkins/workspace/qa_pxc_cross-pipeline/pxc_5.7_tar/pxc-node/node1.err
                      echo "#############################"
                      echo "Printing PXC_80 error logs..."
                      echo "#############################"
                      cat /mnt/jenkins/workspace/qa_pxc_cross-pipeline/pxc_8.0_tar/pxc-node/node2.err
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
                      cat /mnt/jenkins/workspace/qa_pxc_cross-pipeline/pxc_5.7_tar/pxc_node_57_1/node_57_1.err
                      cat /mnt/jenkins/workspace/qa_pxc_cross-pipeline/pxc_5.7_tar/pxc_node_57_2/node_57_2.err
                      echo "#############################"
                      echo "Printing PXC_80 error logs..."
                      echo "#############################"
                      cat /mnt/jenkins/workspace/qa_pxc_cross-pipeline/pxc_8.0_tar/pxc_node_80_1/node_80_1.err
                      cat /mnt/jenkins/workspace/qa_pxc_cross-pipeline/pxc_8.0_tar/pxc_node_80_2/node_80_2.err
                      exit 1
                    fi
                    if [ "$error_code_1" == "0" -a "$error_code_2" == "0" ]; then
                      exit 0
                    else
                      exit 1
                    fi
                '''

            }
    post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
     } 
