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
    MOLECULE_DIR = "molecule/ps80-binary-tarball-pro/";
  }
  parameters {
    string(
      name: 'PS_VERSION', 
      defaultValue: '8.0.36-28', 
      description: 'PS full version'
    )
    string(
      name: 'PS_REVISION', 
      defaultValue: '47601f19', 
      description: 'PS revision'
    )
    choice(
      name: "PRO",
      choices: ["yes",],
      description: "Is this pro test?"
    )
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
    withCredentials(moleculePdpsJenkinsCreds())
    disableConcurrentBuilds()
  }

  stages {
    stage('Set build name'){
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PS_VERSION}-${env.PRO}"
          currentBuild.description = "${env.PS_REVISION}-${env.TESTING_BRANCH}-${env.TESTING_GIT_ACCOUNT}"
        }
      }
    }
    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
      }
    }
    stage ('Prepare') {
      steps {
        script {
          installMolecule()
        }
      }
    }

    stage('Run tarball molecule') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
          script {
            moleculeParallelTest(ps80ProOperatingSystems(), env.MOLECULE_DIR)
          }
        }
      }
    }
  }
  post {
    always {
      script {
        moleculeParallelPostDestroy(ps80ProOperatingSystems(), env.MOLECULE_DIR)
      }
    }
  }
}
