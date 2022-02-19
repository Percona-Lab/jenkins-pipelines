library changelog: false, identifier: "lib@DISTPG-378", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotifcation()
{
 if ( currentBuild.currentResult == "SUCCESS" ) {
  buildSummary = "Job: ${env.JOB_NAME}\n Status: *SUCCESS*\n Build Report: ${env.BUILD_URL}CI-Build-HTML-Report"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test-debug'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\n Status: *FAILURE*\n Error description: *${CI_ERROR}* \nBuild Report :${env.BUILD_URL}CI-Build-HTML-Report"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test-debug'
 }
}

pipeline {
  agent {
  label 'min-centos-7-x64'
  }

  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: ppgOperatingSystems()
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
            defaultValue: 'ppg-11.9',
            description: 'PG version for test',
            name: 'VERSION'
         )
        choice(
            name: 'SCENARIO',
            description: 'PG scenario for test',
            choices: ppgScenarios()
        )
        string(
            defaultValue: 'main',
            description: 'base Branch for upgrade test',
            name: 'TESTING_BRANCH')
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "ppg/${SCENARIO}";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.PLATFORM}"
                }
            }
        }
    stage('Checkout') {
      steps {
            deleteDir()
            git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/ppg-testing.git'
        }
    }
    stage ('Prepare') {
      steps {
          script {
              installMolecule()
            }
        }
    }
    stage ('Create virtual machines') {
      steps {
          script{
              moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "create", env.PLATFORM)
            }
        }
    }
    stage ('Run playbook for test') {
      steps {
          script{
              moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "converge", env.PLATFORM)
            }
        }
    }
    stage ('Start testinfra tests') {
      steps {
            script{
              moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "verify", env.PLATFORM)
            }
            junit 'ppg/$SCENARIO/report.xml'
        }
    }
      stage ('Start Cleanup ') {
        steps {
             script {
               moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "cleanup", env.PLATFORM)
            }
        }
    }
  }
  post {
    always {
          script {
             moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", env.PLATFORM)
             sendSlackNotifcation()
        }
    }
  }
}
