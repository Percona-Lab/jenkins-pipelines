library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(pdp_repo, pdp_branch, version, testsuite, with_tde_heap, access_method, change_tde_branch, tde_branch)
{
 if ( currentBuild.result == "SUCCESS" ) {
    buildSummary = "Job: ${env.JOB_NAME}\nPDP_Repo: ${pdp_repo}\nPDP_Branch: ${pdp_branch}\nVersion: ${version}\nTestsuite: ${testsuite}\nWith_TDE_Heap: ${with_tde_heap}\nAccess_Method: ${access_method}\nChange_TDE_Branch: ${change_tde_branch}\nTDE_Branch: ${tde_branch}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nPDP_Repo: ${pdp_repo}\nPDP_Branch: ${pdp_branch}\nVersion: ${version}\nTestsuite: ${testsuite}\nWith_TDE_Heap: ${with_tde_heap}\nAccess_Method: ${access_method}\nChange_TDE_Branch: ${change_tde_branch}\nTDE_Branch: ${tde_branch}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test'
 }
}

pipeline {
  agent {
      label 'min-ol-9-x64'
  }
  parameters {
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
        choice(
            name: 'TESTSUITE',
            description: 'Testsuite to run',
            choices: [
                'installcheck',
                'installcheck-world'
            ]
        )
        choice(
            name: 'ACCESS_METHOD',
            description: 'Server access method to use',
            choices: [
                'heap',
                'tde_heap',
                'tde_heap_basic'
            ]
        )
        booleanParam(
            name: 'WITH_TDE_HEAP',
            description: "Do you want TDE_HEAP build and test as part of this run?"
        )
        booleanParam(
            name: 'CHANGE_TDE_BRANCH',
            description: "Do you want to change TDE branch to other than default one given in PDP? It will only work if WITH_TDE_HEAP option is enabled."
        )
        string(
            defaultValue: 'main',
            description: 'pg_tde branch to use. It will only work if both options, WITH_TDE_HEAP and CHANGE_TDE_BRANCH, are enabled.',
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
      MOLECULE_DIR = "pdp/server_tests";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-pdp-${env.VERSION}"
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
                    moleculeParallelTest(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(ppgOperatingSystemsALL(), env.MOLECULE_DIR)
              sendSlackNotification(env.PDP_REPO, env.PDP_BRANCH, env.VERSION, env.TESTSUITE, env.WITH_TDE_HEAP, env.ACCESS_METHOD, env.CHANGE_TDE_BRANCH, env.TDE_BRANCH)
         }
      }
   }
}
