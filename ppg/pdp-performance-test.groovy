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
            choices: ['ol-9', 'debian-12', 'ubuntu-noble', 'ol-9-arm', 'debian-12-arm', 'ubuntu-noble-arm']
        )
        string(
            defaultValue: 'ppg-17.0',
            description: 'Server PG version for test, including major and minor version, e.g ppg-16.2, ppg-15.5',
            name: 'VERSION'
        )
        string(
            defaultValue: 'https://github.com/percona/postgres',
            description: 'PDP repo that we want to test, we could also use forked developer repo here.',
            name: 'PDP_REPO'
        )
        string(
            defaultValue: 'TDE_REL_17_STABLE',
            description: 'PDP repo version/branch/tag to use; e.g main, TDE_REL_17_STABLE',
            name: 'PDP_BRANCH'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for ppg-testing testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: '100',
            description: 'Scale value for pgbench.',
            name: 'PGBENCH_SCALE'
        )
        string(
            defaultValue: '600',
            description: 'Duration value for pgbench.',
            name: 'PGBENCH_DURATION'
        )
        string(
            defaultValue: '100',
            description: 'Scale value for pgbench.',
            name: 'PGBENCH_CLIENTS'
        )
        string(
            defaultValue: '30',
            description: 'Scale value for pgbench.',
            name: 'PGBENCH_THREADS'
        )
        booleanParam(
            name: 'RUN_HEAP',
            defaultValue: true,
            description: "Do you want to test performance using heap access method?"
        )
        booleanParam(
            name: 'RUN_TDE_HEAP',
            description: "Do you want to test performance using tde_heap access method?"
        )
        booleanParam(
            name: 'RUN_TDE_HEAP_BASIC',
            description: "Do you want to test performance using tde_heap_basic access method?"
        )
        booleanParam(
            name: 'CHANGE_TDE_BRANCH',
            description: "Do you want to change TDE branch to other than default one given in PDP?"
        )
        string(
            defaultValue: 'main',
            description: 'pg_tde branch to use. Will only apply if CHANGE_TDE_BRANCH is selected',
            name: 'TDE_BRANCH'
        )
        string(
            defaultValue: 'yes',
            description: 'Destroy VM after tests',
            name: 'DESTROY_ENV'
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "pdp/performance_tests";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-ppt-${env.VERSION}-${env.PLATFORM}"
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
