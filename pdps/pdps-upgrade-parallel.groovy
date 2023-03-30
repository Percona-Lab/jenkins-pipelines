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
            description: 'From this repo will be upgraded PDPS (for minor version).',
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
            defaultValue: '8.0.31-23',
            description: 'From this version pdmysql will be updated. Possible values are with and without percona release: 8.0.31 OR 8.0.31-23',
            name: 'FROM_VERSION')
        string(
            defaultValue: '8.0.32-24',
            description: 'To this version pdmysql will be updated. Possible values are with and without percona release: 8.0.32 OR 8.0.32-24',
            name: 'VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: '2.4.7',
            description: 'Updated Proxysql version',
            name: 'PROXYSQL_VERSION'
         )
        string(
            defaultValue: '8.0.32-25',
            description: 'Updated PXB version. Possible values are with and without percona release: 8.0.32 OR 8.0.32-25',
            name: 'PXB_VERSION'
         )
        string(
            defaultValue: '3.5.1',
            description: 'Updated Percona Toolkit version',
            name: 'PT_VERSION'
         )
        string(
            defaultValue: '3.2.6-8',
            description: 'Updated Percona Orchestrator version',
            name: 'ORCHESTRATOR_VERSION'
         )
  }
  options {
          withCredentials(moleculePdpsJenkinsCreds())
          disableConcurrentBuilds()
  }
      stages {
        stage('Check version param and checkout') {
            steps {
                deleteDir()
                checkOrchVersionParam()
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
