library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb-tarball/psmdb-tarball-pro"
def os = ["rhel9","ubuntu-jammy-pro"]

pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        string(
            defaultValue: 'https://url_with_creds_if_needed.ol9.tar.gz',
            description: 'URL/S3 link for pro tarball for ol9',
            name: 'TARBALL_OL9'
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
                    currentBuild.description = "${env.TARBALL_OL9}"
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
                    installMolecule()
                }
            }
        }
        stage('Test') {
            steps {
                moleculeParallelTest(os, moleculeDir)
            }
        }
    }
    post {
        always {
            junit testResults: "**/*-report.xml", keepLongStdio: true
            moleculeParallelPostDestroy(os, moleculeDir)
        }
    }
}
