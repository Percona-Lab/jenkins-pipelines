library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(repo,version)
{
 if ( currentBuild.result == "SUCCESS" ) {
  buildSummary = "Job: ${env.JOB_NAME}\nVersion: ${version}\nRepo: ${repo}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nVersion: ${version}\nRepo: ${repo}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test'
 }
}


pipeline {
  agent {
      label 'min-ol-8-x64'
  }
  parameters {
        string(
            defaultValue: '16.0',
            description: 'Docker PG version for test. For example, 15.4.',
            name: 'VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        choice(
            name: 'REPOSITORY',
            description: 'Docker hub repository to use for docker images.',
            choices: [
                'percona',
                'perconalab'
            ]
        )
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV')
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "docker/ppg-docker";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}"
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
                   installMoleculePPG()
             }
           }
        }
        stage('Test') {
          steps {
                script {
                    moleculeParallelTest(['ol-9', 'debian-12', 'ubuntu-jammy'], env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              if (env.DESTROY_ENV == "yes") {
                    moleculeParallelPostDestroy(['ol-9', 'debian-12', 'ubuntu-jammy'], env.MOLECULE_DIR)
              }
              sendSlackNotification(env.REPOSITORY, env.VERSION)
         }
      }
   }
}
