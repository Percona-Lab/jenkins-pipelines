library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "psmdb-initsync"

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
    }
    parameters {
        choice(name: 'PSMDB',description: 'PSMDB repository',choices: ['psmdb-70','psmdb-80'])
        choice(name: 'REPO',description: 'Repo for testing',choices: ['testing','experimental','release'])
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['i3en.large','t2.micro','i3.xlarge','i3en.xlarge','i3en.3xlarge'])
        choice(name: 'GENERATOR',description: 'Use mgodatagen for regular documents or mongofiles for GridFS',choices: ['mongofiles','mgodatagen'])
        string(name: 'DB_COUNT',description: 'Amount of Databases for initsync',defaultValue: '10')
        string(name: 'DOC_COUNT',description: 'Amount of Documents or amout of files for gridFS per database',defaultValue: '1000000')
        string(name: 'DOC_SIZE',description: 'Approximate size in bytes of single document for initsync, use max 10Kb to avoid issues with max batch size',defaultValue: '1024')
        string(name: 'FILE_SIZE',description: 'The size of the file in megabytes for GridFS',defaultValue: '1024')
        string(name: 'IDX_COUNT',description: 'Amount of indexes per database for initsync',defaultValue: '2')
        string(name: 'TIMEOUT',description: 'Timeout for initsync',defaultValue: '3600')
        string(name: 'SSH_USER',description: 'User for debugging',defaultValue: 'none')
        string(name: 'SSH_PUBKEY',description: 'User ssh public key for debugging',defaultValue: 'none')
        string(name: 'TESTING_BRANCH',description: 'Branch for testing repository',defaultValue: 'main')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}"
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
        stage ('Test') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'PSMDB_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                    script{
                        moleculeExecuteActionWithScenario(moleculeDir, "test", "aws")
                    }
                }
            }
            post {
                always {
                    junit testResults: "**/report.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
    }
    post {
        always {
            script {
                moleculeExecuteActionWithScenario(moleculeDir, "destroy", "aws")
            }
        }
    }
}
