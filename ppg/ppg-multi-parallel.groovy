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
            name: 'TO_REPO',
            description: 'Repo for testing',
            choices: repoList()
        )
        choice(
            name: 'FROM_REPO',
            description: 'Repo for testing',
            choices: repoList()
        )
        string(
            defaultValue: 'ppg-13.3',
            description: 'PPG version for test',
            name: 'VERSION'
         )
        string(
            defaultValue: 'ppg-13.4',
            description: 'PPG Version for minor upgradetests',
            name: 'FROM_MINOR_VERSION')
        string(
            defaultValue: 'ppg-12.8',
            description: 'PPG Version for major upgrade tests',
            name: 'FROM_MAJOR_VERSION')
        string(
            defaultValue: 'main',
            description: 'base Branch for upgrade test',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: '13',
            description: 'PPG Major Version to test',
            name: 'MAJOR_VERSION')
  }
  options {
          withCredentials(moleculeDistributionJenkinsCreds())
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
                        build job: 'ppg-parallel', parameters: [
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}"),
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
                        build job: 'ppg-parallel', parameters: [
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.TO_PBM_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-setup"),
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
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.FROM_MINOR_VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-minor-upgrade"),
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
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.FROM_MINOR_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-minor-upgrade"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test minor downgrade' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test major upgrade') {
            steps {
                script {
                    try {
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.FROM_MAJOR_VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-major-upgrade"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test major upgrade' failed, but we continue"
                    }
                }
            }
        }
        stage ('Test major downgrade') {
            steps {
                script {
                    try {
                        build job: 'ppg-upgrade-parallel', parameters: [
                        string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                        string(name: 'FROM_VERSION', value: "${env.VERSION}"),
                        string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                        string(name: 'VERSION', value: "${env.FROM_MAJOR_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}"),
                        string(name: 'SCENARIO', value: "pg-${env.MAJOR_VERSION}-major-upgrade"),
                        ]
                    }
                    catch (err) {
                        currentBuild.result = "FAILURE"
                        echo "Stage 'Test major downgrade' failed, but we continue"
                    }
                }
            }
        }
  }
}