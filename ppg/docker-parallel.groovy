library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(repo,version,tag)
{
 if ( currentBuild.result == "SUCCESS" ) {
  buildSummary = "Job: ${env.JOB_NAME}\nVersion: ${version}\nRepo: ${repo}\nDocker-tag: ${tag}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nVersion: ${version}\nRepo: ${repo}\nDocker-tag: ${tag}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test'
 }
}

pipeline {
  agent {
      label 'min-ol-9-x64'
  }
  parameters {
        string(
            defaultValue: '17.6',
            description: 'TAG of the docker to test. For example, 16, 16.1, 16.1-multi.',
            name: 'DOCKER_TAG'
        )
        string(
            defaultValue: '17.6',
            description: 'Docker PG version to test, including both major and minor version. For example, 15.4.',
            name: 'SERVER_VERSION'
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
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SERVER_VERSION}"
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
                   installMoleculePython39()
             }
           }
        }
        stage('Test') {
          steps {
                script {
                    moleculeParallelTestPPG(['rocky-9', 'debian-12', 'ubuntu-jammy', 'rocky-9-arm64', 'debian-12-arm64', 'ubuntu-jammy-arm64'], env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              if (env.DESTROY_ENV == "yes") {
                    moleculeParallelPostDestroyPPG(['rocky-9', 'debian-12', 'ubuntu-jammy', 'rocky-9-arm64', 'debian-12-arm64', 'ubuntu-jammy-arm64'], env.MOLECULE_DIR)
              }
              sendSlackNotification(env.REPOSITORY, env.SERVER_VERSION, env.DOCKER_TAG)
         }
      }
   }
}
