pipeline {
    agent {
        label params.USE_ONDEMAND ? 'cli-ondemand' : 'cli'
    }
    parameters {
        booleanParam(
            defaultValue: false,
            description: 'Use on-demand instances instead of spot (for RC/Release testing)',
            name: 'USE_ONDEMAND')
        string(
            defaultValue: 'list-all-vms',
            description: 'Name or IP of VM to stop. Also you can set "list-all-vms" value, in this case list of current VMs will be shown and pipeline will ask you VM again.',
            name: 'VM')
    }
    stages {
        stage('Ask input') {
            steps {
                script {
                    withCredentials([[
                                $class: 'AmazonWebServicesCredentialsBinding',
                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                credentialsId: 'pmm-staging-slave',
                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                        env.VMList = sh returnStdout: true, script: '''
                            set +o xtrace

                            aws ec2 describe-instances \
                                --output table \
                                --region us-east-2 \
                                --filters "Name=tag:iit-billing-tag,Values=pmm-staging" \
                                          "Name=instance-state-name,Values=running" \
                                --query 'Reservations[].Instances[].{
                                    A_RequestId:SpotInstanceRequestId,
                                    InstanceId:InstanceId,
                                    IpAddress:PublicIpAddress,
                                    Name:[Tags[?Key==`Name`].Value][0][0],
                                    Owner:[Tags[?Key==`owner`].Value][0][0]
                                }'
                        '''
                    }
                                        
                    if ( params.VM == "list-all-vms" ) {
                        echo """
                            What VM do you want to stop?
                            Please copy a VM name or IP from below and press 'Proceed'.
                        """
                        echo "${VMList}"

                        timeout(time: 10, unit: 'MINUTES') {
                            def NAME_OR_IP = input message: 'What VM do you want to stop?',
                                 parameters: [
                                    string(defaultValue: '',
                                    description: '',
                                    name: 'Name or IP')
                                ]
                            echo "VM passed: ${NAME_OR_IP}"
                            env.INPUT = NAME_OR_IP.trim()
                        }
                    } else {
                        echo "${VMList}"
                        env.INPUT = params.VM.trim()
                    }
                    if (!env.VMList.toLowerCase().contains(env.INPUT.toLowerCase())) {
                        error 'Unknown VM'
                    }
                }
            }
        }

        stage('Destroy VM') {
            steps {
                withCredentials([
                            [$class: 'AmazonWebServicesCredentialsBinding',
                            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                            credentialsId: 'pmm-staging-slave',
                            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit
                        set +x
                        # Ask AWS for the ids rather than parsing the table above. With a
                        # single running instance `--output table` switches to a vertical
                        # key/value layout, so grepping it for the VM name matched the
                        # "Name" row and cut took the column label as the request id:
                        # cancel-spot-instance-requests was handed the literal "Name",
                        # errexit aborted, and terminate-instances never ran. That leaked
                        # the last lane of every nightly, which is why it stayed hidden.
                        # One line per instance has no layout to get wrong.
                        MATCHES=$(
                            aws ec2 describe-instances                                 --region us-east-2                                 --output text                                 --filters "Name=tag:iit-billing-tag,Values=pmm-staging"                                           "Name=instance-state-name,Values=running"                                 --query 'Reservations[].Instances[].[SpotInstanceRequestId,InstanceId,PublicIpAddress,[Tags[?Key==`Name`].Value][0][0]]'                             | grep -F "${INPUT}" || true
                        )
                        COUNT=$(printf '%s' "$MATCHES" | grep -c . || true)
                        set -x
                        if [ "$COUNT" != "1" ]; then
                            echo "Expected exactly one running pmm-staging instance matching '${INPUT}', found ${COUNT}:"
                            printf '%s\n' "$MATCHES"
                            exit 1
                        fi
                        REQUEST_ID=$(printf '%s' "$MATCHES" | awk '{print $1}')
                        INSTANCE_ID=$(printf '%s' "$MATCHES" | awk '{print $2}')
                        echo $REQUEST_ID
                        echo $INSTANCE_ID
                        # On-demand instances have no spot request -- AWS renders that as the
                        # literal "None" in the table above, not an empty string.
                        if [ "$REQUEST_ID" != "None" ]; then
                            aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids $REQUEST_ID
                        fi
                        aws ec2 --region us-east-2 terminate-instances --instance-ids $INSTANCE_ID
                    '''
                }
            }
        }
    }
}
