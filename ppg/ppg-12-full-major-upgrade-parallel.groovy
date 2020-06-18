library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/ppg/pg-12-full-major-upgrade"

pipeline {
  agent {
      label 'micro-amazon'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
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
        choice(
            name: 'FROM_VERSION',
            description: 'From this version PPG will be updated',
            choices: ppg11Versions()
        )
        choice(
            name: 'VERSION',
            description: 'To this version PPG will be updated',
            choices: ppg12Versions()
        )
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
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
        stage('Create virtual machines') {
          steps {
                script {
                    runMoleculeCommandParallel(ppgOperatingSystems(), moleculeDir, "create")
                }
            }
         }
        stage('Prepare virtual machines') {
          steps {
                script {
                    runMoleculeCommandParallel(ppgOperatingSystems(), moleculeDir, "prepare")
                }
            }
         }
        stage('Install old version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariable(ppgOperatingSystems(), moleculeDir, "converge", "VERSION", env.FROM_VERSION)
                }
            }
         }
        stage('Test old version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariable(ppgOperatingSystems(), moleculeDir, "verify", "VERSION", env.FROM_VERSION)
                }
            }
         }
        stage('Install new version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariable(ppgOperatingSystems(), moleculeDir, "side-effect", "VERSION", env.VERSION)
                }
            }
         }
        stage('Test new version') {
          steps {
                script {
                    runMoleculeCommandParallelWithVariable(ppgOperatingSystems(), moleculeDir, "verify", "VERSION", env.VERSION)
                }
            }
         }
        stage('Remove old packages') {
          steps {
                script {
                    runMoleculeCommandParallel(ppgOperatingSystems(), moleculeDir, "cleanup")
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(ppgOperatingSystems(), moleculeDir)
         }
      }
   }
}
