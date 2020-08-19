library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/pdmdb"
def creds = [sshUserPrivateKey(credentialsId: 'MOLECULE_AWS_PRIVATE_KEY', keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY', passphraseVariable: '', usernameVariable: ''),
             string(credentialsId: 'GCP_SECRET_KEY', variable: 'GCP_SECRET_KEY'), string(credentialsId: 'GCP_ACCESS_KEY', variable: 'GCP_ACCESS_KEY'),
             [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '4462f2e5-f01c-4e3f-9586-2ffcf5bf366a', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'],
             [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'S3_ACCESS_KEY_ID', credentialsId: '2a84aea7-32a0-4598-9e8d-5153179097a9', secretKeyVariable: 'S3_SECRET_ACCESS_KEY']]

pipeline {
  agent {
  label 'micro-amazon'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdmdbOperatingSystems()
        )
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
            defaultValue: 'pdmdb-4.2.8',
            description: 'PDMDB Version for tests',
            name: 'VERSION')
  }
  options {
          withCredentials(creds)
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}"
                }
            }
        }
    stage('Checkout') {
      steps {
            deleteDir()
            git poll: false, branch: 'master', url: 'https://github.com/Percona-QA/package-testing.git'
        }
    }
    stage ('Prepare') {
      steps {
          script {
              installMolecule()
            }
        }
    }
    stage ('Create virtual machines') {
      steps {
          script{
              moleculeExecuteActionWithScenario(moleculeDir, "create", env.PLATFORM)
            }
        }
    }
    stage ('Run playbook for test') {
      steps {
          script{
              moleculeExecuteActionWithScenario(moleculeDir, "converge", env.PLATFORM)
            }
        }
    }
    stage ('Start testinfra tests') {
      steps {
            script{
              moleculeExecuteActionWithScenario(moleculeDir, "verify", env.PLATFORM)
            }
            junit "molecule/pdmdb/molecule/${PLATFORM}/report.xml"
        }
    }
      stage ('Start Cleanup ') {
      steps {
          script {
              moleculeExecuteActionWithScenario(moleculeDir, "cleanup", env.PLATFORM)
            }
        }
    }
  }
  post {
    always {
          script {
             moleculeExecuteActionWithScenario(moleculeDir, "destroy", env.PLATFORM)
        }
    }
  }
}
