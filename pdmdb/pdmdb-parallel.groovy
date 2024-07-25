library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pdmdb/pdmdb"

pipeline {
  agent {
      label 'min-bookworm-x64'
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
        string(
            defaultValue: 'pdmdb-4.2.8',
            description: 'PDMDB Version for tests',
            name: 'PDMDB_VERSION'
        )
        string(
            defaultValue: '1.6.0',
            description: 'PBM Version for tests',
            name: 'VERSION')
        choice(
            name: 'PREL_VERSION',
            description: 'Percona release version',
            choices: [
                'latest',
                '1.0-27'
            ]
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
                    currentBuild.displayName = "${params.REPO}-${params.PDMDB_VERSION}"
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
                   installMoleculeBookworm()
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
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: package tests for ${PDMDB_VERSION} repo ${REPO} finished succesfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: package tests for ${PDMDB_VERSION} repo ${REPO} failed - [${BUILD_URL}]")
        }
        always {
            script {
                moleculeParallelPostDestroy(pdmdbOperatingSystems(PDMDB_VERSION), moleculeDir)
            }
        }
    }
}
