library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def PXB80skipOSPRO() {
  return ['debian-11', 'oracle-8', 'rhel-8','ubuntu-focal']
}

def PXB80skipOSNONPRO() {
  return ['al-2023']
}


def noSkip() {
  return []
}


pipeline {
  agent {

    label 'min-bookworm-x64'

  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pxb-binary-tarball/";
    PXB_VERSION = "${params.PXB_VERSION}";
    install_repo = "${params.TESTING_REPO}";
    TESTING_BRANCH = "${params.TESTING_BRANCH}";
    TESTING_GIT_ACCOUNT = "${params.TESTING_GIT_ACCOUNT}";
    REPO_TYPE = "${params.REPO_TYPE}";
  }
  parameters {
    string(
      name: 'PXB_VERSION', 
      defaultValue: '8.0.30-22.1', 
      description: 'PXB full version'
    )
    //string(
    //  name: 'install_repo',
    //  defaultValue: 'main',
    //  description: 'Repository to install PXB from'
    //)
    string(
      defaultValue: 'master',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
    string(
      defaultValue: 'Percona-QA',
      description: 'Git account for package-testing repository',
      name: 'TESTING_GIT_ACCOUNT'
    )
    choice(
        choices: ['NORMAL', 'PRO'],
        description: 'Choose the product to test',
        name: 'REPO_TYPE'
    )
    choice(
        choices: ['main', 'testing'],
        description: 'Choose the product to test',
        name: 'TESTING_REPO'
    )
  }
  options {
    //withCredentials(moleculepxcJenkinsCreds())
    withCredentials(moleculepxbJenkinsCreds())
    disableConcurrentBuilds()
  }

  stages {
    stage('Set build name'){
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXB_VERSION}-${params.REPO_TYPE}-${params.TESTING_REPO}"
          currentBuild.description = "${env.PXB_REVISION}"
        }
      }
    }
    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
        echo "PXB_VERSION is ${env.PXB_VERSION}"
      }
    }
    stage ('Prepare') {
      steps {
        script {
          installMoleculeBookworm()
        }
      }
    }

    stage('Run tarball molecule') {
      steps {
          script {
              withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {

                if (params.REPO_TYPE == 'PRO'){
                  moleculeParallelTestSkip(pxbTarball(), env.MOLECULE_DIR, PXB80skipOSPRO())
                } else {
                  moleculeParallelTestSkip(pxbTarball(), env.MOLECULE_DIR, PXB80skipOSNONPRO())
                }
              }
          }
      }
    }
  }
  post {
    always {
      script {
        //archiveArtifacts artifacts: "*.tar.gz" , followSymlinks: false
        moleculeParallelPostDestroy(pxbTarball(), env.MOLECULE_DIR)
      }
    }
  }
}
