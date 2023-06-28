library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
    }

    stages {


        stage("Cleanup Workspace") {
            steps {                
                sh "sudo rm -rf ${WORKSPACE}/*"
            }
        }

        stage("Checks") {

            

            steps {              
                    sh """
                    set +xe 
                    sudo yum install jq -y
                    wget https://raw.githubusercontent.com/Percona-QA/package-testing/master/scripts/check-ec2-instances.sh
                    sudo wget -qO /opt/yq https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64
                    sudo chmod +x /opt/yq
                    chmod +x check-ec2-instances.sh
                    """
             script{


                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                        sh "./check-ec2-instances.sh"
                        
                        env.OP = sh(script: "cat ${WORKSPACE}/OUTPUT.txt",returnStdout: true).trim()
                        
                        env.ov = sh(script: "cat ${WORKSPACE}/overview.txt",returnStdout: true).trim()
                    
                    }
                           echo "Print the OUTPUT\n ${env.OP}"
                           echo "Print the overview\n ${env.ov}"       
             }

            }
        }
        
    }


    post {

        always {

                script{
                    def buildNumber = currentBuild.number
                    def jobName = env.JOB_NAME
                    def artifactPath = "OUTPUT.txt"  // Relative path to the archived artifact
                    def artifactUrl = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}/artifact/${artifactPath}"

                    archiveArtifacts artifacts: 'OUTPUT.txt' , followSymlinks: false
                    slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """

=========================
${env.ov}
=========================
${artifactUrl} is the url for the detailed info
=========================
=========================


"""
                
                
                }


             }
        }

    }
