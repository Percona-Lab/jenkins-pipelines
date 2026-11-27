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
        timeout(time: 30, unit: 'MINUTES')
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
                    sudo wget -qO /opt/yq https://github.com/mikefarah/yq/releases/download/v4.44.6/yq_linux_amd64
                    echo "0c2b24e645b57d8e7c0566d18643a6d4f5580feeea3878127354a46f2a1e4598  /opt/yq" | sha256sum -c -
                    sudo chmod +x /opt/yq
                    """

                    script {
                        sh """
                        wget https://raw.githubusercontent.com/Percona-QA/package-testing/refs/heads/master/scripts/check-ec2-instances-pgsql.sh
                        chmod +x check-ec2-instances-pgsql.sh
                        """


                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                            // Preflight: fail the stage now if the AWS CLI/credentials are broken.
                            // Otherwise a failing scan exits 0, reports "0 INSTANCES" for every
                            // region, and the job posts a clean estate on a green build.
                            sh "aws sts get-caller-identity"

                            // Runs every 6 hrs — PGSQL checks
                            sh "bash -x ./check-ec2-instances-pgsql.sh"

                            env.destroyQaPgsql = sh(script: "cat ${WORKSPACE}/DESTROY-QA-PGSQL.txt", returnStdout: true).trim()
                            env.ovQaPgsqlTerminate = sh(script: "cat ${WORKSPACE}/overview-qa-pgsql-to-terminate.txt", returnStdout: true).trim()
                            env.stopQaPgsql = sh(script: "cat ${WORKSPACE}/STOP-QA-PGSQL.txt", returnStdout: true).trim()
                            env.ovQaPgsqlStop = sh(script: "cat ${WORKSPACE}/overview-qa-pgsql-to-stop.txt", returnStdout: true).trim()
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
                    }
            }
        }

        // Terminate/stop run in a stage after the scan, so they execute only when the scan
        // stage succeeded; a failed scan skips this stage instead of acting on partial data.
        stage("Terminate & Stop PGSQL instances") {

            steps {
                    script {
                        def buildNumber = currentBuild.number
                        def jobName = env.JOB_NAME
                        def artifactBaseUrl = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}/artifact"

                        // Matches only a line that is exactly an InstanceId YAML row ("  - i-<id>"),
                        // so Name/job-name values that embed an id-like substring (e.g.
                        // "clone-of-i-0f00ba4d00000c0de-ppg") are not read as instance ids.
                        def instanceIdPattern = /^\s*-\s+(i-(?:[0-9a-f]{8}|[0-9a-f]{17}))\s*$/

                        // Parse DESTROY-QA-PGSQL.txt into (id, region) pairs: PGSQL instances older than 6 h.
                        def pgsqlTerminatePairs = []
                        def pgsqlTerminateRegion = ""
                        readFile('DESTROY-QA-PGSQL.txt').readLines().each { line ->
                            def regionMatch = line =~ /-+Region\s+([a-z0-9-]+)\s+has/
                            if (regionMatch) {
                                pgsqlTerminateRegion = regionMatch[0][1]
                            }
                            def instanceMatch = line =~ instanceIdPattern
                            if (instanceMatch) {
                                def instanceId = instanceMatch[0][1]
                                pgsqlTerminatePairs << [id: instanceId, region: pgsqlTerminateRegion]
                            }
                        }

                        // Parse STOP-QA-PGSQL.txt into (id, region) pairs: key-owned PGSQL instances older than 12 h.
                        def pgsqlStopPairs = []
                        def pgsqlStopRegion = ""
                        readFile('STOP-QA-PGSQL.txt').readLines().each { line ->
                            def regionMatch = line =~ /-+Region\s+([a-z0-9-]+)\s+has/
                            if (regionMatch) {
                                pgsqlStopRegion = regionMatch[0][1]
                            }
                            def instanceMatch = line =~ instanceIdPattern
                            if (instanceMatch) {
                                def instanceId = instanceMatch[0][1]
                                pgsqlStopPairs << [id: instanceId, region: pgsqlStopRegion]
                            }
                        }

                        // Track IDs whose AWS call failed so the loops can't report a clean
                        // success when instances were actually left running.
                        def pgsqlTerminateFailed = []
                        def pgsqlStopFailed = []

                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                            // Terminate PGSQL instances (>6 hrs)
                            // Wrap each call so a single failed instance (already-terminated,
                            // throttled, wrong region) can't skip the remaining pairs, the stop
                            // loop below, or the Slack notifications. Catch only AbortException
                            // (a non-zero sh/AWS exit) so a user abort — FlowInterruptedException,
                            // which extends InterruptedException — still propagates and halts the loop.
                            pgsqlTerminatePairs.each { pair ->
                                try {
                                    echo "Terminating PGSQL instance ${pair.id} in region ${pair.region}"
                                    sh "aws ec2 terminate-instances --instance-ids ${pair.id} --region ${pair.region}"
                                    echo "PGSQL instance ${pair.id} in region ${pair.region} terminated."
                                } catch (hudson.AbortException err) {
                                    echo "WARNING: failed to terminate PGSQL instance ${pair.id} in region ${pair.region}: ${err}"
                                    pgsqlTerminateFailed << pair.id
                                }
                            }

                            // Stop PGSQL instances (>12 hrs)
                            pgsqlStopPairs.each { pair ->
                                try {
                                    echo "Stopping PGSQL instance ${pair.id} in region ${pair.region}"
                                    sh "aws ec2 stop-instances --instance-ids ${pair.id} --region ${pair.region}"
                                    echo "PGSQL instance ${pair.id} in region ${pair.region} stopped."
                                } catch (hudson.AbortException err) {
                                    echo "WARNING: failed to stop PGSQL instance ${pair.id} in region ${pair.region}: ${err}"
                                    pgsqlStopFailed << pair.id
                                }
                            }
                        }

                        // Any failed AWS call means instances may still be running — mark the
                        // build UNSTABLE instead of letting it report green.
                        if (pgsqlTerminateFailed || pgsqlStopFailed) {
                            currentBuild.result = 'UNSTABLE'
                        }

                        // === Slack notifications ===
                        // Only notify when there was something to act on; the empty case just
                        // echoes so we don't post no-op messages to the channel four times a day.

                        if (pgsqlTerminatePairs.size() > 0) {
                            def terminatedOk = pgsqlTerminatePairs.size() - pgsqlTerminateFailed.size()
                            def terminateMsg = """
                            Terminated ${terminatedOk}/${pgsqlTerminatePairs.size()} PGSQL instances running since past 6 hours:
                            ---------------------------------------------------
                            ${env.ovQaPgsqlTerminate}
                            ---------------------------------------------------
                            ${artifactBaseUrl}/DESTROY-QA-PGSQL.txt
                            """
                            if (pgsqlTerminateFailed) {
                                terminateMsg += "\n                            FAILED to terminate (still running): ${pgsqlTerminateFailed.join(', ')}"
                            }
                            slackSend channel: '#dev-server-qa', color: pgsqlTerminateFailed ? '#FF0000' : '#DEFF13', message: terminateMsg
                        } else {
                            echo "No PGSQL instances to terminate (running > 6 hrs)"
                        }

                        if (pgsqlStopPairs.size() > 0) {
                            def stoppedOk = pgsqlStopPairs.size() - pgsqlStopFailed.size()
                            def stopMsg = """
                            Stopped ${stoppedOk}/${pgsqlStopPairs.size()} PGSQL instances running since past 12 hours:
                            ---------------------------------------------------
                            ${env.ovQaPgsqlStop}
                            ---------------------------------------------------
                            ${artifactBaseUrl}/STOP-QA-PGSQL.txt
                            """
                            if (pgsqlStopFailed) {
                                stopMsg += "\n                            FAILED to stop (still running): ${pgsqlStopFailed.join(', ')}"
                            }
                            slackSend channel: '#dev-server-qa', color: pgsqlStopFailed ? '#FF0000' : '#DEFF13', message: stopMsg
                        } else {
                            echo "No PGSQL instances to stop (running > 12 hrs)"
                        }
                    }
            }
        }
    }



    post {
        always {
            archiveArtifacts artifacts: '*.txt', followSymlinks: false, allowEmptyArchive: true
        }
        unsuccessful {
            slackSend channel: '#dev-server-qa', color: '#FF0000', message: "ec2-instance-checks-pgsql ${currentBuild.currentResult}: the EC2 reaper did not complete — leaked instances may still be running. ${env.BUILD_URL}"
        }
    }


}
