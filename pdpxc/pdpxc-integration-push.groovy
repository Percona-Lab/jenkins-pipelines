library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    options {
          withCredentials(moleculePdpxcJenkinsCreds())
          disableConcurrentBuilds()
    }
    environment {
        S3_BUCKET = "s3://package-testing-status-test"
        TRIGGER_FILE = "PXCO"
        LOCAL_TRIGGER_FILE = "PXCO"
    }
    stages {
        
        stage('Clean Workspace'){
            steps {
                deleteDir() // Cleans the workspace
            }
        }
    
        stage('Fetch and Update PXCO') {
            steps {
                script {
                    def jobId = env.BUILD_ID 

                        sh "sudo apt-get install jq -y"

                    def pxc_operator_version_latest = sh(script: """curl -s https://api.github.com/repos/percona/percona-xtradb-cluster-operator/releases/latest | jq -r '.tag_name' | cut -c 2-""", returnStdout: true).trim()
                    
                    echo "Latest PXC Operator version: ${pxc_operator_version_latest}"

                        sh """
                            aws s3 cp ${S3_BUCKET}/${TRIGGER_FILE} ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PXCO=.*/PXCO=1/' ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PXCO_VERSION=.*/PXCO_VERSION=${pxc_operator_version_latest}/' ${LOCAL_TRIGGER_FILE}
                            aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE} 
        
                        """

                }
            }
        }
    }
}
