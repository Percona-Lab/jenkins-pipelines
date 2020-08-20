library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/pbm"

pipeline {
  agent {
      label 'micro-amazon'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
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
            defaultValue: 'psmdb42',
            description: 'PSMDB for testing',
            name: 'psmdb_to_test')
  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
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
                    moleculeParallelTest(pdmdbOperatingSystems(), moleculeDir)
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
