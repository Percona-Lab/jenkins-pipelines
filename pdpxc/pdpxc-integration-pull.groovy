pipeline {
    agent {
        label 'docker'
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
                    
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
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
                    
                   // if (pxcoValue) {
                        echo "PXCO value: ${pxcoValue}"
                        
                        // Perform specific actions based on the PDPS_OPERATORS value
                       // if (pxcoValue == "0") {
                            echo "PXCO is set to 0. Setting build status to UNSTABLE."
                            currentBuild.result = 'UNSTABLE'  // Set build status to UNSTABLE
                       // } else if (pxcoValue == "1") {
                            echo "PXCO is set to 1. Keeping build status SUCCESS."
                            def jobId = env.BUILD_ID
                            
                            // Modify and upload the file back to S3
                            
                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            
                                sh """
                                    sed -i 's/^PXCO=.*/PXCO=0/' ${LOCAL_TRIGGER_FILE}
                                    aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE}
                                """
                            }
                            
                       //     currentBuild.result = 'SUCCESS'  // Explicitly set build status to SUCCESS
                        

                            // Trigger the PXC Operator Jobs on cloud.cd jenkins
                            def envFile = readFile("${LOCAL_TRIGGER_FILE}").trim()
                            envFile.readLines().each { line ->
                                def parts = line.split('=', 2)
                                if (parts.size() == 2) {
                                    def key = parts[0].trim()
                                    def value = parts[1].trim()
                                    env."${key}" = value  // Workaround to avoid putAt()
                                }
                            }
                            echo "Environment variables set!"

                            echo """
                                GIT_BRANCH: ${PXCO_VERSION}
                                GIT_REPO: ${node_to_test}
                                PLATFORM_VER: ${params.test_repo}
                                GKE_RELEASE_CHANNEL: ${test_type}
                                IMAGE_OPERATOR: ${params.pxc57_repo}
                                IMAGE_PXC: ${params.pxc57_repo}
                                IMAGE_PROXY: ${params.pxc57_repo}
                                IMAGE_HAPROXY: ${params.pxc57_repo}
                                IMAGE_BACKUP: ${params.pxc57_repo}
                                IMAGE_LOGCOLLECTOR: ${params.pxc57_repo}
                                IMAGE_PMM_CLIENT: ${params.pxc57_repo}
                                IMAGE_PMM_SERVER: ${params.pxc57_repo}
                            """
                            
                            build(
                                job: 'pxco-gke-1',
                                parameters: [
                                    string(name: "GIT_BRANCH", value: PXCO_VERSION),
                                    string(name: "GIT_REPO", value: node_to_test),
                                    string(name: "PLATFORM_VER", value: params.test_repo),
                                    string(name: "GKE_RELEASE_CHANNEL", value: "${test_type}"),
                                    string(name: "IMAGE_OPERATOR", value: params.pxc57_repo),
                                    string(name: "IMAGE_PXC", value: params.pxc57_repo),
                                    string(name: "IMAGE_PROXY", value: params.pxc57_repo),
                                    string(name: "IMAGE_HAPROXY", value: params.pxc57_repo),
                                    string(name: "IMAGE_BACKUP", value: params.pxc57_repo),
                                    string(name: "IMAGE_LOGCOLLECTOR", value: params.pxc57_repo),
                                    string(name: "IMAGE_PMM_CLIENT", value: params.pxc57_repo),
                                    string(name: "IMAGE_PMM_SERVER", value: params.pxc57_repo)
                                ],
                                propagate: true,
                                wait: true
                            )



                       // } else {
                         //   echo "PXCO has an unexpected value: ${pxcoValue}. No status change."
                      //  }
                   // } else {
                      
                      //  echo "PXCO variable not found in the file."
                    
                    //}
                }
            }
        }
    }
}
