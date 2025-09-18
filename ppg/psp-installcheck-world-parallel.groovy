library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(psp_repo, psp_branch, version, testsuite, percona_server_version)
{
 if ( currentBuild.result == "SUCCESS" ) {
    buildSummary = "Job: ${env.JOB_NAME}\nPSP_Repo: ${psp_repo}\nPSP_Branch: ${psp_branch}\nVersion: ${version}\nTestsuite: ${testsuite}\nPercona_Server_Version: ${percona_server_version}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nPSP_Repo: ${psp_repo}\nPSP_Branch: ${psp_branch}\nVersion: ${version}\nTestsuite: ${testsuite}\nPercona_Server_Version: ${percona_server_version}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test'
 }
}

pipeline {
  agent {
      label 'min-ol-9-x64'
  }
  parameters {
        string(
            defaultValue: '17.6',
            description: 'Server PG version for test, including major and minor version, e.g 17.4, 17.3',
            name: 'VERSION'
        )
        string(
            defaultValue: '17.6.1',
            description: 'Server PG version for test, including major and minor version, e.g 17.6.1',
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
                'check-server',
                'check-tde',
                'check-all',
                'installcheck-world'
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
                        currentBuild.displayName = "${env.BUILD_NUMBER}-psp-${env.VERSION}"
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
        stage('Test') {
          steps {
                script {
                    moleculeParallelTestPPG(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroyPPG(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
              sendSlackNotification(env.PSP_REPO, env.PSP_BRANCH, env.VERSION, env.TESTSUITE, env.PERCONA_SERVER_VERSION)
         }
      }
   }
}
