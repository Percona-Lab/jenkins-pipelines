library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb/psmdb"
def psmdb_default_os_list = ['rhel7-fips','rhel8-fips','rhel9','ubuntu-focal-pro','ubuntu-jammy-pro','ubuntu-noble-pro']
def psmdb_7_os_list = ['rhel7-fips','rhel8-fips','rhel9','ubuntu-focal-pro','ubuntu-jammy-pro']

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
                'false',
                'true'
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

                    def versionNumber = PSMDB_VERSION =~ /^(\d+)\.(\d+)\.(\d+)/
                    def version = versionNumber ? Integer.parseInt(versionNumber[0][1]) : null

                    if (version == 7) {
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
            post {
                always {
                    junit testResults: "**/*-report.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
    }
    post {
        always {
            script {
                moleculeParallelPostDestroy(fipsOS, moleculeDir)
            }
        }
    }
}
