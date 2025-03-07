pipeline {
    agent {
        label 'min-bookworm-x64'
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
                    
                    def pxc_operator_version_latest = sh(script: "
                        curl -s https://api.github.com/repos/percona/percona-xtradb-cluster-operator/releases/latest | jq -r '.tag_name' | cut -c 2-"
                        , returnStdout: true).trim()
                    
                    echo "Latest PXC Operator version: ${pxc_operator_version_latest}"

                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                        sh """

                            aws s3 cp ${S3_BUCKET}/${TRIGGER_FILE} ${LOCAL_TRIGGER_FILE}
                            sed -i 's/^PDPXC_OPERATORS=.*/PDPXC_OPERATORS=1/' TRIGGER_JOBS
                            sed -i 's/^PXCO_VERSION=.*/PXCO_VERSION=${pxc_operator_version_latest}/' ${LOCAL_TRIGGER_FILE}
                            aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE} 
        
                        """
                    }
                }
            }
        }
    }
}
