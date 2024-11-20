pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        S3_BUCKET = "s3://package-testing-jobs"
        TRIGGER_FILE = "PS"
        LOCAL_TRIGGER_FILE = "PS"
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

        stage("Check the status and trigger the job"){
            steps{
                script{
                    def product_to_test = sh(script: "grep '^product_to_test=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2 | sed 's/\"//g'", returnStdout: true).trim()
                    def install_repo = sh(script: "grep '^install_repo=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2 | sed 's/\"//g'", returnStdout: true).trim()
                    def action_to_test = sh(script: "grep '^action_to_test=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2 | sed 's/\"//g'", returnStdout: true).trim()
                    def check_warnings = sh(script: "grep '^check_warnings=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2 | sed 's/\"//g'", returnStdout: true).trim()
                    def git_repo = sh(script: "grep '^git_repo=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2 | sed 's/\"//g'", returnStdout: true).trim()
                    def install_mysql_shell = sh(script: "grep '^install_mysql_shell=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2 | sed 's/\"//g'", returnStdout: true).trim()
                    def run_pt_job = sh(script: "grep '^run_pt_job=' ${LOCAL_TRIGGER_FILE} | cut -d '=' -f2 | sed 's/\"//g'", returnStdout: true).trim()


                    def parameters = [
                        [$class: 'StringParameterValue', name: 'product_to_test', value: "$product_to_test"], 
                        [$class: 'StringParameterValue', name: 'install_repo', value: "$install_repo"], 
                        [$class: 'StringParameterValue', name: 'action_to_test', value: "$action_to_test"], 
                        [$class: 'StringParameterValue', name: 'check_warnings', value: "$check_warnings"], 
                        [$class: 'StringParameterValue', name: 'install_mysql_shell', value: "$install_mysql_shell"], 
                        [$class: 'StringParameterValue', name: 'git_repo', value: "$git_repo"]
                    ]



                    if (run_pt_job) {
                        echo "run_pt_job value: ${run_pt_job}"
                        
                        // Perform specific actions based on the PDPS_OPERATORS value
                        if (run_pt_job == "0") {
                            echo "run_pt_job is set to 0. Setting build status to UNSTABLE."
                            currentBuild.result = 'UNSTABLE'  // Set build status to UNSTABLE
                        } else if (run_pt_job == "1") {
                            echo "run_pt_job is set to 1"
                            
                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '5d78d9c7-2188-4b16-8e31-4d5782c6ceaa', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            
                                sh """
                                    sed -i 's/^run_pt_job=.*/run_pt_job=0/' ${LOCAL_TRIGGER_FILE}
                                    aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE}
                                """
                            }

                            build job: 'ps-package-testing-molecule', parameters: parameters  

                            currentBuild.result = 'SUCCESS'  // Explicitly set build status to SUCCESS
                        
                        } else {
                            echo "run_pt_job has an unexpected value: ${pdpsOperatorsValue}. No status change."
                        }
                    } else {
                        echo "run_pt_job variable not found in the file."
                    }
                }
            }
        }

    }
}
