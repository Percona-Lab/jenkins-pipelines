library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
  agent {
      label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/pdmysql/${SCENARIO}";

  }
  parameters {
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
            defaultValue: '8.0.29',
            description: 'PDMYSQL version for test',
            name: 'VERSION'
         )
        string(
            defaultValue: '2.3.2',
            description: 'Proxysql version for test',
            name: 'PROXYSQL_VERSION'
         )
        string(
            defaultValue: '8.0.29',
            description: 'PXB version for test',
            name: 'PXB_VERSION'
         )
        string(
            defaultValue: '3.4.0',
            description: 'Percona toolkit version for test',
            name: 'PT_VERSION'
         )
        string(
            defaultValue: '3.2.6',
            description: 'Percona orchestrator version for test',
            name: 'ORCHESTRATOR_VERSION'
         )
        choice(
            name: 'SCENARIO',
            description: 'PDMYSQL scenario for test',
            choices: pdpsScenarios()
        )
        string(
            defaultValue: 'master',
            description: 'Branch for package-testing repository',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: 'master',
            description: 'Tests will be run from branch of  https://github.com/percona/orchestrator',
            name: 'ORCHESTRATOR_TESTS_VERSION'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (pdps-8.0) repo instead of pdps-8.0.XX"
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
                        currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}-${env.MAJOR_REPO}"
                    }
                }
            }
        stage('Check version param and checkout') {
            steps {
                deleteDir()
                checkOrchVersionParam()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/package-testing.git'
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
                    moleculeParallelTest(pdpsOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(pdpsOperatingSystems(), env.MOLECULE_DIR)
         }
      }
   }
}
