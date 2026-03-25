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
                    sudo wget -qO /opt/yq https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64
                    sudo chmod +x /opt/yq
                    """

                    script {
                        runEc2Check = (currentBuild.number % 4 == 0)

                        echo "Dec Build #${currentBuild.number} — runEc2Check: ${runEc2Check}"

                        if (runEc2Check) {
                            sh """
                            wget https://raw.githubusercontent.com/Percona-QA/package-testing/master/scripts/check-ec2-instances.sh
                            chmod +x check-ec2-instances.sh
                            """
                        }

                        sh """
                        wget https://raw.githubusercontent.com/Percona-QA/package-testing/master/scripts/check-ec2-instances-pgsql.sh                    
                        chmod +x check-ec2-instances-pgsql.sh
                        """


                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                            // Runs every 6 hrs (every build) — PGSQL checks
                            sh "bash -x ./check-ec2-instances-pgsql.sh"

                            env.destroyQaPgsql = sh(script: "cat ${WORKSPACE}/DESTROY-QA-PGSQL.txt", returnStdout: true).trim()
                            env.ovQaPgsqlTerminate = sh(script: "cat ${WORKSPACE}/overview-qa-pgsql-to-terminate.txt", returnStdout: true).trim()
                            env.stopQaPgsql = sh(script: "cat ${WORKSPACE}/STOP-QA-PGSQL.txt", returnStdout: true).trim()
                            env.ovQaPgsqlStop = sh(script: "cat ${WORKSPACE}/overview-qa-pgsql-to-stop.txt", returnStdout: true).trim()


                            // Runs every 24 hrs (every 4th build) — general EC2 checks
                            if (runEc2Check) {
                                sh " bash -x ./check-ec2-instances.sh"

                                env.OPALL = sh(script: "cat ${WORKSPACE}/OUTPUT-ALL.txt", returnStdout: true).trim()
                                env.ovall = sh(script: "cat ${WORKSPACE}/overview-all.txt", returnStdout: true).trim()
                                env.OPQA = sh(script: "cat ${WORKSPACE}/OUTPUT-QA.txt", returnStdout: true).trim()
                                env.ovqa = sh(script: "cat ${WORKSPACE}/overview-qa.txt", returnStdout: true).trim()
                            }
                        }

                        // PGSQL output (every build)
                        echo "PGSQL Instances to Terminate:\n${env.destroyQaPgsql}"
                        echo "PGSQL Terminate Overview:\n${env.ovQaPgsqlTerminate}"
                        echo "PGSQL Instances to Stop:\n${env.stopQaPgsql}"
                        echo "PGSQL Stop Overview:\n${env.ovQaPgsqlStop}"

                        sh """
                        sed -i '/has 0 INSTANCES WITH PGSQL MOLECULE QA TESTS/d' ${WORKSPACE}/overview-qa-pgsql-to-terminate.txt
                        sed -i '/has 0 INSTANCES WITH PGSQL MOLECULE QA TESTS/d' ${WORKSPACE}/overview-qa-pgsql-to-stop.txt
                        """


                        env.ovQaPgsqlTerminate = sh(script: "cat ${WORKSPACE}/overview-qa-pgsql-to-terminate.txt", returnStdout: true).trim()
                        env.ovQaPgsqlStop = sh(script: "cat ${WORKSPACE}/overview-qa-pgsql-to-stop.txt", returnStdout: true).trim()
                        env.ovQaPgsqlTerminateCount = sh(script: """ awk '{sum += \$4} END {print sum}' ${WORKSPACE}/overview-qa-pgsql-to-terminate.txt """, returnStdout: true).trim()
                        env.ovQaPgsqlStopCount = sh(script: """ awk '{sum += \$4} END {print sum}' ${WORKSPACE}/overview-qa-pgsql-to-stop.txt """, returnStdout: true).trim()


                        // EC2 general output (every 4th build only)
                        if (runEc2Check) {
                            echo "Print the OUTPUT ALL\n ${env.OPALL}"
                            echo "Print the overview all\n ${env.ovall}"
                            echo "Print the OUTPUT QA\n ${env.OPQA}"
                            echo "Print the overview qa\n ${env.ovqa}"

                            sh """
                            sed -i '/has 0 INSTANCES WITH MOLECULE QA TESTS/d' ${WORKSPACE}/overview-qa.txt
                            """
                            env.ovqa = sh(script: "cat ${WORKSPACE}/overview-qa.txt", returnStdout: true).trim()
                            echo "Print the overview qa after removing the 0 servers list in QA \n ${env.ovqa}"
                            env.ovqacount = sh(script: """ awk '{sum += \$4} END {print sum}' ${WORKSPACE}/overview-qa.txt """, returnStdout: true).trim()
                        }
                    }
            }
        }
    }



    post {
        always {
            archiveArtifacts artifacts: '*.txt', followSymlinks: false, allowEmptyArchive: true

            script {
                def buildNumber = currentBuild.number
                def jobName = env.JOB_NAME
                def artifactBaseUrl = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}/artifact"

                // === PGSQL: Runs every build (every 6 hrs) ===

                // Parse DESTROY-QA-PGSQL.txt — terminate PGSQL instances running >6 hrs
                def pgsqlTerminateRegion = ""
                def pgsqlTerminatePairs = []
                def terminateLines = readFile('DESTROY-QA-PGSQL.txt').readLines()

                terminateLines.each { line ->
                    def regionMatch = line =~ /-+Region\s+([a-z0-9-]+)\s+has/
                    if (regionMatch) {
                        pgsqlTerminateRegion = regionMatch[0][1]
                    }
                    def instanceMatch = line =~ /(i-[a-zA-Z0-9]+)/
                    if (instanceMatch) {
                        def instanceId = instanceMatch[0][1]
                        pgsqlTerminatePairs << [id: instanceId, region: pgsqlTerminateRegion]
                    }
                }

                // Parse STOP-QA-PGSQL.txt — stop PGSQL based on the keys instances running >12 hrs
                def pgsqlStopRegion = ""
                def pgsqlStopPairs = []
                def stopLines = readFile('STOP-QA-PGSQL.txt').readLines()

                stopLines.each { line ->
                    def regionMatch = line =~ /-+Region\s+([a-z0-9-]+)\s+has/
                    if (regionMatch) {
                        pgsqlStopRegion = regionMatch[0][1]
                    }
                    def instanceMatch = line =~ /(i-[a-zA-Z0-9]+)/
                    if (instanceMatch) {
                        def instanceId = instanceMatch[0][1]
                        pgsqlStopPairs << [id: instanceId, region: pgsqlStopRegion]
                    }
                }

                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                    // Terminate PGSQL instances (>6 hrs)
                    pgsqlTerminatePairs.each { pair ->
                        echo "Terminating PGSQL instance ${pair.id} in region ${pair.region}"
                        sh "aws ec2 terminate-instances --instance-ids ${pair.id} --region ${pair.region}"
                        echo "PGSQL instance ${pair.id} in region ${pair.region} terminated."
                    }

                    // Stop PGSQL instances (>12 hrs)
                    pgsqlStopPairs.each { pair ->
                        echo "Stopping PGSQL instance ${pair.id} in region ${pair.region}"
                        sh "aws ec2 stop-instances --instance-ids ${pair.id} --region ${pair.region}"
                        echo "PGSQL instance ${pair.id} in region ${pair.region} stopped."
                    }

                    // === EC2 General: Runs every 4th build (every 24 hrs) ===
                    if (runEc2Check) {
                        def region = ""
                        def instanceIdRegionPairs = []
                        def lines = readFile('OUTPUT-QA.txt').readLines()

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

                        instanceIdRegionPairs.each { pair ->
                            echo "Terminating instance ${pair.id} in region ${pair.region}"
                            sh "aws ec2 terminate-instances --instance-ids ${pair.id} --region ${pair.region}"
                            echo "Instance ${pair.id} in region ${pair.region} terminated."
                        }
                    }
                }

                // === Slack notifications ===

                // PGSQL Slack (every build)
                if (pgsqlTerminatePairs.size() > 0) {
                    slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """
                    Terminated PGSQL instances running since past 6 hours:
                    ---------------------------------------------------
                    ${env.ovQaPgsqlTerminate}
                    ---------------------------------------------------
                    ${artifactBaseUrl}/DESTROY-QA-PGSQL.txt
                    """
                }

                if (pgsqlStopPairs.size() > 0) {
                    slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """
                    Stopped PGSQL instances running since past 12 hours:
                    ---------------------------------------------------
                    ${env.ovQaPgsqlStop}
                    ---------------------------------------------------
                    ${artifactBaseUrl}/STOP-QA-PGSQL.txt
                    """
                }

                // EC2 General Slack (every 4th build)
                if (runEc2Check) {
                    slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """
                    ${env.ovall}
                    =========================
                    ${artifactBaseUrl}/OUTPUT-ALL.txt is the url for the detailed info of all running instances
                    =========================
                    """

                    if ("${env.ovqacount}".toInteger() >= 1) {
                        slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """
                        Terminated instances with molecule QA Tests up since past 2 days:
                        ---------------------------------------------------
                        ${env.ovqa}
                        ---------------------------------------------------
                        ${artifactBaseUrl}/OUTPUT-QA.txt
                        """
                    } else {
                        echo "No QA servers are running since past 2 days"
                    }
                }
            }
        }
    }


}
