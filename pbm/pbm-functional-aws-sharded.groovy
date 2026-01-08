library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pbm-functional/sharded"

pipeline {
    agent {
    label 'min-bookworm-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        ANSIBLE_DISPLAY_SKIPPED_HOSTS = false
        SCENARIO = 'aws'
    }
    parameters {
        string(name: 'BRANCH',description: 'PBM repo branch',defaultValue: 'main')
        choice(name: 'PSMDB',description: 'PSMDB for testing',choices: ['psmdb-70','psmdb-60','psmdb-50','psmdb-80'])
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['i3.large','i3en.large','t2.micro','i3.xlarge','i3en.xlarge'])
        choice(name: 'BACKUP_TYPE',description: 'Backup type',choices: ['physical','logical'])        
        choice(name: 'STORAGE',description: 'Storage for PBM',choices: ['aws','aws-minio','gcp','gcp-hmac','azure','oss'])
        string(name: 'TIMEOUT',description: 'Timeout for backup/restore',defaultValue: '3600')
        string(name: 'SIZE',description: 'Data size for test collection',defaultValue: '1000')
        string(name: 'EXISTING_BACKUP',description: 'If defined, the tests will skip backup process, but backup must exist on the remote storage',defaultValue: 'no')
        choice(name: 'CHECK_PITR',description: 'If defined, the tests will run pitr checks, set to no if you want to test the existing backup',choices: ['yes','no'])
        string(name: 'RESTORE_NUMDOWNLOADWORKERS',description: 'PBM setting numDownloadWorkers, if 0 - default will be used',defaultValue: '0')
        string(name: 'RESTORE_NUMDOWNLOADBUFFERMB',description: 'PBM setting maxDownloadBufferMb, if 0 - default will be used',defaultValue: '0')
        string(name: 'RESTORE_DOWNLOADCHUNKMB',description: 'PBM setting downloadChunkMb, if 0 - default will be used',defaultValue: '0')
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
                    installMoleculeBookworm()
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
                withCredentials([file(credentialsId: 'PBM-AWS-S3', variable: 'PBM_AWS_S3_YML'),
                file(credentialsId: 'PBM-MINIO-S3', variable: 'PBM_MINIO_S3_YML'),
                file(credentialsId: 'PBM-GCS-S3', variable: 'PBM_GCS_S3_YML'),
                file(credentialsId: 'PBM-GCS-HMAC-S3', variable: 'PBM_GCS_HMAC_S3_YML'),
                file(credentialsId: 'PBM-AZURE', variable: 'PBM_AZURE_YML'),
                file(credentialsId: 'PBM-OSS', variable: 'PBM_OSS_YML')]) {
                    script{
                        sh """
                            cp $PBM_AWS_S3_YML /tmp/pbm-agent-storage-aws.yaml
                            cp $PBM_MINIO_S3_YML /tmp/pbm-agent-storage-aws-minio.yaml
                            cp $PBM_GCS_S3_YML /tmp/pbm-agent-storage-gcp.conf
                            cp $PBM_GCS_HMAC_S3_YML /tmp/pbm-agent-storage-gcp-hmac.conf
                            cp $PBM_AZURE_YML /tmp/pbm-agent-storage-azure.conf
                            cp $PBM_OSS_YML /tmp/pbm-agent-storage-oss.yaml
                        """
//                        moleculeExecuteActionWithScenario(moleculeDir, "converge", env.SCENARIO)
                    }
                }
            }
        }
//        stage ('Run tests') {
//            steps {
//                script{
//                    moleculeExecuteActionWithScenario(moleculeDir, "verify", env.SCENARIO)
//                }
//            }
//        }
//        stage ('Cleanup') {
//            steps {
//                script{
//                    moleculeExecuteActionWithScenario(moleculeDir, "cleanup", env.SCENARIO)
//                }
//            }
//        }
    }
//    post {
//        always {
//            script {
//                sh """
//                    rm -f /tmp/pbm-agent-storage-aws.yaml
//                    rm -f /tmp/pbm-agent-storage-aws-minio.yaml
//                    rm -f /tmp/pbm-agent-storage-gcp.conf
//                    rm -f /tmp/pbm-agent-storage-gcp-hmac.conf
//                    rm -f /tmp/pbm-agent-storage-azure.conf
//                    rm -f /tmp/pbm-agent-storage-oss.yaml
//                """
//                moleculeExecuteActionWithScenario(moleculeDir, "destroy", env.SCENARIO)
//            }
//            junit testResults: "**/report.xml", keepLongStdio: true
//        }
//    }
}
