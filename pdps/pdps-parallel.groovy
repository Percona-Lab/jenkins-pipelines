library changelog: false, identifier: "lib@test-shell", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/kaushikpuneet07/jenkins-pipelines.git'
])

pipeline {
  agent {
      label 'min-bookworm-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/pdmysql/${SCENARIO}";

  }
  parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.33-25',
            description: 'Percona Server version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: '',
            description: 'Percona Server revision for test. Empty by default (not checked).',
            name: 'PS_REVISION'
        )
        string(
            defaultValue: '',
            description: 'Expected percona-mysql-shell own version, e.g. 8.4.10. Empty: shell is checked against VERSION as before.',
            name: 'PSHELL_VERSION'
        )
        string(
            defaultValue: '',
            description: 'Expected percona-mysql-shell DEB package build, e.g. 8.4.10-1-3. Empty: not checked.',
            name: 'PSHELL_PKG_VERSION'
        )
        string(
            defaultValue: '',
            description: 'Expected percona-mysql-shell RPM package build, e.g. 8.4.10-3. Empty: not checked.',
            name: 'PSHELL_RPM_PKG_VERSION'
        )
        string(
            defaultValue: '',
            description: 'PS version percona-mysql-shell is built for (the "for MySQL" part of mysqlsh --version), e.g. 8.4.13-13. Empty means VERSION.',
            name: 'PSHELL_BUILT_FOR'
        )
        string(
            defaultValue: '2.5.1',
            description: 'Proxysql version for test',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '8.0.33-27',
            description: 'PXB version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '3.5.3',
            description: 'Percona toolkit version for test',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '3.2.6-9',
            description: 'Percona orchestrator version for test',
            name: 'ORCHESTRATOR_VERSION'
        )
        string(
            defaultValue: '',
            description: 'Orchestrator revision for version from https://github.com/percona/orchestrator . Empty by default (not checked).',
            name: 'ORCHESTRATOR_REVISION'
        )
        choice(
            name: 'SCENARIO',
            description: 'PDMYSQL scenario for test',
            choices: pdpsScenarios()
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
        string(
            defaultValue: 'master',
            description: 'Tests will be run from branch of  https://github.com/percona/orchestrator',
            name: 'ORCHESTRATOR_TESTS_VERSION'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (pdps-8.0) repo instead of pdps-8.0.XX"
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}"
                    currentBuild.description = "${env.VERSION}-${env.REPO}-${env.TESTING_BRANCH}-${env.MAJOR_REPO}"
                }
            }
        }
        stage ('Prepare') {
          steps {
                script {
                   deleteDir()
                   installMoleculeBookworm_pdps()
             }
           }
        }
        stage('Check version param and checkout') {
            steps {
                checkOrchVersionParam()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
            }
        }
        stage('Test') {
          steps {
                script {
                    moleculeParallelTestPDPS(pdpsOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroyPDPS(pdpsOperatingSystems(), env.MOLECULE_DIR)
         }
      }
   }
}
