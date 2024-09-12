library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def sendSlackNotification(pgsm_repo, pgsm_branch, pgsm_package_install, version, package_repo, major_repo)
{
 if ( currentBuild.result == "SUCCESS" ) {
    buildSummary = "Job: ${env.JOB_NAME}\nPGSM_Repo: ${pgsm_repo}\nPGSM_Branch: ${pgsm_branch}\nPGSM_install_from_package: ${pgsm_package_install}\nVersion: ${version}\nPackage_Repo: ${package_repo}\nMajor_Repo: ${major_repo}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
  slackSend color : "good", message: "${buildSummary}", channel: '#postgresql-test'
 }
 else {
  buildSummary = "Job: ${env.JOB_NAME}\nPGSM_Repo: ${pgsm_repo}\nPGSM_Branch: ${pgsm_branch}\nPGSM_install_from_package: ${pgsm_package_install}\nVersion: ${version}\nPackage_Repo: ${package_repo}\nMajor_Repo: ${major_repo}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
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
            defaultValue: 'https://github.com/percona/pg_stat_monitor.git',
            description: 'PGSM repo that we want to test, we could also use forked developer repo here.',
            name: 'PGSM_REPO'
        )
        string(
            defaultValue: 'main',
            description: 'PGSM repo version/branch/tag to use; e.g main, 2.0.5',
            name: 'PGSM_BRANCH'
        )
        string(
            defaultValue: 'ppg-16.4',
            description: 'Server PG version for test, including major and minor version, e.g ppg-16.2, ppg-15.5',
            name: 'VERSION'
        )
        booleanParam(
            name: 'PGSM_PACKAGE_INSTALL',
            description: "If Selected, then pgsm rpm/deb will be installed in server that is shipped with ppg mentioned in VERSION above. Build sources will only be used for regression and binary will not be installed into server from built sources. If UnSelected, then no pgsm rpm/deb will be installed into the server, and pgsm binary from the built sources will be installed into the server."
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
            description: "Enable to use major (ppg-16) repo instead of ppg-16.2"
        )
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "pg_stat_monitor/pgsm";
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                    script {
                        currentBuild.displayName = "${env.BUILD_NUMBER}-pgsm-${env.VERSION}"
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
              sendSlackNotification(env.PGSM_REPO, env.PGSM_BRANCH, env.PGSM_PACKAGE_INSTALL, env.VERSION, env.REPO, env.MAJOR_REPO)
         }
      }
   }
}
