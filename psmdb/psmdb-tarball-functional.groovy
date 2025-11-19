library changelog: false, identifier: "lib@master", retriever: modernSCM([
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
                defaultValue: '7.0.24',
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
                withCredentials([string(credentialsId: 'VAULT_TRIAL_LICENSE', variable: 'VAULT_TRIAL_LICENSE')]) {
                    script {
                        def os = pdmdbOperatingSystems("${PSMDB_VERSION}")
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
                moleculeParallelPostDestroy(os, moleculeDir)
            }
        }
    }
}
