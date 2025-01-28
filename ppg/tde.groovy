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
            name: 'REPO',
            description: 'Packages Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: 'https://github.com/Percona-Lab/pg_tde.git',
            description: 'TDE repo that we want to test, we could also use forked developer repo here.',
            name: 'TDE_REPO'
        )
        string(
            defaultValue: 'main',
            description: 'TDE repo version/branch/tag to use; e.g main, 2.0.5',
            name: 'TDE_BRANCH'
        )
        string(
            defaultValue: 'ppg-17.0',
            description: 'Server PG version for test, including major and minor version, e.g ppg-16.2, ppg-15.5',
            name: 'VERSION'
        )
        booleanParam(
            name: 'TDE_PACKAGE_INSTALL',
            description: "Select if want to install TDE using native package from repo.percona.com, one shipped with VERSION above."
        )
        string(
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (ppg-16) repo instead of ppg-16.2"
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "pg_tde/tde";
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
