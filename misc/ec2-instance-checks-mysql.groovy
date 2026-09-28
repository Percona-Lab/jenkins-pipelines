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
                        sh """
                        wget https://raw.githubusercontent.com/Percona-QA/package-testing/master/scripts/check-ec2-instances.sh
                        chmod +x check-ec2-instances.sh
                        """


                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                            // Preflight: fail the stage now if the AWS CLI/credentials are broken.
                            // Otherwise a failing scan exits 0, reports "0 INSTANCES" for every
                            // region, and the job posts a clean estate on a green build.
                            sh "aws sts get-caller-identity"

                            // Runs every 48 hrs — general EC2 checks
                            sh " bash -x ./check-ec2-instances.sh"

                            env.OPALL = sh(script: "cat ${WORKSPACE}/OUTPUT-ALL.txt", returnStdout: true).trim()
                            env.ovall = sh(script: "cat ${WORKSPACE}/overview-all.txt", returnStdout: true).trim()
                            env.OPQA = sh(script: "cat ${WORKSPACE}/OUTPUT-QA.txt", returnStdout: true).trim()
                            env.ovqa = sh(script: "cat ${WORKSPACE}/overview-qa.txt", returnStdout: true).trim()
                        }

                        // EC2 general output (every 48 hrs)
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

        // Mutations live in their own stage, NOT in post{always}. A stage only runs
        // when every earlier stage succeeded, so a failed or aborted scan skips the
        // terminate calls instead of firing them on stale/partial data.
        stage("Terminate instances") {

            steps {
                    script {
                        def buildNumber = currentBuild.number
                        def jobName = env.JOB_NAME
                        def artifactBaseUrl = "${env.JENKINS_URL}/job/${jobName}/${buildNumber}/artifact"

                        // Instance-id regex is anchored to the two valid EC2 id lengths (8 or 17
                        // hex chars) with word boundaries, so a Name tag like "multi-parallel"
                        // no longer parses as the bogus id "i-parallel".
                        def instanceIdPattern = /\b(i-(?:[0-9a-f]{8}|[0-9a-f]{17}))\b/

                        // === EC2 General: Runs every 48 hrs ===
                        // Guard with fileExists: a missing file (scan wrote nothing) would make
                        // readFile throw and skip the terminate loop and Slack notifications below.
                        def instanceIdRegionPairs = []
                        if (fileExists('OUTPUT-QA.txt')) {
                            def region = ""
                            readFile('OUTPUT-QA.txt').readLines().each { line ->
                                def regionMatch = line =~ /-+Region\s+([a-z0-9-]+)\s+has/
                                if (regionMatch) {
                                    region = regionMatch[0][1]
                                }
                                def instanceMatch = line =~ instanceIdPattern
                                if (instanceMatch) {
                                    def instanceId = instanceMatch[0][1]
                                    instanceIdRegionPairs << [id: instanceId, region: region]
                                }
                            }
                        } else {
                            echo "OUTPUT-QA.txt not found — skipping QA terminate parsing"
                        }

                        // Track IDs whose AWS call failed so the loop can't report a clean
                        // success when instances were actually left running.
                        def terminateFailed = []

                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                            // Wrap each call so one bad instance can't skip the rest or the Slack
                            // notification. Catch only AbortException (a non-zero sh/AWS exit) so a
                            // user abort — FlowInterruptedException, which extends InterruptedException
                            // — still propagates and halts the loop.
                            instanceIdRegionPairs.each { pair ->
                                try {
                                    echo "Terminating instance ${pair.id} in region ${pair.region}"
                                    sh "aws ec2 terminate-instances --instance-ids ${pair.id} --region ${pair.region}"
                                    echo "Instance ${pair.id} in region ${pair.region} terminated."
                                } catch (hudson.AbortException err) {
                                    echo "WARNING: failed to terminate instance ${pair.id} in region ${pair.region}: ${err}"
                                    terminateFailed << pair.id
                                }
                            }
                        }

                        // Any failed AWS call means instances may still be running — mark the
                        // build UNSTABLE instead of letting it report green.
                        if (terminateFailed) {
                            currentBuild.result = 'UNSTABLE'
                        }

                        // === Slack notifications ===

                        // EC2 General Slack (every 48 hrs) — the estate overview heartbeat
                        slackSend channel: '#dev-server-qa', color: '#DEFF13', message: """
                        ${env.ovall}
                        GENERAL
                        =========================
                        ${artifactBaseUrl}/OUTPUT-ALL.txt is the url for the detailed info of all running instances
                        =========================
                        """

                        if ((env.ovqacount ?: '0').toInteger() >= 1) {
                            def terminatedOk = instanceIdRegionPairs.size() - terminateFailed.size()
                            def qaMsg = """
                            Terminated ${terminatedOk}/${instanceIdRegionPairs.size()} instances with molecule QA Tests up since past 2 days:
                            ---------------------------------------------------
                            ${env.ovqa}
                            ---------------------------------------------------
                            ${artifactBaseUrl}/OUTPUT-QA.txt
                            """
                            if (terminateFailed) {
                                qaMsg += "\n                            FAILED to terminate (still running): ${terminateFailed.join(', ')}"
                            }
                            slackSend channel: '#dev-server-qa', color: terminateFailed ? '#FF0000' : '#DEFF13', message: qaMsg
                        } else {
                            echo "No QA servers are running since past 2 days"
                        }
                    }
            }
        }
    }



    post {
        always {
            archiveArtifacts artifacts: '*.txt', followSymlinks: false, allowEmptyArchive: true
        }
    }


}
