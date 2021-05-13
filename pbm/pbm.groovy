library changelog: false, identifier: "lib@add_molecule_deps", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "molecule/pbm/install"

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
            choices: pdmdbOperatingSystems()
        )
        choice(
            name: 'install_repo',
            description: 'Repo for testing',
            choices: [
                'testing',
                'main',
                'experimental'
            ]
        )
        string(
            defaultValue: 'psmdb-42',
            description: 'PSMDB for testing. Valid values: psmdb-4*, psmdb-36',
            name: 'psmdb_to_test')
        string(
            defaultValue: '1.3.4',
            description: 'PBM Version for tests',
            name: 'VERSION')
  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
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
            sh """
                sudo yum install -y gcc python3-pip python3-devel
                sudo yum remove ansible -y
                python3 -m venv virtenv
                . virtenv/bin/activate
                python3 -m pip install --upgrade molecule==3.0.2 testinfra pytest molecule-ec2==0.2 ansible==2.9.6
            """
        }
    }
    stage ('Create virtual machines') {
      steps {
          script{
              moleculeExecuteActionWithScenario(moleculeDir, "create", env.PLATFORM)
            }
        }
    }
    stage ('Run playbook for test') {
      steps {
          script{
              moleculeExecuteActionWithScenario(moleculeDir, "converge", env.PLATFORM)
            }
        }
    }
    stage ('Start testinfra tests') {
      steps {
            script{
              moleculeExecuteActionWithScenario(moleculeDir, "verify", env.PLATFORM)
            }
            junit "${moleculeDir}molecule/${PLATFORM}/report.xml"
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
