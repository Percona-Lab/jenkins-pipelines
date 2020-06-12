library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/ppg/pg-12-full-major-upgrade"

pipeline {
  agent {
     label 'micro-amazon'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: ppgOperatingSystems()
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
        choice(
            name: 'FROM_VERSION',
            description: 'From this version PPG will be updated',
            choices: ppg11Versions()
        )
        choice(
            name: 'VERSION',
            description: 'To this version PPG will be updated',
            choices: ppg12Versions()
        )
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PLATFORM}"
                }
            }
        }
    stage('Checkout') {
      steps {
            deleteDir()
            git poll: false, branch: 'master', url: 'https://github.com/Percona-QA/package-testing.git'
        }
    }
    stage ('Prepare') {
      steps {
          script {
              installMolecule()
            }
        }
    }
    stage ('Create virtual machines') {
      steps {
          script{
              moleculeExecuteActionWithScenario(moleculeDir, "create", env.PLATFORM)
            }
        }
    }
    stage ('Prepare VM for test') {
      steps {
          script{
              moleculeExecuteActionWithScenario(moleculeDir, "prepare", env.PLATFORM)
            }
        }
    }
    stage ('Run playbook for test with old version') {
      steps {
          script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "converge", env.PLATFORM, "VERSION", env.FROM_VERSION)
            }
        }
    }
    stage ('Start testinfra tests for old version') {
      steps {
            script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "verify", env.PLATFORM, "VERSION", env.FROM_VERSION)
            }
            junit "molecule/ppg/pg-12-full-major-upgrade/molecule/${PLATFORM}/report.xml"
        }
    }
    stage ('Run playbook for test with new version') {
      steps {
          script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "side-effect", env.PLATFORM, "VERSION", env.VERSION)
            }
        }
    }
    stage ('Start testinfra tests for new version') {
      steps {
            script{
              moleculeExecuteActionWithVariableAndScenario(moleculeDir, "verify", env.PLATFORM, "VERSION", env.VERSION)
            }
            junit "molecule/ppg/pg-12-full-major-upgrade/molecule/${PLATFORM}/report.xml"
        }
    }
      stage ('Start Cleanup ') {
        steps {
             script {
               moleculeExecuteActionWithScenario(moleculeDir, "cleanup", env.PLATFORM)
            }
        }
    }
  }
  post {
    always {
          script {
             moleculeExecuteActionWithScenario(moleculeDir, "destroy", env.PLATFORM)
        }
    }
  }
}
