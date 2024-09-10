def call(String INSTANCE_TYPE, String SPOT_PRICE, VOLUME) {
    withCredentials([
        [$class: 'AmazonWebServicesCredentialsBinding',
        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
        credentialsId: 'pmm-staging-slave',
        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            export VM_NAME=\$(cat VM_NAME)
            export OWNER=\$(cat OWNER_FULL)
            export INSTANCE_TYPE=${INSTANCE_TYPE}
            export VOLUME=${VOLUME}
            export IMAGE_ID=\$(aws ec2 describe-images --owners self --filters "Name=tag:iit-billing-tag,Values=pmm-worker-3" "Name=architecture,Values=x86_64"  --region us-east-2 | jq -r '.Images[0].ImageId')

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

            PRICE_MULTIPLIER=0
            while true
            do
                if [ "$SPOT_PRICE" = "FAIR" ]; then
                    set +x
                    PRICE_HISTORY=\$(
                        aws ec2 describe-spot-price-history \
                            --instance-types \$INSTANCE_TYPE \
                            --region us-east-2 --output text \
                            --product-description "Linux/UNIX (Amazon VPC)"
                    )
                    export SPOT_PRICE=\$(echo \$PRICE_HISTORY | head -n 1 | awk '{ print \$5}')
                    set -x
                    # increase price by 15% each time
                    export SPOT_PRICE=\$(bc -l <<< "scale=8; \$SPOT_PRICE + ((\$SPOT_PRICE / 100) * (15 * \$PRICE_MULTIPLIER))" | sed 's/^\\./0./')
                    echo SET PRICE: \$SPOT_PRICE
                    echo \$SPOT_PRICE > SPOT_PRICE
                else
                    export SPOT_PRICE=${SPOT_PRICE}
                fi

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
                                    "VolumeType": "gp3"
                                }
                            }
                        ],
                        "EbsOptimized": false,
                        "ImageId": "IMAGE_ID",
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
                    | sed -e "s/IMAGE_ID/\${IMAGE_ID}/" \
                    > config.json

                REQUEST_ID=\$(
                    aws ec2 request-spot-instances \
                        --output text \
                        --region us-east-2 \
                        --cli-input-json file://config.json \
                        --query SpotInstanceRequests[].SpotInstanceRequestId
                )
                echo \$REQUEST_ID > REQUEST_ID
                ATTEMPTS=2
                until [ -s IP ]; do
                    sleep 5
                    aws ec2 describe-instances \
                        --filters "Name=spot-instance-request-id,Values=\${REQUEST_ID}" \
                        --query 'Reservations[].Instances[].PublicIpAddress' \
                        --output text \
                        --region us-east-2 \
                        | tee IP
                    ATTEMPTS=\$((ATTEMPTS-1))
                    if [ \$ATTEMPTS -eq 0 ]; then
                        break
                    fi
                done
                if [ -s IP ]; then
                    break
                else
                    aws ec2 --region us-east-2 cancel-spot-instance-requests --spot-instance-request-ids \$REQUEST_ID
                    PRICE_MULTIPLIER=\$((PRICE_MULTIPLIER+1))
                    continue
                fi
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

            # wait for the instance to get ready
            aws ec2 wait instance-status-ok \
                --instance-ids \$(cat ID)                      
        """
        env.SPOT_PRICE = sh(returnStdout: true, script: "cat SPOT_PRICE").trim()
        env.REQUEST_ID = sh(returnStdout: true, script: "cat REQUEST_ID").trim()
        env.IP = sh(returnStdout: true, script: "cat IP").trim()
        env.ID = sh(returnStdout: true, script: "cat ID").trim()
    }
}
