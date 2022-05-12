library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "resharding-tests/sharded"

pipeline {
    agent {
    label 'min-centos-7-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
        SCENARIO = 'aws'
    }
    parameters {
        choice(name: 'VENDOR',description: 'Vendor for testing',choices: ['mongodb-org','percona'])
        string(name: 'MONGOD_VERSION',description: 'Mongod version, should be latest or 5.0.+',defaultValue: 'latest')
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['i3.large','i3en.large','t2.micro','i3.xlarge','i3en.xlarge'])
        string(name: 'TIMEOUT',description: 'Time for running load test',defaultValue: '600')
        string(name: 'SIZE',description: 'Data size for test collection in Mb',defaultValue: '1000')
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
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.INSTANCE_TYPE}-${env.SIZE}Mb"
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
                    installMolecule()
                }
            }
        }
        stage ('Create instances') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "create", env.SCENARIO)
                }
            }
        }
        stage ('Prepare instances') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "prepare", env.SCENARIO)
                }
            }
        }
        stage ('Create infrastructure') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "converge", env.SCENARIO)
                }
            }
        }
        stage ('Run tests') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "verify", env.SCENARIO)
                }
            }
        }
        stage ('Cleanup') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "cleanup", env.SCENARIO)
                }
            }
        }
    }
    post {
        always {
            script {
                moleculeExecuteActionWithScenario(moleculeDir, "destroy", env.SCENARIO)
            }
            junit testResults: "**/report.xml", keepLongStdio: true
        }
    }
}
