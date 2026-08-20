library changelog: false, identifier: "lib@PS-8631", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/kaushikpuneet07/jenkins-pipelines.git'
])


pipeline {
  agent {
      label "docker"
  }

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
    stage('Build') {
      steps {
          script {
            currentBuild.displayName = "#${BUILD_NUMBER}-${DOCKER_ACC}-${ROUTER_VERSION}"
            currentBuild.description = "${PS_VERSION}"
              }
            }
          }

    stage('Run tests') {
      steps {
          sh '''
            # run test
            sudo yum install -y jq python3 python3-pip
            rm -rf package-testing
            git clone ${TESTING_REPO} -b ${TESTING_BRANCH} --depth 1

            # Phase 1: static/attribute checks against a standalone router
            # image. This builds and tears down its own mysql1-4/router
            # cluster, so it must run - and fully clean up - before phase 2
            # builds the cluster router-docker_test.sh depends on.
            cd package-testing/docker-image-tests/percona-mysql-router
            pip3 install --user -r requirements.txt
            ./run.sh tests/test_router_attr.py tests/test_router_static.py || true
            mv -f report.xml report_static.xml || true

            # Phase 2: build the InnoDB cluster + router + sysbench data +
            # fault-tolerance check, then assert the outcome.
            cd ../..
            chmod +x router-docker_test.sh
            ./router-docker_test.sh ${DOCKER_ACC}/percona-server:${PS_VERSION} ${DOCKER_ACC}/percona-mysql-router:${ROUTER_VERSION} || true

            cd docker-image-tests/percona-mysql-router
            ./run.sh tests/test_router_cluster_ha.py || true
            mv -f report.xml report_cluster_ha.xml || true
          '''
      } //end steps
    } //end Run test stage
  } //end stages
  post {
    always {
      junit allowEmptyResults: true, testResults: 'package-testing/docker-image-tests/percona-mysql-router/report_*.xml'
      sh '''
        sudo docker stop mysql1 mysql2 mysql3 mysql4 mysql-client mysql-router || true
        sudo docker rm mysql1 mysql2 mysql3 mysql4 mysql-client mysql-router || true
        sudo docker network rm innodbnet || true
      '''
    }
  }
}
