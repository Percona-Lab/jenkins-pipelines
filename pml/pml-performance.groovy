library changelog: false, identifier: "lib@master", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pml-functional/replicaset"
def stageFailed = false

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
        choice(name: 'PSMDB',description: 'PSMDB for testing',choices: ['psmdb-70','psmdb-80','psmdb-60','psmdb-50'])
        string(name: 'PML_BRANCH',description: 'PML Branch for testing',defaultValue: 'main')
        string(name: 'TESTING_BRANCH',description: 'Branch for testing repository',defaultValue: 'main')
        string(name: 'GO_VERSION',description: 'Version of Golang used',defaultValue: '1.24.1')
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['t2.micro','i3.large','i3en.large','i3.xlarge','i3en.xlarge'])
        string(name: 'DATASIZE',description: 'The max size of the data in mb',defaultValue: '10')
        string(name: 'COLLECTIONS',description: 'The number of collections',defaultValue: '1')
        booleanParam(name: 'RANDOM_DISTRIBUTE_DATA', defaultValue: false, description: 'Randomly distribute data throughout Collections')
        string(name: 'EXTRAS_VARS',description: 'Extra variables used for Mongolink job',defaultValue: '')
        string(name: 'TIMEOUT',description: 'Timeout for the job',defaultValue: '3600')
        string(name: 'SSH_USER',description: 'User for debugging',defaultValue: 'none')
        string(name: 'SSH_PUBKEY',description: 'User ssh public key for debugging',defaultValue: 'none')
        booleanParam(name: 'DESTROY', defaultValue: true, description: 'Automatically dismantle environment upon finishing tests, leave unchecked if you do not want to dismantle.')
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
                    try {
                        moleculeExecuteActionWithScenario(moleculeDir, "create", "aws")
                    } catch (e) {
                        echo "Create stage failed"
                        stageFailed = true
                        throw e
                    }
                }
            }
        }
        stage ('Prepare instances') {
            steps {
                script{
                    try {
                        moleculeExecuteActionWithScenario(moleculeDir, "prepare", "aws")
                    } catch (e) {
                        echo "Prepare stage failed"
                        stageFailed = true
                        throw e
                    }
                }
            }
        }
        stage ('Create infrastructure') {
            steps {
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),file(credentialsId: 'PBM-GCS-S3', variable: 'PBM_GCS_S3_YML'), file(credentialsId: 'PBM-AZURE', variable: 'PBM_AZURE_YML'), string(credentialsId: 'GITHUB_API_TOKEN', variable: 'MONGO_REPO_TOKEN')]) {
                    script{
                        try {
                            moleculeExecuteActionWithScenario(moleculeDir, "converge", "aws")
                        } catch (e) {
                            echo "Converge stage failed"
                            stageFailed = true
                            throw e
                        }
                    }
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
                if (params.DESTROY || stageFailed) {
                    echo "Running destroy because DESTROY=true or build failed"
                    moleculeExecuteActionWithScenario(moleculeDir, "destroy", "aws")
                } else {
                    echo "Skipping destroy because DESTROY is false and build succeeded"
                }
            }
        }
    }
}
