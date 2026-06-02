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
                defaultValue: '8.0.12-4',
                description: 'PSMDB version for tests, e.g. 8.0.12-4, with hyphens',
                name: 'PSMDB_VERSION'
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
                    currentBuild.description = "${env.PSMDB_VERSION}"
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
                        moleculeParallelTestPSMDB(os, moleculeDir)
                    }
                }
            }
        }
    }
    post {
        always {
            junit testResults: "**/*-report.xml", keepLongStdio: true
            script {
                def os = pdmdbOperatingSystems("${PSMDB_VERSION}")
                os.removeAll { it.contains('-arm') }
                moleculeParallelPostDestroy(os, moleculeDir)
            }
        }
    }
}
