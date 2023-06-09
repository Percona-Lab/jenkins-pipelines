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
            choices: pdpsOperatingSystems()
        )
        choice(
            name: 'FROM_REPO',
            description: 'Repo for upgrade tests. From this repo PDPS will be upgraded',
            choices: [
                'release',
                'testing',
                'experimental'
            ]
        )
        choice(
            name: 'TO_REPO',
            description: 'Repo for installalation and upgrade tests. Use "testing" for pre-release and "release" for post-release runs.',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        string(
            defaultValue: '8.0.31-23',
            description: 'From this version pdmysql will be updated. Possible values are with and without percona release: 8.0.31 OR 8.0.31-23',
            name: 'FROM_VERSION')
        string(
            defaultValue: '8.0.32-24',
            description: 'To this version pdmysql will be updated. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: '2.4.8',
            description: 'Updated Proxysql version',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '8.0.32-25',
            description: 'Updated PXB version. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '3.5.1',
            description: 'Updated Percona Toolkit version',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '3.2.6-8',
            description: 'Updated Percona Orchestrator version',
            name: 'ORCHESTRATOR_VERSION'
        )
        string(
            defaultValue: '',
            description: 'Orchestrator revision for version from https://github.com/percona/orchestrator . Empty by default (not checked).',
            name: 'ORCHESTRATOR_REVISION'
        )
  }
  options {
          withCredentials(moleculePdpsJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
        stage('Check version param') {
            steps {
                checkOrchVersionParam()
            }
        }
        stage ('Test install: minor repo') {
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
                        booleanParam(name: 'MAJOR_REPO', value: false)
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test install' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test setup: minor repo') {
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
                        string(name: 'SCENARIO', value: "pdps_setup"),
                        string(name: 'PROXYSQL_VERSION', value: "${env.PROXYSQL_VERSION}"),
                        string(name: 'PXB_VERSION', value: "${env.PXB_VERSION}"),
                        string(name: 'PT_VERSION', value: "${env.PT_VERSION}"),
                        string(name: 'ORCHESTRATOR_VERSION', value: "${env.ORCHESTRATOR_VERSION}"),
                        booleanParam(name: 'MAJOR_REPO', value: false)
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test setup' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test setup: major repo') {
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
                        string(name: 'SCENARIO', value: "pdps_setup"),
                        string(name: 'PROXYSQL_VERSION', value: "${env.PROXYSQL_VERSION}"),
                        string(name: 'PXB_VERSION', value: "${env.PXB_VERSION}"),
                        string(name: 'PT_VERSION', value: "${env.PT_VERSION}"),
                        string(name: 'ORCHESTRATOR_VERSION', value: "${env.ORCHESTRATOR_VERSION}"),
                        booleanParam(name: 'MAJOR_REPO', value: true)
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
            when {
                expression { env.TO_REPO != 'release' }
            }
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
                        string(name: 'SCENARIO', value: "pdps_minor_upgrade"),
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
  }
}
