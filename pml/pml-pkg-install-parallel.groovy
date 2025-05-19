library changelog: false, identifier: "lib@PML-134", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pml/install"

pipeline {
  agent {
      label 'min-bookworm-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
    choice(
        name: 'psmdb_version',
        description: 'Version of PSMDB PML will interact with',
        choices: [
            '6.0',
            '7.0',
            '8.0'
        ]
    )
    string(
            name: 'TESTING_BRANCH',
            description: 'Branch for testing repository',
            defaultValue: 'PML-134')
    string(
            name: 'PML_BRANCH',
            description: 'PML Branch for testing',
            defaultValue: 'main')
    string(
            name: 'GO_VERSION',
            description: 'Version of Golang used',
            defaultValue: '1.24.1')
    string(
          name: 'SSH_USER',
          description: 'User for debugging',
          defaultValue: 'none')
    string(
          name: 'SSH_PUBKEY',
          description: 'User ssh public key for debugging',
          defaultValue: 'none')
  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Checkout') {
            steps {
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
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'MONGO_REPO_TOKEN')]) {
                        try {
                            moleculeParallelTest(pdmdbOperatingSystems("psmdb-70"), moleculeDir)
                        } catch (e) {
                            echo "Converge stage failed"
                            throw e
                        }
                    }
                }
            }
         }
    }
//    post {
//        success {
//            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: package tests for PBM ${VERSION} repo ${install_repo} finished succesfully - [${BUILD_URL}]")
//        }
//        failure {
//            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: package tests for PBM ${VERSION} repo ${install_repo} failed - [${BUILD_URL}]")
//        }
//        always {
//            script {
//                moleculeParallelPostDestroy(pdmdbOperatingSystems(psmdb_to_test), moleculeDir)
//            }
//        }
//    }
}
