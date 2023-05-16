library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pdmdb/pdmdb-setup"

pipeline {
  agent {
      label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
    parameters {
        string(
            defaultValue: 'pdmdb-4.2.8',
            description: 'PDMDB Version for tests',
            name: 'PDMDB_VERSION')
        string(
            defaultValue: '1.6.0',
            description: 'PBM Version for tests',
            name: 'VERSION')
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
        stage('Test') {
            steps {
                script {
                    moleculeParallelTest(pdmdbOperatingSystems(PDMDB_VERSION), moleculeDir)
                }
            }
         }
  }
    post {
    always {
      script {
          moleculeParallelPostDestroy(pdmdbOperatingSystems(PDMDB_VERSION), moleculeDir)
         }
      }
   }
}
