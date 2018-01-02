def VMList = ''

pipeline {
    agent {
        label 'virtualbox'
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
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        VMList = sh returnStdout: true, script: '''
                            set +o xtrace

                            aws ec2 describe-instances \
                                --output text \
                                --region us-east-2 \
                                --filters "Name=tag:iit-billing-tag,Values=pmm-staging" \
                                          "Name=instance-state-name,Values=running" \
                                --query 'Reservations[].Instances[].[
                                    SpotInstanceRequestId,
                                    InstanceId,
                                    PublicIpAddress,
                                    Tags[?Key==`Name`].Value,
                                    Tags[?Key==`owner`].Value
                                ]' | perl -p -e 's/\n/\t/; s/sir/\nsir/'
                        '''
                    }
                    if ( "${VM}" == "list-all-vms" ) {
                        echo """
                            What VM do you want to stop?
                            please copy VM name below and press 'Input requested' button
                        """
                        echo "${VMList}"
                        timeout(time:10, unit:'MINUTES') {
                            VM = input message: 'What VM do you want to stop?', parameters: [string(defaultValue: '', description: '', name: 'Name or IP')]
                        }
                    }
                    if ( !VMList.toLowerCase().contains(VM.toLowerCase())) {
                        echo 'Unknown VM'
                        error 'Unknown VM'
                    }
                }
            }
        }

        stage('Destroy VM') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        REQUEST_ID=\$(echo '${VMList}' | grep '${VM}' | awk '{print\$1}')
                        INSTANCE_ID=\$(echo '${VMList}' | grep '${VM}' | awk '{print\$2}')
                        aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids \$REQUEST_ID
                        aws ec2 --region us-east-2 terminate-instances --instance-ids \$INSTANCE_ID
                    """
                }
            }
        }
    }
}
