library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb-tarball/psmdb-tarball"

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
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-4.2/percona-server-mongodb-4.2.15-16/binary/tarball/percona-server-mongodb-4.2.15-16-x86_64.glibc2.17-minimal.tar.gz',
            description: 'URL/S3 link for tarball to upgrade/downgrade from',
            name: 'TARBALL'
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
                    currentBuild.description = "${env.TARBALL}"                    
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
                sh """
                  echo ${params.TARBALL} | awk '{ match(\$0,/([0-9].[0-9])/,m); print m[0]}' > VERSION
                """
                script {
                    def PSMDB_VER = sh(returnStdout: true, script: "cat VERSION").trim()
                    moleculeParallelTest(pdmdbOperatingSystems("${PSMDB_VER}"), moleculeDir)
                }
            }
        }
    }
    post {
        always {
            junit testResults: "**/*-report.xml", keepLongStdio: true
            script {
                def PSMDB_VER = sh(returnStdout: true, script: "cat VERSION").trim()
                moleculeParallelPostDestroy(pdmdbOperatingSystems("${PSMDB_VER}"), moleculeDir)
            }
        }
    }
}
