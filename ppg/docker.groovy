library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
  agent {
  label 'min-ol-9-x64'
  }

  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: [
                'debian-12',
                'rocky-9',
                'ubuntu-jammy',
                'debian-12-arm64',
                'rocky-9-arm64',
                'ubuntu-jammy-arm64'
            ]
        )
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-docker-${env.SERVER_VERSION}-${env.PLATFORM}"
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
    stage ('Create virtual machines') {
      steps {
          script{
              moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "create", env.PLATFORM)
            }
        }
    }
    stage ('Run playbook for test') {
      steps {
          script{
              moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "converge", env.PLATFORM)
            }
        }
    }
  }
  post {
    always {
          script {
             if (env.DESTROY_ENV == "yes") {
                        moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "destroy", env.PLATFORM)
                    }
        }
    }
  }
}