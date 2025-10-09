def call(String INSTANCE_TYPE) {
  withEnv(["INSTANCE_TYPE=${INSTANCE_TYPE}"]) {
    withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
        sh '''
            set -o xtrace
            declare IMAGE_ID SUBNET SG1 SG2 SG3 SPOT_PRICE

            IMAGE_ID=$(
                aws ec2 describe-images \
                    --owners self \
                    --filters "Name=tag:iit-billing-tag,Values=pmm-worker-3" "Name=architecture,Values=x86_64" \
                    --region us-east-2 \
                    --output text \
                    --query 'Images[0].ImageId'
            )
            SUBNET=$(
                aws ec2 describe-subnets \
                    --region us-east-2 \
                    --output text \
                    --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                    --query 'Subnets[].SubnetId' \
                    | tr '\t' '\n' \
                    | sort --random-sort \
                    | head -1
            )
            SPOT_PRICE=$(
                aws ec2 describe-spot-price-history \
                    --instance-types $INSTANCE_TYPE \
                    --region us-east-2 \
                    --output text \
                    --product-description "Linux/UNIX (Amazon VPC)" \
                    --query 'SpotPriceHistory[0].SpotPrice'
            )

            PRICE_MULTIPLIER=1
            while true; do
                # increase price by 15% each time
                SPOT_PRICE=$(bc <<< "scale=8; $SPOT_PRICE * (1 + (.15 * $PRICE_MULTIPLIER))" | sed 's/^\\./0./')
                echo $SPOT_PRICE > SPOT_PRICE

                cat > config.json <<EOF
                  {
                    "DryRun": false,
                    "InstanceCount": 1,
                    "InstanceInterruptionBehavior": "terminate",
                    "LaunchSpecification": {
                        "EbsOptimized": false,
                        "ImageId": "$IMAGE_ID",
                        "InstanceType": "$INSTANCE_TYPE",
                        "KeyName": "jenkins",
                        "Monitoring": {
                            "Enabled": false
                        },
                        "IamInstanceProfile": {
                            "Name": "pmm-staging-slave"
                        },
                        "SecurityGroupIds": [
                            "sg-cd39dba6",
                            "sg-9f3cdef4",
                            "sg-0cbb55499c1e70fb7"
                        ],
                        "SubnetId": "$SUBNET"
                    },
                    "SpotPrice": "$SPOT_PRICE",
                    "Type": "persistent"
                  }
EOF

                REQUEST_ID=$(
                    aws ec2 request-spot-instances \
                        --output text \
                        --region us-east-2 \
                        --cli-input-json file://config.json \
                        --query 'SpotInstanceRequests[].SpotInstanceRequestId' \
                        | tee REQUEST_ID
                )

                ATTEMPTS=2
                until [ -s IP ] || [ $ATTEMPTS -eq 0 ]; do
                    sleep 5
                    aws ec2 describe-instances \
                        --filters "Name=spot-instance-request-id,Values=$REQUEST_ID" \
                        --query 'Reservations[].Instances[].PublicIpAddress' \
                        --output text \
                        --region us-east-2 \
                        | tee IP
                    ATTEMPTS=$((ATTEMPTS-1))
                done

                if [ -s IP ]; then
                    break
                fi

                aws ec2 cancel-spot-instance-requests --region us-east-2 --spot-instance-request-ids $REQUEST_ID
                PRICE_MULTIPLIER=$((PRICE_MULTIPLIER+1))
            done

            AMI_ID=$(
                aws ec2 describe-instances \
                    --filters "Name=spot-instance-request-id,Values=$REQUEST_ID" \
                    --query 'Reservations[].Instances[].InstanceId' \
                    --output text \
                    --region us-east-2 \
                    | tee AMI_ID
            )

            VOLUMES=$(
                aws ec2 describe-instances \
                    --region us-east-2 \
                    --output text \
                    --instance-ids $AMI_ID \
                    --query 'Reservations[].Instances[].BlockDeviceMappings[].Ebs.VolumeId'
            )

            aws ec2 create-tags  \
                --region us-east-2 \
                --resources $REQUEST_ID $AMI_ID $VOLUMES \
                --tags Key=Name,Value=${VM_NAME} \
                       Key=iit-billing-tag,Value=pmm-staging \
                       Key=stop-after-days,Value=${DAYS} \
                       Key=owner,Value=$OWNER

            # wait for the instance to be ready
            aws ec2 wait instance-status-ok --instance-ids $AMI_ID
        '''
        env.SPOT_PRICE = sh(returnStdout: true, script: "cat SPOT_PRICE").trim()
        env.REQUEST_ID = sh(returnStdout: true, script: "cat REQUEST_ID").trim()
        env.IP = sh(returnStdout: true, script: "cat IP").trim()
        env.AMI_ID = sh(returnStdout: true, script: "cat AMI_ID").trim()
    }
  }
}
