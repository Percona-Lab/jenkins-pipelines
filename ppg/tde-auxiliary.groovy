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
            defaultValue: '18.1',
            description: 'Server PG version for test, including major and minor version, e.g 17.4, 17.3',
            name: 'VERSION'
        )
        string(
            defaultValue: '18.1.1',
            description: 'Server PG version for test, including major and minor version, e.g 17.6.1',
            name: 'PERCONA_SERVER_VERSION'
        )
        string(
            defaultValue: 'https://github.com/percona/postgres',
            description: 'PSP repo that we want to test, we could also use forked developer repo here.',
            name: 'PSP_REPO'
        )
        string(
            defaultValue: 'PSP_REL_18_STABLE',
            description: 'PSP repo version/branch/tag to use; e.g main, TDE_REL_17_STABLE',
            name: 'PSP_BRANCH'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
        choice(
            name: 'IO_METHOD',
            description: 'io_method to use for the server (applicable to pg-18 and onwards only).',
            choices: [
                'worker',
                'sync',
                'io_uring'
            ]
        )
        string(
            defaultValue: 'https://github.com/percona/pg_tde.git',
            description: 'pg_tde repo that we want to test, we could also use forked developer repo here.',
            name: 'TDE_REPO'
        )
        string(
            defaultValue: 'release-2.1',
            description: 'TDE repo version/branch/tag to use; e.g main, release-2.1',
            name: 'TDE_BRANCH'
        )
        booleanParam(
            name: 'SKIP_TESTCASE',
            description: "Enable if want to skip some test cases."
        )string(
            name: 'TESTCASE_TO_SKIP',
            defaultValue: 'pg_receivewal.sh,pg_tde_change_database_key_provider_vault_v2.sh',
            description: '''If SKIP_TESTCASE option is enabled, then testcase given here will be ignored. 
        Values should be comma separated. For example:
        pg_receivewal.sh,pg_tde_change_database_key_provider_vault_v2.sh'''
        )
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV'
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "pg_tde/auxiliary";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-tde-auxiliary-${env.VERSION}-${env.PLATFORM}"
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
    stage ('Start testinfra tests') {
      steps {
            script{
              moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "verify", env.PLATFORM)
            }
        }
    }
      stage ('Start Cleanup ') {
        steps {
             script {
               moleculeExecuteActionWithScenarioPPG(env.MOLECULE_DIR, "cleanup", env.PLATFORM)
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
