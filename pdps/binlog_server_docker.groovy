library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def runBinlogServerTests() {
    sh '''
        # run test
        export PATH=${PATH}:~/.local/bin
        sudo yum install -y python3 python3-pip
        rm -rf package-testing
        git clone ${TESTING_REPO} -b ${TESTING_BRANCH} --depth 1
        cd package-testing/docker-image-tests/binlog-server
        pip3 install --user -r requirements.txt
        ./run.sh
    '''
}

pipeline {
  agent none

  parameters {
    choice(
      name: 'DOCKER_ACC',
      description: 'Docker repo to use: percona or perconalab',
      choices: [
        'perconalab',
        'percona'
      ]
    )
    string(
      defaultValue: '0.4.1-1',
      description: 'Full percona-binlog-server version. Used as version and docker tag',
      name: 'PBS_VERSION'
    )
    string(
      defaultValue: '9.7.1-1',
      description: 'Full PS version to use as the binlog_server replication source',
      name: 'PS_VERSION'
    )
    string(
      defaultValue: 'https://github.com/Percona-QA/package-testing.git',
      description: 'Repo for package-testing repository',
      name: 'TESTING_REPO'
    )
    string(
      defaultValue: 'master',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
  }

  stages {
    stage("Run parallel") {
      parallel {
        stage ('Run trivy analyzer') {
          agent { label 'docker-32gb' }
          steps {
              sh """
                  TRIVY_VERSION="0.69.3"
                  TRIVY_CHECKSUM="1816b632dfe529869c740c0913e36bd1629cb7688bd5634f4a858c1d57c88b75"
                  wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                  echo "\${TRIVY_CHECKSUM}  trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz" | sha256sum -c -
                  sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                  wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                  /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                      --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-binlog-server:${PBS_VERSION}
              """
          }//end steps
          post {
              always {
                  junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
              }
          }
        }//end Run trivy analyzer stage

        // percona-binlog-server and percona-server are multi-arch manifests
        // (amd64+arm64 under the same tag), so both branches run the
        // identical script/params - only the fleet node differs, and
        // docker pulls whichever platform layer matches it.
        stage('Run docker tests (amd64)') {
          agent { label 'docker-32gb' }
          steps {
              script {
                currentBuild.displayName = "#${BUILD_NUMBER}-${DOCKER_ACC}-${PBS_VERSION}"
                currentBuild.description = "${PS_VERSION}"
              }
              runBinlogServerTests()
          } //end steps
          post {
            always {
              junit allowEmptyResults: true, testResults: 'package-testing/docker-image-tests/binlog-server/report.xml'
            }
          }
        } //end amd64 stage

        stage('Run docker tests (arm64)') {
          agent { label 'docker-32gb-aarch64' }
          steps {
              runBinlogServerTests()
          } //end steps
          post {
            always {
              junit allowEmptyResults: true, testResults: 'package-testing/docker-image-tests/binlog-server/report.xml'
            }
          }
        } //end arm64 stage
      }//end parallel
    }//end Run parallel
  }//end stages
}
