library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
  agent {
      label 'micro-amazon'
  }
  parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        string(
            defaultValue: 'ppg-11.9',
            description: 'PG version for test',
            name: 'VERSION'
        )
        choice(
            name: 'SCENARIO',
            description: 'PG version for test',
            choices: ppgScenarios()
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/ppg/${SCENARIO}";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.PLATFORM}"
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
        stage('Test') {
          steps {
                script {
                    moleculeParallelTest(ppgOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(ppgOperatingSystems(), env.MOLECULE_DIR)
         }
      }
   }
}
