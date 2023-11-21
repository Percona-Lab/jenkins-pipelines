library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])


pipeline {
  agent {
      label "docker-32gb"
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
      defaultValue: '3.2.6-9',
      description: 'Full orchestrator version. Used as version and docker tag',
      name: 'ORCHESTRATOR_VERSION'
    )
    string(
      defaultValue: '',
      description: 'Orchestrator revision for version from https://github.com/percona/orchestrator . Empty by default (not checked).',
      name: 'ORCHESTRATOR_REVISION'
    )
    string(
      defaultValue: '8.0.33-25',
      description: 'Full PS version to test with orchestrator',
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
            steps {
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                        --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/percona-orchestrator:${ORCHESTRATOR_VERSION}
                """
            }//end steps
            post {
                always {
                    junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }//end Run trivy analyzer stage
        stage('Run docker tests') {
          steps {
              script {
                currentBuild.displayName = "#${BUILD_NUMBER}-${DOCKER_ACC}-${ORCHESTRATOR_VERSION}"
                currentBuild.description = "${PS_VERSION}"
              }
              sh '''
                # run test
                export PATH=${PATH}:~/.local/bin
                sudo yum install -y python3 python3-pip
                rm -rf package-testing
                git clone ${TESTING_REPO} -b ${TESTING_BRANCH} --depth 1
                cd package-testing/docker-image-tests/orchestrator
                pip3 install --user -r requirements.txt
                ./run.sh
              '''
          } //end steps
          post {
            always {
              junit 'package-testing/docker-image-tests/orchestrator/report.xml'
            }
          }
        } //end Run docker tests stage
      }//end parallel
    }//end Run parallel
  }//end stages
}
