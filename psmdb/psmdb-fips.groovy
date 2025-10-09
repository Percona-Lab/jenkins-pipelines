library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb/psmdb"
def fipsOS = ['al2023','rhel8-fips','rhel9','ubuntu-jammy-pro']

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'release',
                'experimental'
            ]
        )
        string(
            defaultValue: '5.0.22',
            description: 'PSMDB Version for tests',
            name: 'PSMDB_VERSION'
        )
        choice(
            name: 'GATED_BUILD',
            description: 'Test private repo?',
            choices: [
                'true',
                'false'
            ]
        )
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.REPO}-${params.PSMDB_VERSION}"
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
                withCredentials([
                    usernamePassword(credentialsId: 'PSMDB_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME'),
                    usernamePassword(credentialsId: 'OIDC_ACCESS', passwordVariable: 'OIDC_CLIENT_SECRET', usernameVariable: 'OIDC_CLIENT_ID')]) {
                      script {
                        moleculeParallelTest(fipsOS, moleculeDir)
                    }
                }
            }
            post {
                always {
                    junit testResults: "**/*-report.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
    }
    post {
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Package tests for PSMDB ${PSMDB_VERSION} on FIPS-enabled OSs, repo ${REPO}, pro repo - ${GATED_BUILD} finished succesfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: Package tests for PSMDB ${PSMDB_VERSION} on FIPS-enabled OSs, repo ${REPO}, pro repo - ${GATED_BUILD} failed - [${BUILD_URL}]")
        }
        always {
            script {
                moleculeParallelPostDestroy(fipsOS, moleculeDir)
            }
        }
    }
}
