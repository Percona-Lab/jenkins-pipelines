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
      defaultValue: '9468fd1d',
      description: 'Revision of MYsql Router docker image',
      name: 'Revision'
    )
    string(
      defaultValue: '8.0.32-24',
      description: 'Full PS version to test with router',
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
            export PATH=${PATH}:~/.local/bin
            sudo yum install -y python3 python3-pip
            rm -rf package-testing
            git clone ${TESTING_REPO} -b ${TESTING_BRANCH} --depth 1
            cd package-testing/docker-image-tests/percona-mysql-router
            pip3 install --user -r requirements.txt
            ./run.sh
          '''
      } //end steps
    } //end Run test stage
  } //end stages
  post {
    always {
      junit 'package-testing/docker-image-tests/percona-mysql-router/report.xml'
    }
  }
}
