library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/ppg/pg-12-with-vanila-components"
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
                'release',
                'experimental'
            ]
        )
        choice(
            name: 'VERSION',
            description: 'PG version for test',
            choices: [
                'ppg-12.2',
                'ppg-12.3'
            ]
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
        stage('Test') {
          steps {
                script {
                    moleculeParallelTest(ppgOperatingSystems(), moleculeDir)
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
