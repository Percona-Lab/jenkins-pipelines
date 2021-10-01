def call(String INSTANCE_TYPE, String SPOT_PRICE, VOLUME) {
   withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            export VM_NAME=\$(cat VM_NAME)
            export OWNER=\$(cat OWNER_FULL)
            export INSTANCE_TYPE=${INSTANCE_TYPE}
            export SPOT_PRICE=${SPOT_PRICE}
            export VOLUME=${VOLUME}
            export SUBNET=\$(
                aws ec2 describe-subnets \
                    --region us-east-2 \
                    --output text \
                    --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                    --query 'Subnets[].SubnetId' \
                    | tr '\t' '\n' \
                    | sort --random-sort \
                    | head -1
            )
            export SG1=\$(
                aws ec2 describe-security-groups \
                    --region us-east-2 \
                    --output text \
                    --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                              "Name=group-name,Values=HTTP" \
                    --query 'SecurityGroups[].GroupId'
            )
            export SG2=\$(
                aws ec2 describe-security-groups \
                    --region us-east-2 \
                    --output text \
                    --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                              "Name=group-name,Values=SSH" \
                    --query 'SecurityGroups[].GroupId'
            )

            echo '{
                "DryRun": false,
                "InstanceCount": 1,
                "InstanceInterruptionBehavior": "terminate",
                "LaunchSpecification": {
                    "BlockDeviceMappings": [
                        {
                            "DeviceName": "/dev/xvda",
                            "Ebs": {
                                "DeleteOnTermination": true,
                                "VolumeSize": VOLUME,
                                "VolumeType": "gp2"
                            }
                        }
                    ],
                    "EbsOptimized": false,
                    "ImageId": "ami-00dfe2c7ce89a450b",
                    "UserData": "c3VkbyB5dW0gaW5zdGFsbCAteSBqYXZhLTEuOC4wLW9wZW5qZGsKCnN1ZG8gL3Vzci9zYmluL2FsdGVybmF0aXZlcyAtLXNldCBqYXZhIC91c3IvbGliL2p2bS9qcmUtMS44LjAtb3Blbmpkay54ODZfNjQvYmluL2phdmEKCnN1ZG8gL3Vzci9zYmluL2FsdGVybmF0aXZlcyAtLXNldCBqYXZhYyAvdXNyL2xpYi9qdm0vanJlLTEuOC4wLW9wZW5qZGsueDg2XzY0L2Jpbi9qYXZhYwoKc3VkbyB5dW0gcmVtb3ZlIGphdmEtMS43Cg==",
                    "InstanceType": "INSTANCE_TYPE",
                    "KeyName": "jenkins",
                    "Monitoring": {
                        "Enabled": false
                    },
                    "IamInstanceProfile": {
                        "Name": "pmm-staging-slave"
                    },
                    "SecurityGroupIds": [
                        "security-group-id-1",
                        "security-group-id-2"
                    ],
                    "SubnetId": "subnet-id"
                },
                "SpotPrice": "SPOT_PRICE",
                "Type": "persistent"
            }' \
                | sed -e "s/subnet-id/\${SUBNET}/" \
                | sed -e "s/security-group-id-1/\${SG1}/" \
                | sed -e "s/security-group-id-2/\${SG2}/" \
                | sed -e "s/SPOT_PRICE/\${SPOT_PRICE}/" \
                | sed -e "s/INSTANCE_TYPE/\${INSTANCE_TYPE}/" \
                | sed -e "s/VOLUME/\${VOLUME}/" \
                > config.json

            REQUEST_ID=\$(
                aws ec2 request-spot-instances \
                    --output text \
                    --region us-east-2 \
                    --cli-input-json file://config.json \
                    --query SpotInstanceRequests[].SpotInstanceRequestId
            )
            echo \$REQUEST_ID > REQUEST_ID

            until [ -s IP ]; do
                sleep 5
                aws ec2 describe-instances \
                    --filters "Name=spot-instance-request-id,Values=\${REQUEST_ID}" \
                    --query 'Reservations[].Instances[].PublicIpAddress' \
                    --output text \
                    --region us-east-2 \
                    | tee IP
            done

            aws ec2 describe-instances \
                --filters "Name=spot-instance-request-id,Values=\${REQUEST_ID}" \
                --query 'Reservations[].Instances[].InstanceId' \
                --output text \
                --region us-east-2 \
                | tee ID

            VOLUMES=\$(
                aws ec2 describe-instances \
                    --region us-east-2 \
                    --output text \
                    --instance-ids \$(cat ID) \
                    --query 'Reservations[].Instances[].BlockDeviceMappings[].Ebs.VolumeId'
            )

            aws ec2 create-tags  \
                --region us-east-2 \
                --resources \$REQUEST_ID \$(cat ID) \$VOLUMES \
                --tags Key=Name,Value=\$VM_NAME \
                       Key=iit-billing-tag,Value=pmm-staging \
                       Key=stop-after-days,Value=${DAYS} \
                       Key=owner,Value=\$OWNER
        """
    }
}
