def call(String INSTANCE_TYPE, boolean USE_ONDEMAND = false) {
  withEnv(["INSTANCE_TYPE=${INSTANCE_TYPE}", "USE_ONDEMAND=${USE_ONDEMAND}"]) {
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
            if [ "$USE_ONDEMAND" = "true" ]; then
                # On-demand launch for RC/Release testing — no spot bidding, no interruption risk.
                # No IP polling here: the shared block below fetches the IP after the status-ok wait.
                echo "on-demand" > SPOT_PRICE
                : > REQUEST_ID
                AMI_ID=$(
                    aws ec2 run-instances \
                        --region us-east-2 \
                        --image-id "$IMAGE_ID" \
                        --instance-type "$INSTANCE_TYPE" \
                        --key-name jenkins \
                        --iam-instance-profile Name=pmm-staging-slave \
                        --security-group-ids sg-cd39dba6 sg-9f3cdef4 sg-0cbb55499c1e70fb7 \
                        --subnet-id "$SUBNET" \
                        --count 1 \
                        --output text \
                        --query 'Instances[].InstanceId' \
                        | tee AMI_ID
                )
                aws ec2 wait instance-running --instance-ids $AMI_ID
            else
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
            fi

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

            # on-demand path doesn't poll for the IP above — fetch it once the instance is ready
            if [ ! -s IP ]; then
                aws ec2 describe-instances \
                    --instance-ids $AMI_ID \
                    --query 'Reservations[].Instances[].PublicIpAddress' \
                    --output text \
                    --region us-east-2 \
                    | tee IP
            fi
        '''
        env.SPOT_PRICE = sh(returnStdout: true, script: "cat SPOT_PRICE").trim()
        env.REQUEST_ID = sh(returnStdout: true, script: "cat REQUEST_ID").trim()
        env.IP = sh(returnStdout: true, script: "cat IP").trim()
        env.AMI_ID = sh(returnStdout: true, script: "cat AMI_ID").trim()
    }
  }
}
