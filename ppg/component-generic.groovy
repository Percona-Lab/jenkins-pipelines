library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(componentName, ppgVersion, componentVersion, osArchitecture)
{
 if ( currentBuild.result == "SUCCESS" ) {
  buildSummary = "Job: ${env.JOB_NAME}\nComponent: ${componentName}\nComponent Version: ${componentVersion}\nPPG Version: ${ppgVersion}\nArchitecture: ${osArchitecture}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nComponent: ${componentName}\nComponent Version: ${componentVersion}\nPPG Version: ${ppgVersion}\nArchitecture: ${osArchitecture}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test'
 }
}

pipeline {
  agent {
  label 'min-ol-9-x64'
  }

  parameters {
        choice(
            name: 'REPO',
            description: 'PPG repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: 'main',
            description: 'Branch for tests',
            name: 'TEST_BRANCH'
        )
        choice(
            name: 'PLATFORM',
            description: 'For what platform architecture (amd or arm) need to test',
            choices: ppgArchitectures()
        )
        string(
            defaultValue: 'https://github.com/pgaudit/pgaudit.git',
            description: 'Component repo for test',
            name: 'COMPONENT_REPO'
        )
        string(
            defaultValue: 'master',
            description: 'Component version for test',
            name: 'COMPONENT_VERSION'
        )
        string(
            defaultValue: 'ppg-16.4',
            description: 'PPG version for test',
            name: 'VERSION'
        )
        choice(
            name: 'PRODUCT',
            description: 'Product to test',
            choices: ['pg_contrib',
                      'pg_audit',
                      'pg_repack',
                      'patroni',
                      'pgbackrest',
                      'pgpool',
                      'postgis',
                      'pgaudit13_set_user',
                      'pgbadger',
                      'pgbouncer',
                      'wal2json']
        )
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV'
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "${PRODUCT}/setup";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-${env.PLATFORM}-${env.PRODUCT}"
                }
            }
        }
    stage('Checkout') {
      steps {
            deleteDir()
            git poll: false, branch: env.TEST_BRANCH, url: 'https://github.com/Percona-QA/ppg-testing.git'
        }
    }
    stage ('Prepare') {
      steps {
          script {
              installMoleculePython39()
            }
        }
    }
    stage ('Run tests') {
      steps {
          script{
              moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "test", env.PLATFORM)
            }
        }
    }
  }
  post {
    always {
          script {
             if (env.DESTROY_ENV == "yes") {
             moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", env.PLATFORM)
             }
             sendSlackNotification(env.PRODUCT, env.VERSION, env.COMPONENT_VERSION, env.PLATFORM)
        }
    }
  }
}
