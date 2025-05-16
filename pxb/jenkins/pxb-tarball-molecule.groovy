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
    MOLECULE_DIR = "molecule/pxb-rhel-binary-tarball/";
    PXB_VERSION = "${params.PXB_VERSION}";
    install_repo = "${params.install_repo}";
    TESTING_BRANCH = "${params.TESTING_BRANCH}";
    TESTING_GIT_ACCOUNT = "${params.TESTING_GIT_ACCOUNT}";
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
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXB_VERSION}-${env.PXB_REVISION}"
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
            moleculeParallelTest(pxbTarball(), env.MOLECULE_DIR)
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
