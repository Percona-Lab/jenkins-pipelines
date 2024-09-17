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
      MOLECULE_DIR = "molecule/pdmysql/pdpxc_minor_upgrade";
  }
  parameters {
        choice(
            name: 'FROM_REPO',
            description: 'PDPXC will be upgraded from this repository',
            choices: [
                'release',
                'testing',
                'experimental'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.34',
            description: 'From this version pdmysql will be updated. Possible values are with and without percona release: 8.0.30 OR 8.0.30-22',
            name: 'FROM_VERSION'
        )
        string(
            defaultValue: '8.0.35-27',
            description: 'To this version pdmysql will be updated. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: '',
            description: 'PXC revision for test after update. Empty by default (not checked).',
            name: 'PXC_REVISION'
        )
        string(
            defaultValue: '8.0.35-30',
            description: 'PXB version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '2.5.5',
            description: 'Proxysql version for test',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '2.8.5',
            description: 'HAProxy version for test',
            name: 'HAPROXY_VERSION'
        )
        string(
            defaultValue: '3.6.0',
            description: 'Percona toolkit version for test',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '1.0',
            description: 'replication-manager.sh version',
            name: 'REPL_MANAGER_VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
            defaultValue: 'Percona-QA',
            description: 'Git account for package-testing repository',
            name: 'TESTING_GIT_ACCOUNT'
        )
  }
  options {
          withCredentials(moleculePdpxcJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}"
                    currentBuild.description = "From: ${env.FROM_VERSION} ${env.FROM_REPO}; to: ${env.VERSION} ${env.TO_REPO}. Git br: ${env.TESTING_BRANCH}"
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
                   installMoleculeBookworm()
             }
           }
        }
        stage('Test') {
          steps {
                script {
                    moleculeParallelTest(pdpxcOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroy(pdpxcOperatingSystems(), env.MOLECULE_DIR)
         }
      }
   }
}
