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
                        
                        env.OPALL = sh(script: "cat ${WORKSPACE}/OUTPUT-ALL.txt",returnStdout: true).trim()
                        env.ovall = sh(script: "cat ${WORKSPACE}/overview-all.txt",returnStdout: true).trim()
                        env.OPQA = sh(script: "cat ${WORKSPACE}/OUTPUT-QA.txt",returnStdout: true).trim()
                        env.ovqa = sh(script: "cat ${WORKSPACE}/overview-qa.txt",returnStdout: true).trim()
                    }
                           echo "Print the OUTPUT ALL\n ${env.OPALL}"
                           echo "Print the overview all\n ${env.ovall}"
                           echo "Print the OUTPUT QA\n ${env.OPQA}"
                           echo "Print the overview qa\n ${env.ovqa}"

                        sh """
                        sed -i '/has 0 INSTANCES WITH MOLECULE QA TESTS/d' ${WORKSPACE}/overview-qa.txt
                        """
                            env.ovqa = sh(script: "cat ${WORKSPACE}/overview-qa.txt",returnStdout: true).trim()
                            echo "Print the overview qa after removing the 0 servers list in QA \n ${env.ovqa}"
                            env.ovqacount=sh(script: """ awk '{sum += \$4} END {print sum}' ${WORKSPACE}/overview-qa.txt """,returnStdout: true).trim()
             }

            }
        }
        
    }


    post {
        always {
                script{
                    def buildNumber = currentBuild.number
                    def jobName = env.JOB_NAME
                    def artifactPathall = "OUTPUT-ALL.txt"  // Relative path to the archived artifact
                    def artifactPathqa = "OUTPUT-QA.txt"  // Relative path to the archived artifact
                    def artifactUrlall = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}/artifact/${artifactPathall}"
                    def artifactUrlqa = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}/artifact/${artifactPathqa}"
                    archiveArtifacts artifacts: '*.txt' , followSymlinks: false

                    def region = ""
                    def instanceIdRegionPairs = []
                    def lines = readFile(artifactPathqa).readLines()

                    lines.each { line ->

                        def regionMatch = line =~ /-+Region\s+([a-z0-9-]+)\s+has/
                        if (regionMatch) {
                            region = regionMatch[0][1]
                        }

                        def instanceMatch = line =~ /(i-[a-zA-Z0-9]+)/
                        if (instanceMatch) {
                            def instanceId = instanceMatch[0][1]
                            instanceIdRegionPairs << [id: instanceId, region: region]
                        }
                    }

                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                        instanceIdRegionPairs.each { pair ->
                            def id = pair.id
                            def regionForId = pair.region
                            echo "Instance ${id} in region ${regionForId}"
                            echo "Preparing to terminate instance: ${id} in region: ${regionForId}"
                            sh "aws ec2 terminate-instances --instance-ids ${id} --region ${regionForId}"
                            echo "Instance ${id} in region ${regionForId} terminated."
                        }

                    }

                    slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """

                    ${env.ovall}
                    =========================
                    ${artifactUrlall} is the url for the detailed info of all running instances
                    =========================
                    =========================

                    """
                                echo "${env.ovqacount} is word count of up qa server regions"

                    if("${env.ovqacount}" >= 1){
                        slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """

                        Following are the Instances with molecule QA Tests up since past 2 days
                        ---------------------------------------------------
                        ${env.ovqa}
                        ---------------------------------------------------
                        ${artifactUrlqa} is the url for the detailed info of instances with QA Tests

                        """

                        slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """

                        Deleted the following Instances with molecule QA Tests that were up since past 2 days
                        ---------------------------------------------------
                        ${env.ovqa}
                        ---------------------------------------------------
                        ${artifactUrlqa} is the url for the detailed info of instances which were deleted
                        """

                    }else{
                            echo "No QA servers are running since past 2 days"
                    }
                }
             }
        }
    }
