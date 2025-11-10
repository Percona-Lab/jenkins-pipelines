library changelog: false, identifier: "lib@PSMDB-1776", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb-tarball/psmdb-tarball"

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
            defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-4.2/percona-server-mongodb-4.2.15-16/binary/tarball/percona-server-mongodb-4.2.15-16-x86_64.glibc2.17-minimal.tar.gz',
            description: 'URL/S3 link for tarball to upgrade/downgrade from',
            name: 'TARBALL'
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH'
        )
        string(
                name: 'SSH_USER',
                description: 'User for debugging',
                defaultValue: 'none')
        string(
                name: 'SSH_PUBKEY',
                description: 'User ssh public key for debugging',
                defaultValue: 'none')
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
                   installMoleculeBookworm()
                }
            }
        }
        stage('Test') {
            steps {
                sh """
                  echo ${params.TARBALL} | sed -E 's/(.+mongodb-)([0-9].[0-9])(.+)/\\2/' > VERSION
                """
                withCredentials([string(credentialsId: 'VAULT_TRIAL_LICENSE', variable: 'VAULT_TRIAL_LICENSE')]) {
                    script {
                        def PSMDB_VER = sh(returnStdout: true, script: "cat VERSION").trim()
                        def os = pdmdbOperatingSystems("${PSMDB_VER}")
                        os.removeAll { it.contains('-arm') }
                        moleculeParallelTest(os, moleculeDir)
                    }
                }
            }
        }
    }
    post {
        always {
            junit testResults: "**/*-report.xml", keepLongStdio: true
            script {
                def PSMDB_VER = sh(returnStdout: true, script: "cat VERSION").trim()
                def os = pdmdbOperatingSystems("${PSMDB_VER}")
                os.removeAll { it.contains('-arm') }
//                moleculeParallelPostDestroy(os, moleculeDir)
            }
        }
    }
}
