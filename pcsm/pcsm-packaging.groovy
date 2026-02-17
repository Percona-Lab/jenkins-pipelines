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
      string(
          name: 'pcsm_version',
          description: 'Release branch version (e.g. 1.0.0)',
          defaultValue: '1.0.0')
      choice(
          name: 'install_repo',
          description: 'Repo for testing',
          choices: [
              'testing',
              'release',
              'experimental'
          ]
      )
      choice(
          name: 'psmdb_version',
          description: 'Version of PSMDB PCSM will interact with',
          choices: [
              '6.0',
              '7.0',
              '8.0'
          ]
      )
      string(
          name: 'TEST_BRANCH',
          description: 'Branch for testing repository',
          defaultValue: 'main')
      string(
          name: 'SSH_USER',
          description: 'User for debugging',
          defaultValue: 'none')
      string(
          name: 'SSH_PUBKEY',
          description: 'User ssh public key for debugging',
          defaultValue: 'none')
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
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: package tests for PCSM with PSMDB Version(${psmdb_version}) finished succesfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: package tests for PCSM with PSMDB Version(${psmdb_version}) failed - [${BUILD_URL}]")
        }
        always {
            script {
                moleculeParallelPostDestroy(pcsmOperatingSystems(), moleculeDir)
            }
        }
    }
}
