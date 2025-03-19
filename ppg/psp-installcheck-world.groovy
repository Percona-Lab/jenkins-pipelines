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
            description: 'For which platform (OS) you want to test?',
            choices: ppgOperatingSystemsALL()
        )
        string(
            defaultValue: '17.4',
            description: 'Server PG version for test, including major and minor version, e.g 16.2, 15.5',
            name: 'VERSION'
        )
        string(
            defaultValue: 'ppg-17.4.1',
            description: 'Server PG version for test, including major and minor version, e.g 17.4.1',
            name: 'PERCONA_SERVER_VERSION'
        )
        string(
            defaultValue: 'https://github.com/percona/postgres',
            description: 'PSP repo that we want to test, we could also use forked developer repo here.',
            name: 'PSP_REPO'
        )
        string(
            defaultValue: 'TDE_REL_17_STABLE',
            description: 'PSP repo version/branch/tag to use; e.g main, TDE_REL_17_STABLE',
            name: 'PSP_BRANCH'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
        choice(
            name: 'TESTSUITE',
            description: 'Testsuite to run',
            choices: [
                'server-check-world-without-tde',
                'server-installcheck-world-with-tde',
                'tde-installcheck-only'
            ]
        )
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV'
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "psp/server_tests";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-psp-${env.VERSION}-${env.PLATFORM}"
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
