library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(scenario)
{
 if ( currentBuild.result == "SUCCESS" ) {
  buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nScenario: ${scenario}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test'
 }
}

pipeline {
  agent {
      label 'min-centos-7-x64'
  }
  parameters {
        choice(
            name: 'FROM_REPO',
            description: 'From this repo will be upgraded PPG',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: 'ppg-11.9',
            description: 'From this version PPG will be updated',
            name: 'FROM_VERSION'
        )
        string(
            defaultValue: 'ppg-11.9',
            description: 'To this version PPG will be updated',
            name: 'VERSION'
        )
        choice(
            name: 'SCENARIO',
            description: 'PG version for test',
            choices: ppgUpgradeScenarios()
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
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
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}"
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
        stage('Test') {
          steps {
                script {
                    moleculeParallelTest(ppgOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(ppgOperatingSystems(), env.MOLECULE_DIR)
              sendSlackNotification(env.SCENARIO)
         }
      }
   }
}
