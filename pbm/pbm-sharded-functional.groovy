library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pbm-functional/sharded"

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
        string(name: 'BRANCH',description: 'PBM repo branch',defaultValue: 'main')
        choice(name: 'PSMDB',description: 'PSMDB for testing',choices: ['psmdb-44','psmdb-42','psmdb-50'])
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['i3.large','i3en.large','t2.micro','i3.xlarge','i3en.xlarge'])
        choice(name: 'BACKUP_TYPE',description: 'Backup type',choices: ['physical','logical'])        
        choice(name: 'STORAGE',description: 'Storage for PBM',choices: ['aws','gcp'])
        string(name: 'TIMEOUT',description: 'Timeout for backup/restore',defaultValue: '3600')
        string(name: 'SIZE',description: 'Data size for test collection',defaultValue: '1000')
        string(name: 'TESTING_BRANCH',description: 'Branch for testing repository',defaultValue: 'main')
        string(name: 'SSH_USER',description: 'User for debugging',defaultValue: 'none')
        string(name: 'SSH_PUBKEY',description: 'User ssh public key for debugging',defaultValue: 'none')
        password(name: 'PMM_HOST', description: 'PMM host with credentials, format https://user:password@x.x.x.x',defaultValue: 'none')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){ 
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.BACKUP_TYPE}-${env.SIZE}Mb"
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
        stage ('Install build tools') {
            steps {
                sh """
                    curl "https://raw.githubusercontent.com/percona/percona-backup-mongodb/${params.BRANCH}/packaging/scripts/mongodb-backup_builder.sh" -o "mongodb-backup_builder.sh"
                    chmod +x mongodb-backup_builder.sh
                    mkdir -p /tmp/builddir
                    sudo ./mongodb-backup_builder.sh --builddir=/tmp/builddir --install_deps=1
                """
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
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '67d10a9b-d873-450b-bf0f-95d32477501c', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    script{
                        moleculeExecuteActionWithScenario(moleculeDir, "converge", env.SCENARIO)
                    }
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
