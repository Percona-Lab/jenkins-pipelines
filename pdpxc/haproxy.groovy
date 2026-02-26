library changelog: false, identifier: "lib@fix-ha", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/kaushikpuneet07/jenkins-pipelines.git'
])


pipeline {
  agent {
    label 'min-bookworm-x64'
  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pdmysql/haproxy";
  }
  parameters {
    choice(
        name: 'REPO',
        description: 'Repo for testing',
        choices: [
            'testing',
            'experimental',
            'release'
        ]
    )
    string(
        defaultValue: '8.0.35',
        description: 'PXC version for test',
        name: 'VERSION'
    )
    string(
      name: 'HAPROXY_VERSION',
      defaultValue: '2.8.5',
      description: 'Full haproxy version. Used as version and docker tag'
    )
    choice(
      name: 'DOCKER_ACC',
      description: 'Docker repo to use: percona or perconalab',
      choices: [
        'perconalab',
        'percona'
      ]
    )
    booleanParam(
        name: 'skip_molecule',
        description: "Enable to skip molecule HAproxy tests"
    )
    booleanParam(
        name: 'skip_docker',
        description: "Enable to skip Docker tests"
    )
    string(
      name: 'TESTING_REPO',
      defaultValue: 'https://github.com/Percona-QA/package-testing.git',
      description: 'Repo for package-testing repository'
    )
    string(
      name: 'TESTING_BRANCH',
      defaultValue: 'master',
      description: 'Branch for package-testing repository'
    )
    choice(
      name: 'DESTROY_MOLECULE_ENV',
      description: 'Destroy VM after tests',
      choices: [
        'yes',
        'no'
      ]
    )
  }

  options {
    withCredentials(moleculePdpxcJenkinsCreds())
    disableConcurrentBuilds()
  }

  stages {
    stage('Set build name'){
      steps {
            script {
              currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-${env.HAPROXY_VERSION}"
              currentBuild.description = "${env.REPO}-${env.DOCKER_ACC}-${env.TESTING_BRANCH}"
            }
          }
        }

    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: TESTING_REPO
      }
    }

    stage("Run parallel") {
      parallel {
        stage ('Molecule') {
          when {
            expression {
              !params.skip_molecule
            }
          }
          stages {
            stage ('Molecule: Prepare') {
              steps {
                script {
                  installMoleculeBookworm()
                }
              }
            }
            stage ('Molecule: Create virtual machines') {
              steps {
                script{
                  moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "create", "ubuntu-jammy")
                }
              }
            }
            stage ('Molecule: Run playbook for test') {
              steps {
                script{
                  moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "converge", "ubuntu-jammy")
                }
              }
            }
            stage ('Molecule: Start testinfra tests') {
              steps {
                script{
                  moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "verify", "ubuntu-jammy")
                }
              }
            }
            stage ('Molecule: Start Cleanup ') {
              steps {
                script {
                  moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "cleanup", "ubuntu-jammy")
                }
              }
            }
          }
          post {
            always {
              script {
                if (env.DESTROY_MOLECULE_ENV == "yes") {
                    moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", "ubuntu-jammy")
                }
              }
            }
          }
        }
        stage ('Docker') {
          when {
            beforeAgent true
            expression {
              !params.skip_docker
            }
          }
          agent {
            label 'docker'
          }
          stages {
            stage ('Docker: Run trivy analyzer') {
              steps {
                catchError {
                  sh """
                      TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                      wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                      sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                      wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl
                      /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-hight-junit.xml \
                                          --timeout 10m0s --ignore-unfixed --exit-code 1 --severity HIGH,CRITICAL ${DOCKER_ACC}/haproxy:${HAPROXY_VERSION}
                  """
                }
              }
              post {
                always {
                  junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
              }
            }
            stage('Docker: Run tests') {
              steps {
                sh '''
                  # run test
                  export PATH=${PATH}:~/.local/bin
                  sudo yum install -y python3 python3-pip
                  rm -rf package-testing
                  git clone ${TESTING_REPO} -b ${TESTING_BRANCH} --depth 1
                  cd package-testing/docker-image-tests/haproxy
                  pip3 install --user -r requirements.txt
                  ./run.sh
                '''
              }
              post {
                always {
                  junit 'package-testing/docker-image-tests/haproxy/report.xml'
                }
              }
            }
          }
        }
      }
    }
  }
}
