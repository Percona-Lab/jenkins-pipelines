library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb-tarball/psmdb-tarball-pro"
def psmdb_default_os_list = ["al2023","rhel8","rhel9","ubuntu-jammy-pro","ubuntu-noble-pro"]
def psmdb_7_os_list = ["al2023","rhel8","rhel9","ubuntu-jammy-pro","ubuntu-noble-pro","debian-12"]

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        string(
            defaultValue: '6.0.20-17',
            description: 'PSMDB version for tests',
            name: 'PSMDB_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
    }
    options {
          withCredentials(moleculePbmJenkinsCreds())
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}"
                    currentBuild.description = "${env.PSMDB_VERSION}"

                    def versionNumber = PSMDB_VERSION =~ /^(\d+)/
                    def version = versionNumber ? Integer.parseInt(versionNumber[0][1]) : null

                    if (version > 7) {
                        psmdb_default_os_list = psmdb_7_os_list
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
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
            withCredentials([usernamePassword(credentialsId: 'PSMDB_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
             script {
                moleculeParallelTest(psmdb_default_os_list, moleculeDir)
            }
          }
        }
      }
    }
    post {
        always {
            junit testResults: "**/*-report.xml", keepLongStdio: true
            moleculeParallelPostDestroy(psmdb_default_os_list, moleculeDir)
        }
    }
}
