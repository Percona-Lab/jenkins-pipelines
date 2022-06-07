library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
  agent {
    label 'min-centos-7-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
  }
  parameters {
        choice(
            name: 'PLATFORM',
            description: 'For what platform (OS) need to test',
            choices: pdmysqlOperatingSystems()
        )
        choice(
            name: 'FROM_REPO',
            description: 'From this repo will be upgraded PDPS',
            choices: [
                'testing',
                'experimental',
                'release'
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
            defaultValue: '8.0.28',
            description: 'From this version pdmysql will be updated',
            name: 'FROM_VERSION')
        string(
            defaultValue: '8.0.29',
            description: 'To this version pdmysql will be updated',
            name: 'VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: '2.0.18',
            description: 'Updated Proxysql version',
            name: 'PROXYSQL_VERSION'
         )
        string(
            defaultValue: '8.0.23',
            description: 'Updated PXB version',
            name: 'PXB_VERSION'
         )
        string(
            defaultValue: '3.3.1',
            description: 'Updated Percona Toolkit version',
            name: 'PT_VERSION'
         )
        string(
            defaultValue: '3.1.4',
            description: 'Updated Percona Orchestrator version',
            name: 'ORCHESTRATOR_VERSION'
         )
  }
  options {
          withCredentials(moleculePdpsJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
        stage ('Test install') {
            when {
                expression { env.TO_REPO != 'release' }
            }
            steps {
                script {
                    try {
                        build job: 'pdps', parameters: [
                        string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pdps"),
                        string(name: 'PROXYSQL_VERSION', value: "${env.PROXYSQL_VERSION}"),
                        string(name: 'PXB_VERSION', value: "${env.PXB_VERSION}"),
                        string(name: 'PT_VERSION', value: "${env.PT_VERSION}"),
                        string(name: 'ORCHESTRATOR_VERSION', value: "${env.ORCHESTRATOR_VERSION}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test install' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test setup') {
            when {
                expression { env.TO_REPO == 'release' }
            }
            steps {
                script {
                    try {
                        build job: 'pdps', parameters: [
                        string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pdps-setup"),
                        string(name: 'PROXYSQL_VERSION', value: "${env.PROXYSQL_VERSION}"),
                        string(name: 'PXB_VERSION', value: "${env.PXB_VERSION}"),
                        string(name: 'PT_VERSION', value: "${env.PT_VERSION}"),
                        string(name: 'ORCHESTRATOR_VERSION', value: "${env.ORCHESTRATOR_VERSION}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test setup' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test minor upgrade') {
            steps {
                script {
                    try {
                        build job: 'pdps-upgrade', parameters: [
                        string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.FROM_VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pdps-minor-upgrade"),
                        string(name: 'PROXYSQL_VERSION', value: "${env.PROXYSQL_VERSION}"),
                        string(name: 'PXB_VERSION', value: "${env.PXB_VERSION}"),
                        string(name: 'PT_VERSION', value: "${env.PT_VERSION}"),
                        string(name: 'ORCHESTRATOR_VERSION', value: "${env.ORCHESTRATOR_VERSION}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test minor upgrade' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test minor downgrade') {
            steps {
                script {
                    try {
                        build job: 'pdps-upgrade', parameters: [
                        string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                        string(name: 'FROM_REPO', value: "${env.TO_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.VERSION}"),
                        string(name: 'TO_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'VERSION', value: "${env.FROM_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pdps-minor-upgrade"),
                        string(name: 'PROXYSQL_VERSION', value: "${env.PROXYSQL_VERSION}"),
                        string(name: 'PXB_VERSION', value: "${env.PXB_VERSION}"),
                        string(name: 'PT_VERSION', value: "${env.PT_VERSION}"),
                        string(name: 'ORCHESTRATOR_VERSION', value: "${env.ORCHESTRATOR_VERSION}"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test minor downgrade' failed, but we continue"
                    }
                }
            }
        }
  }
}
