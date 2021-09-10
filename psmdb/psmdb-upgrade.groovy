library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb/psmdb-upgrade"

pipeline {
  agent {
  label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
      OLDVERSIONS = "PSMDB_VERSION=${params.FROM_PSMDB_VERSION}"
      NEWVERSIONS = "PSMDB_VERSION=${params.TO_PSMDB_VERSION}"
  }
  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdmdbOperatingSystems()
        )
        choice(
            name: 'FROM_REPO',
            description: 'Repo for base install',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '4.4.8',
            description: 'From this version PSMDB will be updated',
            name: 'FROM_PSMDB_VERSION'
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for update',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '5.0.2',
            description: 'To this version PDMDB will be updated',
            name: 'TO_PSMDB_VERSION'
        )
        choice(
            name: 'ENCRYPTION',
            description: 'Enable/disable encryption at rest',
            choices: [
                'NONE',
                'VAULT',
                'KEYFILE'
            ]
        )
        choice(
            name: 'CIPHER',
            description: 'Cipher (make sense only if encryption enabled)',
            choices: [
                'AES256-CBC',
                'AES256-GCM'
            ]
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
//          disableConcurrentBuilds()
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
              moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "converge", env.PLATFORM, env.OLDVERSIONS)              
            }
        }
    }
    stage ('Start testinfra tests for old version') {
      steps {
            script{
              moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "verify", env.PLATFORM, env.OLDVERSIONS)
            }
        }
    }
    stage ('Run playbook for test with new version') {
      steps {
          script{
              moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "side-effect", env.PLATFORM, env.NEWVERSIONS)
            }
        }
    }
    stage ('Start testinfra tests for new version') {
      steps {
            script{
              moleculeExecuteActionWithVariableListAndScenario(moleculeDir, "verify", env.PLATFORM, env.NEWVERSIONS)
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
