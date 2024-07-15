library changelog: false, identifier: "lib@telem_ph1", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/eleo007/jenkins-pipelines.git'
])

pipeline {
  agent {
      label 'min-bookworm-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/telemetry/${SCENARIO}";

  }
  parameters {
        string(
            defaultValue: '1.0.1',
            description: 'Telemetry Agent version',
            name: 'VERSION'
        )
        string(
            defaultValue: 'ee4825e',
            description: 'Telemetry Agent revision',
            name: 'REVISION'
        )
        choice(
            name: 'SCENARIO',
            description: 'Scenario for test',
            choices: ['telemetry-ps',]
        )
        string(
            defaultValue: 'telemetry_ph1',
            description: 'Branch for package-testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'eleo007',
            description: 'Git account for package-testing repository',
            name: 'TESTING_GIT_ACCOUNT'
        )
  }
  options {
          withCredentials(moleculePdpsJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}"
                    currentBuild.description = "${env.VERSION}"
                }
            }
        }
        stage('Check version param and checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
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
                    moleculeParallelTest(pdpsOperatingSystems() + ['rocky-8', 'rocky-9'], env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(pdpsOperatingSystems() + ['rocky-8', 'rocky-9'], env.MOLECULE_DIR)
         }
      }
   }
}

List operating_systems = []
operating_systems = pdpsOperatingSystems() + ['rocky-8', 'rocky-9']
