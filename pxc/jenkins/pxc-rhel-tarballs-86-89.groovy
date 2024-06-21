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
    MOLECULE_DIR = "molecule/pxc-rhel-binary-tarball/";
    PXC_VERSION = "${params.PXC_VERSION}";
    PXC_REVISION = "${params.PXC_REVISION}";
    WSREP_VERSION = "${params.WSREP_VERSION}";
    PXC57_PKG_VERSION = "${params.PXC57_PKG_VERSION}";
    BUILD_TYPE_MINIMAL = "${params.BUILD_TYPE_MINIMAL}";
    TESTING_BRANCH = "${params.TESTING_BRANCH}";
    TESTING_GIT_ACCOUNT = "${params.TESTING_GIT_ACCOUNT}";
  }
  parameters {
    string(
      name: 'PXC_VERSION', 
      defaultValue: '8.0.36-28.1', 
      description: 'PXC full version'
    )
    string(
      name: 'PXC_REVISION', 
      defaultValue: '5e9be6b', 
      description: 'PXC revision'
    )
    string(
      name: 'WSREP_VERSION', 
      defaultValue: '26.1.4.3', 
      description: 'WSREP version'
    )
    string(
      name: 'PXC57_PKG_VERSION', 
      defaultValue: '5.7.33-rel36-49.1', 
      description: 'PXC-5.7 package version'
    )
    booleanParam( 
      defaultValue: false,
      name: 'BUILD_TYPE_MINIMAL'
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
    withCredentials(moleculepxcJenkinsCreds())
    disableConcurrentBuilds()
  }

  stages {
    stage('Set build name'){
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXC_VERSION}-${env.PXC_REVISION}"
          currentBuild.description = "${env.PXC_REVISION}"
        }
      }
    }
    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
        echo "PXC_VERSION is ${env.PXC_VERSION}"
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
          script {
            moleculeParallelTest(pxcTarballRHEL8689(), env.MOLECULE_DIR)
          }
      }
    }
  }
  post {
    always {
      script {
        moleculeParallelPostDestroy(pxcTarballRHEL8689(), env.MOLECULE_DIR)
      }
    }
  }
}
