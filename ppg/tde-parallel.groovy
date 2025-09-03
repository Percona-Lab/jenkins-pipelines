library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(psp_repo, psp_branch, version, package_repo, major_repo)
{
 if ( currentBuild.result == "SUCCESS" ) {
    buildSummary = "Job: ${env.JOB_NAME}\nPSP_Repo: ${psp_repo}\nPSP_Branch: ${psp_branch}\nVersion: ${version}\nPackage_Repo: ${package_repo}\nMajor_Repo: ${major_repo}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nPSP_Repo: ${psp_repo}\nPSP_Branch: ${psp_branch}\nVersion: ${version}\nPackage_Repo: ${package_repo}\nMajor_Repo: ${major_repo}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
  slackSend color : "danger", message: "${buildSummary}", channel: '#postgresql-test'
 }
}

pipeline {
  agent {
      label 'min-ol-9-x64'
  }
  parameters {
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
            defaultValue: 'ppg-17.6',
            description: 'Server PG version for test, including major and minor version, e.g ppg-17.4, ppg-17.3',
            name: 'VERSION'
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
            description: "Enable to use major (ppg-17) repo instead of ppg-17.6"
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
                        currentBuild.displayName = "${env.BUILD_NUMBER}-pg_tde-${env.VERSION}"
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
              sendSlackNotification(env.PSP_REPO, env.PSP_BRANCH, env.VERSION, env.REPO, env.MAJOR_REPO)
         }
      }
   }
}
