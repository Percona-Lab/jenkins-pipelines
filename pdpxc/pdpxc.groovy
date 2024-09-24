library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])


pipeline {
  agent {
  label 'min-bookworm-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/pdmysql/${SCENARIO}";
  }
  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdpxcOperatingSystems()
        )
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.35-27',
            description: 'PXC version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: '',
            description: 'PXC revision for test. Empty by default (not checked).',
            name: 'PXC_REVISION'
        )
        string(
            defaultValue: '8.0.35-30',
            description: 'PXB version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '2.5.5',
            description: 'Proxysql version for test',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '2.8.5',
            description: 'HAProxy version for test',
            name: 'HAPROXY_VERSION'
        )
        string(
            defaultValue: '3.6.0',
            description: 'Percona toolkit version for test',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '1.0',
            description: 'replication-manager.sh version',
            name: 'REPL_MANAGER_VERSION'
        )
        choice(
            name: 'SCENARIO',
            description: 'Scenario for test',
            choices: pdpxcScenarios()
        )
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'Percona-QA',
            description: 'Git account for package-testing repository',
            name: 'TESTING_GIT_ACCOUNT'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (pdpxc-8.0) repo instead of pdpxc-8.0.XX"
        )
        choice(
            name: 'DESTROY_ENV',
            description: 'Destroy VM after tests',
            choices: [
                'yes',
                'no'
            ]
        )
  }
  options {
          withCredentials(moleculePdpxcJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}-${env.SCENARIO}"
                    currentBuild.description = "${env.VERSION}-${env.REPO}-${env.TESTING_BRANCH}-${env.MAJOR_REPO}"
                }
            }
        }
    stage('Checkout') {
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
            junit "${MOLECULE_DIR}/report.xml"
        }
    }
  }
}
