library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def runRouterClusterTests() {
    sh '''
        # run test
        sudo yum install -y jq python3 python3-pip
        rm -rf package-testing
        git clone ${TESTING_REPO} -b ${TESTING_BRANCH} --depth 1
        cd package-testing
        chmod +x router-docker_test.sh
        ./router-docker_test.sh ${DOCKER_ACC}/percona-server:${PS_VERSION} ${DOCKER_ACC}/percona-mysql-router:${ROUTER_VERSION}
        cd docker-image-tests/percona-mysql-router
        pip3 install --user -r requirements.txt
        ./run.sh tests/test_router_cluster_ha.py
    '''
}

def cleanupRouterClusterContainers() {
    sh '''
        sudo docker stop mysql1 mysql2 mysql3 mysql4 mysql-client mysql-router || true
        sudo docker rm mysql1 mysql2 mysql3 mysql4 mysql-client mysql-router || true
        sudo docker network rm innodbnet || true
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
      defaultValue: '8.0.32',
      description: 'Full percona-mysql-router version. Used as version and docker tag',
      name: 'ROUTER_VERSION'
    )
    string(
      defaultValue: '8.0.32-24',
      description: 'Full PS version to build the InnoDB cluster nodes with',
      name: 'PS_VERSION'
    )
    string(
      defaultValue: 'https://github.com/kaushikpuneet07/package-testing.git',
      description: 'Repo for package-testing repository',
      name: 'TESTING_REPO'
    )
    string(
      defaultValue: 'PS-8631',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
  }

  stages {
    stage('Run tests') {
      // percona-server/percona-mysql-router are multi-arch manifests
      // (amd64+arm64 under the same tag), so both branches run the
      // identical script/params - only the fleet node differs, and
      // docker pulls whichever platform layer matches it.
      parallel {
        stage('amd64') {
          agent { label 'docker' }
          steps {
            script {
              currentBuild.displayName = "#${BUILD_NUMBER}-${DOCKER_ACC}-${ROUTER_VERSION}"
              currentBuild.description = "${PS_VERSION}"
            }
            runRouterClusterTests()
          }
          post {
            always {
              junit allowEmptyResults: true, testResults: 'package-testing/docker-image-tests/percona-mysql-router/report.xml'
              cleanupRouterClusterContainers()
            }
          }
        } //end amd64 stage

        stage('arm64') {
          agent { label 'docker-32gb-aarch64' }
          steps {
            runRouterClusterTests()
          }
          post {
            always {
              junit allowEmptyResults: true, testResults: 'package-testing/docker-image-tests/percona-mysql-router/report.xml'
              cleanupRouterClusterContainers()
            }
          }
        } //end arm64 stage
      } //end parallel
    } //end Run test stage
  } //end stages
}
