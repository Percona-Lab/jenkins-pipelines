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
                sh """
                
                ls -la
                
                rm -rf * 
                
                ls -la 
                """


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
                            
                            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            
                                sh """
                                    sed -i 's/^PXCO=.*/PXCO=0/' ${LOCAL_TRIGGER_FILE}
                                    aws s3 cp ${LOCAL_TRIGGER_FILE} ${S3_BUCKET}/${TRIGGER_FILE}
                                """
                            }
                            
                            currentBuild.result = 'SUCCESS'  // Explicitly set build status to SUCCESS
                        

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
                            
                            build(
                                job: 'pxco-gke-1',
                                parameters: [
                                    string(name: "TEST_SUITE", value: "run-release.csv"),
                                    string(name: "IGNORE_PREVIOUS_RUN", value: "YES"),
                                    string(name: "PILLAR_VERSION", value: "80"),
                                    string(name: "GIT_BRANCH", value: "v${PXCO_VERSION}"),
                                    string(name: "GIT_REPO", value: "https://github.com/percona/percona-xtradb-cluster-operator"),
                                    string(name: "PLATFORM_VER", value: "max"),
                                    string(name: "GKE_RELEASE_CHANNEL", value: "rapid"),
                                    string(name: "CLUSTER_WIDE", value: "YES"),
                                    string(name: "IMAGE_OPERATOR", value: "percona/percona-xtradb-cluster-operator:${PXCO_VERSION}"),
                                    string(name: "IMAGE_PXC", value: "perconalab/percona-xtradb-cluster:${PXC_VERSION}"),
                                    string(name: "IMAGE_PROXY", value: "percona/proxysql2:${PROXYSQL_VERSION}"),
                                    string(name: "IMAGE_HAPROXY", value: "perconalab/haproxy:${HAPROXY_VERSION}"),
                                    string(name: "IMAGE_BACKUP", value: "perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup"),
                                    string(name: "IMAGE_LOGCOLLECTOR", value: "perconalab/percona-xtradb-cluster-operator:main-logcollector"),
                                    string(name: "IMAGE_PMM_CLIENT", value: "perconalab/pmm-client:dev-latest"),
                                    string(name: "IMAGE_PMM_SERVER", value: "perconalab/pmm-server:dev-latest")
                                ],
                                propagate: true,
                                wait: true
                            )

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
