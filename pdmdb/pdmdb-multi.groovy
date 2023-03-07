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
            choices: pdmdbOperatingSystems('6.0')
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
            defaultValue: 'pdmdb-4.4.8',
            description: 'PDMDB Version for tests',
            name: 'TO_PDMDB_VERSION')
        string(
            defaultValue: '1.6.0',
            description: 'PBM Version for tests',
            name: 'TO_PBM_VERSION')
        choice(
            name: 'FROM_REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: 'pdmdb-4.4.8',
            description: 'base PDMDB version for upgrade test',
            name: 'FROM_PDMDB_VERSION')
        string(
            defaultValue: '1.6.0',
            description: 'base PBM Version for upgrade test',
            name: 'FROM_PBM_VERSION')
        string(
            defaultValue: 'main',
            description: 'base Branch for upgrade test',
            name: 'TESTING_BRANCH')

  }
  options {
          withCredentials(moleculePbmJenkinsCreds())
          disableConcurrentBuilds()
  }
  stages {
        stage ('Test install') {
            steps {
                build job: 'pdmdb', parameters: [
                string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                string(name: 'REPO', value: "${env.TO_REPO}"),
                string(name: 'PDMDB_VERSION', value: "${env.TO_PDMDB_VERSION}"),
                string(name: 'VERSION', value: "${env.TO_PBM_VERSION}"),
                string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                ]
            }
        }
        stage ('Test setup') {
            steps {
                script {
                    if (env.TO_REPO == 'release') {
                        build job: 'pdmdb-setup', parameters: [
                        string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                        string(name: 'REPO', value: "${env.TO_REPO}"),
                        string(name: 'PDMDB_VERSION', value: "${env.TO_PDMDB_VERSION}"),
                        string(name: 'VERSION', value: "${env.TO_PBM_VERSION}"),
                        string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                        ]
                    }
                    else {
                        echo 'skipped setup on non-release repo'
                    }
                }
            }
        }
        stage ('Test upgrade') {
            steps {
                build job: 'pdmdb-upgrade', parameters: [
                string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                string(name: 'FROM_REPO', value: "${env.FROM_REPO}"),
                string(name: 'FROM_PDMDB_VERSION', value: "${env.FROM_PDMDB_VERSION}"),
                string(name: 'FROM_PBM_VERSION', value: "${env.FROM_PBM_VERSION}"),
                string(name: 'TO_REPO', value: "${env.TO_REPO}"),
                string(name: 'TO_PDMDB_VERSION', value: "${env.TO_PDMDB_VERSION}"),
                string(name: 'TO_PBM_VERSION', value: "${env.TO_PBM_VERSION}"),
                string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                ]
            }
        }
        stage ('Test downgrade') {
            steps {
                build job: 'pdmdb-upgrade', parameters: [
                string(name: 'PLATFORM', value: "${env.PLATFORM}"),
                string(name: 'FROM_REPO', value: "${env.TO_REPO}"),
                string(name: 'FROM_PDMDB_VERSION', value: "${env.TO_PDMDB_VERSION}"),
                string(name: 'FROM_PBM_VERSION', value: "${env.TO_PBM_VERSION}"),
                string(name: 'TO_REPO', value: "${env.FROM_REPO}"),
                string(name: 'TO_PDMDB_VERSION', value: "${env.FROM_PDMDB_VERSION}"),
                string(name: 'TO_PBM_VERSION', value: "${env.FROM_PBM_VERSION}"),
                string(name: 'TESTING_BRANCH', value: "${env.TESTING_BRANCH}")
                ]
            }
        }
  }
}
