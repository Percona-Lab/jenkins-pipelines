library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb/psmdb"

pipeline {
  agent {
      label 'min-bookworm-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
      ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
  }
  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdmdbOperatingSystems('6.0')
        )
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '4.4.8',
            description: 'PSMDB Version for tests',
            name: 'PSMDB_VERSION')
        choice(
            name: 'ENABLE_TOOLKIT',
            description: 'Enable or disable percona toolkit check',
            choices: [
                'false',
                'true'
            ]
        )
        choice(
            name: 'GATED_BUILD',
            description: 'Test private repo?',
            choices: [
                'false',
                'true'
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}"
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
    stage ('Create virtual machines') {
      steps {
          script{
              moleculeExecuteActionWithScenarioPSMDB(moleculeDir, "create", env.PLATFORM)
            }
        }
    }
    stage ('Run playbook for test') {
      steps {
          withCredentials([
             usernamePassword(credentialsId: 'PSMDB_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME'),
             usernamePassword(credentialsId: 'OIDC_ACCESS', passwordVariable: 'OIDC_CLIENT_SECRET', usernameVariable: 'OIDC_CLIENT_ID'),
             string(credentialsId: 'VAULT_TRIAL_LICENSE', variable: 'VAULT_TRIAL_LICENSE')]) {
                 script{
                     moleculeExecuteActionWithScenarioPSMDB(moleculeDir, "converge", env.PLATFORM)
                 }
            }
        }
    }
    stage ('Start testinfra tests') {
      steps {
            script{
              moleculeExecuteActionWithScenarioPSMDB(moleculeDir, "verify", env.PLATFORM)
            }
        }
    }
      stage ('Start Cleanup ') {
      steps {
          script {
              moleculeExecuteActionWithScenarioPSMDB(moleculeDir, "cleanup", env.PLATFORM)
            }
        }
    }
  }
  post {
    always {
          script {
             moleculeExecuteActionWithScenarioPSMDB(moleculeDir, "destroy", env.PLATFORM)
        }
    }
  }
}
