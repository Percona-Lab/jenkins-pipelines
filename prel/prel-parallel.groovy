library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/prel"
def operatingSystems = ['centos-7', 'centos-8', 'debian-9', 'debian-10', 'debian-11', 'ubuntu-xenial', 'ubuntu-bionic', 'ubuntu-focal']

pipeline {
  agent {
      label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
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
                    moleculeParallelTest(operatingSystems, moleculeDir)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(operatingSystems, moleculeDir)
         }
      }
   }
}
