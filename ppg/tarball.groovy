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
            choices: ppgOperatingSystemsALL()
        )
        choice(
            name: 'SSL_VERSION',
            description: 'SSL version to use',
            choices: [
                '1',
                '3'
            ]
        )
        string(
            defaultValue: 'ppg-16.3',
            description: 'PG version for test',
            name: 'VERSION'
        )
        string(
            defaultValue: 'https://downloads.percona.com/downloads/TESTING/pg_tarballs-16.3/percona-postgresql-16.3-ssl1.1-linux-x86_64.tar.gz',
            description: 'URL for tarball.',
            name: 'TARBALL_URL'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV'
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "ppg/pg-tarballs";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-${env.PLATFORM}"
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
             if (env.DESTROY_ENV == "yes") {
                        moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", env.PLATFORM)
                    }
        }
    }
  }
}
