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
            description: 'To this version PSMDB will be updated',
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
        stage('Create virtual machines') {
          steps {
                script {
                    runMoleculeCommandParallel(pdmdbOperatingSystems(), moleculeDir, "create")
                }
            }
         }
        stage('Prepare virtual machines') {
          steps {
                script {
                    runMoleculeCommandParallel(pdmdbOperatingSystems(), moleculeDir, "prepare")
                }
            }
         }
        stage('Install old version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "converge", env.OLDVERSIONS)
                }
            }
         }
        stage('Test old version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "verify", env.OLDVERSIONS)
                }
            }
         }
        stage('Install new version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "side-effect", env.NEWVERSIONS)
                }
            }
         }
        stage('Test new version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariableList(pdmdbOperatingSystems(), moleculeDir, "verify", env.NEWVERSIONS)
                }
            }
         }
        stage('Remove old packages') {
          steps {
                script {
                    runMoleculeCommandParallel(pdmdbOperatingSystems(), moleculeDir, "cleanup")
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(pdmdbOperatingSystems(), moleculeDir)
         }
      }
  }
}
