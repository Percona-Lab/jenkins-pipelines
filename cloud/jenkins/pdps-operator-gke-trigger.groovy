pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        S3_BUCKET = "s3://package-testing-jobs"
        TRIGGER_FILE = "PDPS_OPERATORS_BUILD_STATUS"
        LOCAL_TRIGGER_FILE = "PDPS_OPERATORS_BUILD_STATUS"
    }
    stages {
        stage('Clean Workspace'){
            steps {
                deleteDir()
            }
        }
    
        stage('Fetch and Check PDPS_OPERATORS_BUILD_STATUS') {
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
                    def pdpsOperatorsValue = sh(script: "grep '^PDPS_OPERATORS=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2", returnStdout: true).trim()
                    
                    if (pdpsOperatorsValue) {
                        echo "PDPS_OPERATORS value: ${pdpsOperatorsValue}"
                        
                        // Perform specific actions based on the PDPS_OPERATORS value
                        if (pdpsOperatorsValue == "0") {
                            echo "PDPS_OPERATORS is set to 0. Setting build status to UNSTABLE."
                            currentBuild.result = 'UNSTABLE'  // Set build status to UNSTABLE
                        } else if (pdpsOperatorsValue == "1") {
                            echo "PDPS_OPERATORS is set to 1. Keeping build status SUCCESS."
                            def jobId = env.BUILD_ID
                            
                            // Modify and upload the file back to S3
                            
                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            
                                sh """
                                    sed -i 's/^PDPS_OPERATORS=.*/PDPS_OPERATORS=0/' ${LOCAL_TRIGGER_FILE}
                                    aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE}
                                """
                            }
                            
                            currentBuild.result = 'SUCCESS'  // Explicitly set build status to SUCCESS
                        
                        } else {
                            echo "PDPS_OPERATORS has an unexpected value: ${pdpsOperatorsValue}. No status change."
                        }
                    } else {
                        echo "PDPS_OPERATORS variable not found in the file."
                    }
                }
            }
        }

        stage("Trigger a Job"){
            steps{
                script{
                    build job: 'TestJob1', parameters: [[$class: 'StringParameterValue', name: 'TRIGGER_FILE', value: TRIGGER_FILE]]
                }
            }
        }

    }
}
