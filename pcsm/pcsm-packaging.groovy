library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pcsm/install"

pipeline {
  agent {
      label 'min-bookworm-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
      string(name: 'MONGODB_VERSION', description: 'Docker release version (e.g. 8.0.19-7 for Percona, 8.0.19-ubi8 for Mongodb Community)', defaultValue: 'latest')
      booleanParam(name: 'MONGODB_COMMUNITY', defaultValue: false, description: 'Do you want to use Mongodb Community Edition?')
      choice(name: 'install_repo', description: 'Repo for testing',
          choices: [
              'testing',
              'release',
              'experimental'
          ])
      string(name: 'TEST_BRANCH', description: 'Branch for psmdb testing repository', defaultValue: 'main')
      string(name: 'SSH_USER', description: 'User for debugging', defaultValue: 'none')
      string(name: 'SSH_PUBKEY', description: 'User ssh public key for debugging', defaultValue: 'none')
  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Checkout') {
            steps {
                git poll: false, branch: TEST_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
            }
        }
        stage ('Prepare') {
          steps {
                script {
                   installMoleculeBookworm()
             }
           }
        }
        stage('Test') {
          steps {
                script {
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'MONGO_REPO_TOKEN')]) {
                        try {
                            moleculeParallelTestPSMDB(pcsmOperatingSystems(), moleculeDir)
                        } catch (e) {
                            echo "Converge stage failed"
                            throw e
                        }
                    }
                }
            }
         }
    }
    post {
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: package tests for PCSM with ${MONGODB_COMMUNITY.toBoolean() ? 'MongoDB Community Edition' : 'PSMDB'} Version(${MONGODB_VERSION}) finished succesfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: package tests for PCSM with ${MONGODB_COMMUNITY.toBoolean() ? 'MongoDB Community Edition' : 'PSMDB'} Version(${MONGODB_VERSION}) failed - [${BUILD_URL}]")
        }
        always {
            script {
                moleculeParallelPostDestroy(pcsmOperatingSystems(), moleculeDir)
            }
        }
    }
}
