library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])


pipeline {
  agent {
      label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/pdmysql/pdps_minor_upgrade";
  }
  parameters {
        choice(
            name: 'FROM_REPO',
            description: 'From this repo will be upgraded PDPS (for minor version)',
            choices: [
                'release',
                'testing',
                'experimental'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        string(
            defaultValue: '8.0.28',
            description: 'From this version pdmysql will be updated',
            name: 'FROM_VERSION')
        string(
            defaultValue: '8.0.29',
            description: 'To this version pdmysql will be updated',
            name: 'VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: '2.3.2',
            description: 'Updated Proxysql version',
            name: 'PROXYSQL_VERSION'
         )
        string(
            defaultValue: '8.0.29',
            description: 'Updated PXB version',
            name: 'PXB_VERSION'
         )
        string(
            defaultValue: '3.4.0',
            description: 'Updated Percona Toolkit version',
            name: 'PT_VERSION'
         )
        string(
            defaultValue: '3.2.6',
            description: 'Updated Percona Orchestrator version',
            name: 'ORCHESTRATOR_VERSION'
         )
  }
  options {
          withCredentials(moleculePdpsJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/package-testing.git'
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
                    moleculeParallelTest(pdpsOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(pdpsOperatingSystems(), env.MOLECULE_DIR)
         }
      }
   }
}
