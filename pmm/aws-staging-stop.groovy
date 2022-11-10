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
                    
                    echo "\n${VMList}"
                    
                    if ( params.VM == "list-all-vms" ) {
                        echo """
                            What VM do you want to stop?
                            please copy VM name below and press 'Input requested' button
                        """
                        timeout(time:10, unit:'MINUTES') {
                            VM = input message: 'What VM do you want to stop?',
                                 parameters: [string(defaultValue: '',
                                 description: '',
                                 name: 'Name or IP')]
                        }
                    }
                    if (!env.VMList.toLowerCase().contains(VM.toLowerCase())) {
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
                        REQUEST_ID=$(echo "${VMList}" | grep "${VM}" | awk '{print $2}' | cut -d '|' -f1)
                        INSTANCE_ID=$(echo "${VMList}" | grep "${VM}" | awk '{print $3}')
                        set -x
                        echo $REQUEST_ID
                        echo $INSTANCE_ID
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids $REQUEST_ID
                        aws ec2 --region us-east-2 terminate-instances --instance-ids $INSTANCE_ID
                    '''
                }
            }
        }
    }
}
