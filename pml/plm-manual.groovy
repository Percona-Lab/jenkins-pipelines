library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "plm-functional/manual"

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
        STORAGE = 'aws'
    }
    parameters {
        choice(name: 'PSMDB',description: 'PSMDB used for testing',choices: ['6', '7','8'])
        string(name: 'PLM_BRANCH',description: 'PLM Branch for testing',defaultValue: 'main')
        string(name: 'GO_VERSION',description: 'Version of Golang used',defaultValue: '1.24.1')
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['t2.micro','i3.large','i3en.large','i3.xlarge','i3en.xlarge'])
        string(name: 'TIMEOUT',description: 'Timeout for the job',defaultValue: '3600')
        string(name: 'TESTING_BRANCH',description: 'Branch for testing repository',defaultValue: 'main')
        string(name: 'SSH_USER',description: 'User for debugging',defaultValue: 'none')
        string(name: 'SSH_PUBKEY',description: 'User ssh public key for debugging',defaultValue: 'none')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){ 
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SSH_USER}-aws"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
            }
        }
        stage ('Install molecule') {
            steps {
                script {
                    installMoleculeBookworm()
                }
            }
        }
        stage ('Create instances') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "create", "aws")
                }
            }
        }
        stage ('Prepare instances') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "prepare", "aws")
                }
            }
        }
        stage ('Create infrastructure') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "converge", "aws")
                }

            }
        }
        stage ('Run tests') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "verify", "aws")
                }
            }
        }
        stage ('Cleanup') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "cleanup", "aws")
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
