def call(String INSTANCE_TYPE, boolean USE_ONDEMAND = false, String ARCH = 'x86_64') {
  // t4g.xlarge alone often has no spot capacity. These three are interchangeable (arm64,
  // 4 vCPU, 16 GiB), so trying all of them usually finds one. Other types are left as-is.
  String CANDIDATE_TYPES = (ARCH == 'arm64' && INSTANCE_TYPE == 't4g.xlarge') ? 't4g.xlarge m6g.xlarge m7g.xlarge' : INSTANCE_TYPE

  withEnv(["INSTANCE_TYPE=${INSTANCE_TYPE}", "USE_ONDEMAND=${USE_ONDEMAND}", "ARCH=${ARCH}", "CANDIDATE_TYPES=${CANDIDATE_TYPES}"]) {
    withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
        sh '''
            set -o xtrace
            declare IMAGE_ID SUBNETS MARKET_OPTS TAGS

            IMAGE_ID=$(
                aws ec2 describe-images \
                    --owners self \
                    --filters "Name=tag:iit-billing-tag,Values=pmm-worker-3" "Name=architecture,Values=${ARCH}" \
                    --region us-east-2 \
                    --output text \
                    --query 'Images[0].ImageId'
            )
            if [ -z "$IMAGE_ID" ] || [ "$IMAGE_ID" = "None" ]; then
                echo "No pmm-worker-3 AMI tagged for $ARCH in us-east-2"
                exit 1
            fi

            # capacity is per-AZ, so walk every pmm-staging subnet instead of drawing one
            SUBNETS=$(
                aws ec2 describe-subnets \
                    --region us-east-2 \
                    --output text \
                    --filters "Name=tag:aws:cloudformation:stack-name,Values=pmm-staging" \
                    --query 'Subnets[].SubnetId' \
                    | tr '\t' '\n' \
                    | sort --random-sort
            )

            MARKET_OPTS=""
            if [ "$USE_ONDEMAND" != "true" ]; then
                MARKET_OPTS="--instance-market-options MarketType=spot"
            fi

            # tag at launch, so the VM is never running while invisible to aws-staging-stop
            TAGS="[{Key=Name,Value=$VM_NAME},{Key=iit-billing-tag,Value=pmm-staging},{Key=stop-after-days,Value=$DAYS},{Key=owner,Value=$OWNER}]"

            : > AMI_ID
            for SUBNET in $SUBNETS; do
                for TYPE in $CANDIDATE_TYPES; do
                    # run-instances answers synchronously: either an id, or an error and no VM
                    if aws ec2 run-instances \
                        --region us-east-2 \
                        --image-id "$IMAGE_ID" \
                        --instance-type "$TYPE" \
                        --key-name jenkins \
                        --iam-instance-profile Name=pmm-staging-slave \
                        --security-group-ids sg-cd39dba6 sg-9f3cdef4 sg-0cbb55499c1e70fb7 \
                        --subnet-id "$SUBNET" \
                        --count 1 \
                        $MARKET_OPTS \
                        --tag-specifications "ResourceType=instance,Tags=$TAGS" "ResourceType=volume,Tags=$TAGS" \
                        --output text \
                        --query 'Instances[].InstanceId' > AMI_ID
                    then
                        break 2
                    fi
                done
            done

            AMI_ID=$(cat AMI_ID)
            if [ -z "$AMI_ID" ]; then
                echo "Could not launch $INSTANCE_TYPE [tried $CANDIDATE_TYPES] in any pmm-staging subnet"
                exit 1
            fi

            # run-instances leaves no pending request, so there is never anything to cancel
            : > REQUEST_ID

            if [ "$USE_ONDEMAND" = "true" ]; then
                echo "on-demand ($TYPE)" > SPOT_PRICE
            else
                echo "spot ($TYPE)" > SPOT_PRICE
            fi

            # wait for the instance to be ready
            aws ec2 wait instance-running --instance-ids $AMI_ID
            aws ec2 wait instance-status-ok --instance-ids $AMI_ID

            aws ec2 describe-instances \
                --region us-east-2 \
                --output text \
                --instance-ids $AMI_ID \
                --query 'Reservations[].Instances[].PublicIpAddress' \
                | tee IP

            # the ssh wait loop in the caller never times out, so fail here instead of hanging
            IP=$(cat IP)
            if [ -z "$IP" ] || [ "$IP" = "None" ]; then
                echo "Instance $AMI_ID in $SUBNET has no public IP"
                exit 1
            fi
        '''
        env.SPOT_PRICE = sh(returnStdout: true, script: "cat SPOT_PRICE").trim()
        env.REQUEST_ID = sh(returnStdout: true, script: "cat REQUEST_ID").trim()
        env.IP = sh(returnStdout: true, script: "cat IP").trim()
        env.AMI_ID = sh(returnStdout: true, script: "cat AMI_ID").trim()
    }
  }
}
