pipeline {
    agent {
        label 'cli'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }
    triggers {
        cron('H * * * *')
    }
    stages {
        stage('List instances') {
            steps {
                deleteDir()
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        copy_tags() {
                            local request=$1
                            sleep 5

                            # Get instance ID (plain string)
                            instance_id=$(
                                aws ec2 describe-spot-instance-requests \
                                    --region us-east-2 \
                                    --output text \
                                    --spot-instance-request-ids ${request} \
                                    --query 'SpotInstanceRequests[].InstanceId'
                            )

                            # Get safe tags (filter out eks, kubernetes.io/*, aws:*)
                            tags=$(aws ec2 describe-spot-instance-requests \
                                --region us-east-2 \
                                --spot-instance-request-ids ${request} \
                                --query 'SpotInstanceRequests[].Tags[?!(starts_with(Key, `eks`) || starts_with(Key, `kubernetes.io/`) || starts_with(Key, `aws:`))]' \
                                --output json
                            )

                            # Flatten nested lists to a single list
                            tags=$(echo "$tags" | jq 'flatten | map(select(.Key != null))')

                            # Skip if no tags to apply
                            if [ "$(echo "$tags" | jq 'length')" -eq 0 ]; then
                                echo "The instance ${instance_id} has no user-defined tags to copy!"
                                return
                            fi

                            # Apply tags
                            aws ec2 create-tags \
                                --region us-east-2 \
                                --resources $instance_id \
                                --tags "$tags"
                        }

                        is_shutdown_needed() {
                            local instance_id=$1
                            local request_id=$2
                            local days=$3
                            local days_ago=$(date --date=-${days}days +%Y-%m-%dT%H:%M 2>/dev/null || date -v-${days}d +%Y-%m-%dT%H:%M)

                            if [[ $days = 0 ]]; then
                                # unlimited uptime
                                return
                            fi

                            aws ec2 describe-instances \
                                --region us-east-2 \
                                --output text \
                                --instance-ids $instance_id \
                                --query "Reservations[].Instances[?LaunchTime<='${days_ago}'][].InstanceId"

                            aws ec2 describe-spot-instance-requests \
                                --region us-east-2 \
                                --output text \
                                --spot-instance-request-ids ${request_id} \
                                --query "SpotInstanceRequests[?CreateTime<='${days_ago}'].InstanceId"
                        }

                        get_sir_state() {
                            local sir=$1

                            grep "^$sir\\s" spot_details \
                                | cut -f 2
                        }
                        get_sir_name() {
                            local sir=$1

                            grep "^$sir\\s" spot_details \
                                | cut -f 3
                        }
                        get_sir_days() {
                            local sir=$1

                            local days=$(
                                grep "^$sir\\s" spot_details \
                                    | cut -f 4
                            )
                            if [[ -z $days ]]; then
                                echo None
                            else
                                echo $days
                            fi
                        }

                        main() {
                            echo -n > instances
                            echo -n > requests_to_terminate
                            echo -n > instances_to_terminate

                            # stopped as well: a disabled persistent request keeps its stopped VM and volumes until the TTL check reaps them
                            aws ec2 describe-instances \
                                --region us-east-2 \
                                --output text \
                                --query 'Reservations[].Instances[].{
                                    A_Name:[Tags[?Key==`Name`].Value][0][0],
                                    B_InstanceId:InstanceId,
                                    C_RequestId:SpotInstanceRequestId,
                                    D_Days: [Tags[?Key==`stop-after-days`].Value][0][0]
                                }' \
                                --filter Name=instance-state-name,Values=running,stopped \
                                | sort -n \
                                | tee init_instances

                            aws ec2 describe-spot-instance-requests \
                                --region us-east-2 \
                                --output text \
                                --spot-instance-request-ids $(cat init_instances | cut -f 3 | grep -v None | xargs echo) \
                                --query 'SpotInstanceRequests[].[
                                            SpotInstanceRequestId,
                                            State,
                                            [Tags[?Key==`Name`].Value][0][0],
                                            [Tags[?Key==`stop-after-days`].Value][0][0]]
                                ' \
                                | tee spot_details

                            while read -r name instance request days; do
                                state=$(get_sir_state "$request")
                                if [[ $name = None ]] && [[ $request != None ]]; then
                                    copy_tags "${request}"
                                    name=$(get_sir_name "${request}")
                                    days=$(get_sir_days "${request}")
                                fi

                                printf "%-40s\t%s\t%s\t%s\t%s\n" $name $instance $request $days $state >> instances
                                if [[ $state = cancelled ]]; then
                                    echo TERMINATE cancelled: $name
                                    echo ${instance} >> instances_to_terminate
                                fi
                                if [[ $days != None ]]; then
                                    if [[ -n $(is_shutdown_needed "${instance}" "${request}" "${days}") ]]; then
                                        echo TERMINATE days: $name
                                        echo ${request}  >> requests_to_terminate
                                        echo ${instance} >> instances_to_terminate
                                    else
                                        echo KEEP days: $name
                                    fi
                                fi
                            done < init_instances

                            # A persistent request with no tags never enters the loop above: nothing
                            # copies tags onto its instances, so an aborted build's request relaunches
                            # an untagged VM every time something terminates it. Cancel any such
                            # request older than an hour and terminate whatever it is running. Only
                            # requests with no tags at all: a request tagged by anyone is not ours to
                            # cancel, and this region is shared with other workloads.
                            # CreateTime is UTC, so the cutoff must be too (the TTL check above compares local time)
                            one_hour_ago=$(date -u --date=-1hour +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -v-1H +%Y-%m-%dT%H:%M:%S)
                            # written to the file directly: a failed describe must fail the build, not read as an empty sweep.
                            # disabled = a persistent request whose instance was stopped, it relaunches when re-enabled.
                            aws ec2 describe-spot-instance-requests \
                                --region us-east-2 \
                                --output text \
                                --filters Name=state,Values=open,active,disabled Name=type,Values=persistent \
                                --query "SpotInstanceRequests[?CreateTime<='${one_hour_ago}' && !Tags].[SpotInstanceRequestId, InstanceId]" \
                                > untagged_requests
                            cat untagged_requests
                            while read -r request instance; do
                                if [[ -z $request ]]; then
                                    continue
                                fi
                                # an instance somebody tagged by hand keeps its own TTL (the loop above
                                # already decided KEEP or TERMINATE for it), only an untagged one goes
                                # with its request. Checked live because a disabled request's instance is
                                # stopped, so it is not in init_instances, and cancelling the request
                                # would terminate it.
                                instance_name=None
                                if [[ $instance != None ]]; then
                                    instance_name=$(aws ec2 describe-instances \
                                        --region us-east-2 \
                                        --output text \
                                        --instance-ids "$instance" \
                                        --query 'Reservations[].Instances[].[Tags[?Key==`Name`].Value | [0]]' 2>/dev/null || echo None)
                                    instance_name=${instance_name:-None}
                                fi
                                if [[ $instance_name != None ]]; then
                                    echo "KEEP tagged instance $instance behind untagged request $request"
                                    continue
                                fi
                                echo TERMINATE untagged persistent request: $request
                                echo ${request} >> requests_to_terminate
                                if [[ $instance != None ]]; then
                                    echo ${instance} >> instances_to_terminate
                                fi
                            done < untagged_requests

                            sort instances
                            cat requests_to_terminate instances_to_terminate
                            wc -l requests_to_terminate instances_to_terminate
                        }

                        main
                    '''
                }
                stash includes: 'requests_to_terminate,instances_to_terminate', name: 'instances'
            }
        }
        stage('Check list') {
            steps {
                unstash 'instances'
                script {
                    def targets_count = sh(returnStdout: true, script: '''
                        cat requests_to_terminate instances_to_terminate | wc -l
                    ''').trim()
                    if (targets_count == '0') {
                        echo "WARNING: everything ok, skip terminate"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Terminate instances') {
            steps {
                unstash 'instances'
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        # either list can be empty on its own: a request with no live instance, or on-demand instances.
                        # A failed cancel fails the build: for a request with no instance it is the only action there is.
                        if grep -qv None requests_to_terminate; then
                            grep -v None requests_to_terminate | sort -u | xargs aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids
                        fi
                        if [ -s instances_to_terminate ]; then
                            sort -u instances_to_terminate | xargs aws ec2 --region us-east-2 terminate-instances --instance-ids
                        fi
                    '''
                }
            }
        }
    }
    post {
        success {
            slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00', message: "[${JOB_NAME}]: stop successful"
        }
        unstable {
            script {
                echo 'No instances reached TTL.'
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
