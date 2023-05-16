library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pbm/upgrade"

pipeline {
  agent {
  label 'min-centos-7-x64'
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
            name: 'install_repo',
            description: 'Repo for testing',
            choices: [
                'testing',
                'main',
                'experimental'
            ]
        )
        string(
            defaultValue: 'psmdb-42',
            description: 'PSMDB for testing. Valid values: psmdb-4*, psmdb-36',
            name: 'psmdb_to_test')
        choice(
            name: 'FROM_REPO',
            description: 'From this repo will be upgraded PPG',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '1.6.0',
            description: 'From this version PBM will be updated',
            name: 'FROM_VERSION'
        )
        string(
            defaultValue: '1.6.0',
            description: 'To this version PBM will be updated',
            name: 'VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
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
            git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
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
    stage ('Prepare VM for test') {
      steps {
          script{
              moleculeExecuteActionWithScenario(moleculeDir, "prepare", env.PLATFORM)
            }
        }
    }
    stage ('Run playbook for test with old version') {
      steps {
          script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "converge", env.PLATFORM, "VERSION", env.FROM_VERSION)
            }
        }
    }
    stage ('Start testinfra tests for old version') {
      steps {
            script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "verify", env.PLATFORM, "VERSION", env.FROM_VERSION)
            }
        }
    }
    stage ('Run playbook for test with new version') {
      steps {
          script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "side-effect", env.PLATFORM, "VERSION", env.VERSION)
            }
        }
    }
    stage ('Start testinfra tests for new version') {
      steps {
            script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "verify", env.PLATFORM, "VERSION", env.VERSION)
            }
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
