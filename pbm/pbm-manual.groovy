library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def moleculeDir = "pbm-functional/manual"

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
        string(name: 'BRANCH',description: 'PBM repo branch',defaultValue: 'main')
        choice(name: 'PSMDB',description: 'PSMDB for testing',choices: ['psmdb-70','psmdb-80','psmdb-60','psmdb-50'])
        choice(name: 'INSTANCE_TYPE',description: 'Ec2 instance type',choices: ['t2.micro','i3.large','i3en.large','i3.xlarge','i3en.xlarge', 'i3en.3xlarge'])
        choice(name: 'LAYOUT',description: 'Layout',choices: ['replicaset','sharded'])        
        string(name: 'TIMEOUT',description: 'Timeout for the job',defaultValue: '3600')
        string(name: 'TESTING_BRANCH',description: 'Branch for testing repository',defaultValue: 'main')
        string(name: 'SSH_USER',description: 'User for debugging',defaultValue: 'none')
        string(name: 'SSH_PUBKEY',description: 'User ssh public key for debugging',defaultValue: 'none')
        password(name: 'PMM_HOST', description: 'PMM host with credentials, format https://user:password@x.x.x.x',defaultValue: 'none')
        string(name: 'DEBUG_SLEEP',description: 'Seconds to pause after config files are transferred, for manual debugging on the instance',defaultValue: '0')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){ 
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SSH_USER}-${env.LAYOUT}"
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
                    moleculeExecuteActionWithScenario(moleculeDir, "create", params.LAYOUT)
                }
            }
        }
        stage ('Prepare instances') {
            steps {
                script{
                    moleculeExecuteActionWithScenario(moleculeDir, "prepare", params.LAYOUT)
                }
            }
        }
        stage ('Create infrastructure') {
            steps {
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),file(credentialsId: 'PBM-GCS-S3', variable: 'PBM_GCS_S3_YML'),file(credentialsId: 'PBM-GCS-HMAC-S3', variable: 'PBM_GCS_HMAC_S3_YML'), file(credentialsId: 'PBM-AZURE', variable: 'PBM_AZURE_YML'), file(credentialsId: 'PBM-AWS-S3', variable: 'PBM_AWS_S3_YML'), file(credentialsId: 'PBM-MINIO-S3', variable: 'PBM_MINIO_S3_YML'), file(credentialsId: 'PBM-OSS', variable: 'PBM_OSS_YML'), file(credentialsId: 'PBM-OCI-WEST', variable: 'PBM_OCI_WEST_YML')]) {
                    script{
                        sh """
                            cp $PBM_AWS_S3_YML /tmp/pbm-agent-storage-aws.yaml
                            cp $PBM_MINIO_S3_YML /tmp/pbm-agent-storage-aws-minio.yaml
                            cp $PBM_GCS_S3_YML /tmp/pbm-agent-storage-gcp.conf
                            cp $PBM_GCS_HMAC_S3_YML /tmp/pbm-agent-storage-gcp-hmac.conf
                            cp $PBM_AZURE_YML /tmp/pbm-agent-storage-azure.conf
                            cp $PBM_OSS_YML /tmp/pbm-agent-storage-oss.yaml
                            cp $PBM_OCI_WEST_YML /tmp/pbm-agent-storage-oci.yaml
                        """
                        moleculeExecuteActionWithScenario(moleculeDir, "converge", params.LAYOUT)
                    }
                }
            }
        }
        stage ('Debug sleep') {
            when {
                expression { params.DEBUG_SLEEP.toInteger() > 0 }
            }
            steps {
                script {
                    echo "Sleeping for ${params.DEBUG_SLEEP} seconds so the instance can be inspected manually (config files are already transferred)."
                    sleep time: params.DEBUG_SLEEP.toInteger(), unit: 'SECONDS'
                }
            }
        }
//        stage ('Run tests') {
//            steps {
//                script{
//                    moleculeExecuteActionWithScenario(moleculeDir, "verify", params.LAYOUT)
//                }
//            }
//        }
//        stage ('Cleanup') {
//            steps {
//                script{
//                    moleculeExecuteActionWithScenario(moleculeDir, "cleanup", params.LAYOUT)
//                }
//            }
//        }
    }
    post {
        always {
            script {
                sh """
                    rm -f /tmp/pbm-agent-storage-aws.yaml
                    rm -f /tmp/pbm-agent-storage-aws-minio.yaml
                    rm -f /tmp/pbm-agent-storage-oss.yaml
                    rm -f /tmp/pbm-agent-storage-oci.yaml
                    rm -f /tmp/pbm-agent-storage-gcp.conf
                    rm -f /tmp/pbm-agent-storage-gcp-hmac.conf
                    rm -f /tmp/pbm-agent-storage-azure.conf
                """
                moleculeExecuteActionWithScenario(moleculeDir, "destroy", params.LAYOUT)
            }
        }
    }
}
