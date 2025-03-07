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
    
        stage('Fetch and Check PXCO') {
            steps {
                script {
                    echo "Fetching file from S3..."
                    
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh """
                            aws s3 cp ${S3_BUCKET}/${TRIGGER_FILE} ${LOCAL_TRIGGER_FILE}
                        """
                    
                    }
                    
                }
            }
        }

        stage("Check"){
            steps{
                script{
                    def pxcoValue = sh(script: "grep '^PXCO=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2", returnStdout: true).trim()
                    
                    if (pxcoValue) {
                        echo "PXCO value: ${pxcoValue}"
                        
                        // Perform specific actions based on the PDPS_OPERATORS value
                        if (pxcoValue == "0") {
                            echo "PXCO is set to 0. Setting build status to UNSTABLE."
                            currentBuild.result = 'UNSTABLE'  // Set build status to UNSTABLE
                        } else if (pxcoValue == "1") {
                            echo "PXCO is set to 1. Keeping build status SUCCESS."
                            def jobId = env.BUILD_ID
                            
                            // Modify and upload the file back to S3
                            
                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            
                                sh """
                                    sed -i 's/^PXCO=.*/PXCO=0/' ${LOCAL_TRIGGER_FILE}
                                    aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE}
                                """
                            }
                            
                            currentBuild.result = 'SUCCESS'  // Explicitly set build status to SUCCESS
                        

                            // Trigger the PXC Operator Jobs on cloud.cd jenkins


                        } else {
                            echo "PXCO has an unexpected value: ${pxcoValue}. No status change."
                        }
                    } else {
                        echo "PXCO variable not found in the file."
                    }
                }
            }
        }
    }
}
