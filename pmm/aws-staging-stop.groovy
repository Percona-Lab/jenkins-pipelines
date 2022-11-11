pipeline {
    agent {
        label 'cli'
    }
    parameters {
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
                        REQUEST_ID=$(echo "${VMList}" | grep "${INPUT}" | awk '{print $2}' | cut -d '|' -f1)
                        INSTANCE_ID=$(echo "${VMList}" | grep "${INPUT}" | awk '{print $3}')
                        set -x
                        echo $REQUEST_ID
                        echo $INSTANCE_ID
                        if [ -z "$REQUEST_ID" -o -z "$INSTANCE_ID" ]; then
                            echo "Wrong or not enough parameters passed"
                            echo "REQUEST_ID: '$REQUEST_ID', INSTANCE_ID: '$INSTANCE_ID'"
                            exit 1
                        fi
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids $REQUEST_ID
                        aws ec2 --region us-east-2 terminate-instances --instance-ids $INSTANCE_ID
                    '''
                }
            }
        }
    }
}
