library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])



def operatingsystems() {
    return ['oracle-9','debian-12','ubuntu-jammy', 'ubuntu-noble', 'al-2023', 'rhel-10']
}


pipeline {
  agent {
    label 'min-bookworm-x64'
  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/ps80-binary-tarball-pro/";
    PRO = "${params.PRO}"
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
    booleanParam(
        defaultValue: false, 
        name: 'PRO'
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
          installMoleculeBookwormPXBPRO()
        }
      }
    }

    stage('Run tarball molecule') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
          script {
            moleculeParallelTest(operatingsystems(), env.MOLECULE_DIR)
          }
        }
      }
    }
  }
  post {
    always {
      script {
        moleculeParallelPostDestroy(operatingsystems(), env.MOLECULE_DIR)
      }
    }
  }
}
