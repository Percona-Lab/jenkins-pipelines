pipeline {
    agent {
        label 'cli'
    }
    parameters {
        booleanParam(
            defaultValue: false,
            description: 'Only list the instances past their stop-after-days, do not terminate them',
            name: 'DRY_RUN')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('H * * * *')
    }
    stages {
        stage('Terminate expired instances') {
            steps {
                deleteDir()
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        # pmm3-ami-staging-start launches in us-east-1, which aws-staging-stop-robot never looks at.
                        # The region is shared with other teams, so only touch instances that carry both
                        # iit-billing-tag=pmm and a stop-after-days tag.
                        # written to the file directly: a failed describe must fail the build, not read as an empty sweep
                        aws ec2 describe-instances \
                            --region us-east-1 \
                            --output text \
                            --filters Name=instance-state-name,Values=running,stopped Name=tag:iit-billing-tag,Values=pmm Name=tag-key,Values=stop-after-days \
                            --query 'Reservations[].Instances[].[InstanceId, LaunchTime, Tags[?Key==`stop-after-days`] | [0].Value, Tags[?Key==`Name`] | [0].Value]' \
                            > instances

                        now=$(date -u +%s)
                        echo -n > instances_to_terminate
                        while read -r instance launched days name; do
                            if [[ ! $days =~ ^[0-9]+$ ]]; then
                                echo "SKIP $name ($instance): stop-after-days=$days is not a number"
                                continue
                            fi
                            if [[ $days = 0 ]]; then
                                # unlimited uptime
                                echo "KEEP forever: $name ($instance)"
                                continue
                            fi
                            # LaunchTime carries its UTC offset, so the epoch compare does not depend on the agent's TZ
                            if (( $(date --date="$launched" +%s) + days * 86400 <= now )); then
                                echo "TERMINATE after $days days: $name ($instance), launched $launched"
                                echo "$instance $name" >> instances_to_terminate
                            else
                                echo "KEEP for $days days: $name ($instance), launched $launched"
                            fi
                        done < instances

                        if [ ! -s instances_to_terminate ]; then
                            echo "No instances past their stop-after-days"
                        elif [ "$DRY_RUN" = true ]; then
                            echo "DRY_RUN: not terminating $(wc -l < instances_to_terminate) instance(s)"
                        else
                            cut -d ' ' -f 1 instances_to_terminate | xargs aws ec2 --region us-east-1 terminate-instances --instance-ids
                        fi
                    '''
                }
                script {
                    env.TERMINATED = sh(returnStdout: true, script: 'cat instances_to_terminate').trim()
                }
            }
        }
    }
    post {
        success {
            script {
                if (env.TERMINATED && !params.DRY_RUN) {
                    slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: terminated AMI staging instances past their stop-after-days, URL: ${BUILD_URL}\n${env.TERMINATED}"
                }
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, URL: ${BUILD_URL}"
        }
        cleanup {
            deleteDir()
        }
    }
}
