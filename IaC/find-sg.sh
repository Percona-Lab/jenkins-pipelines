#!/bin/bash

function usage {
    echo "$0 <jenkins_name> -- to locate corresponding Jenkins"
    echo "FOR EXAMPLE: $0 pxb"
    echo "$0 -h to display this message"
}

if [[ $1 == "-h"  ]]; then
    usage
    exit 0
elif [[ -z $1 ]]; then
    usage
    exit 1
elif [[ -n $1 ]]; then
    JENKINS_NAME=$1
fi

REGIONS="us-east-2 us-west-1 us-west-2 eu-central-1 eu-west-1"
if [[ $JENKINS_NAME == "pmm" ]]; then
    JENKINS_NAME="pmm-amzn2"
fi

for REGION in $REGIONS; do
    for INSTANCE in $(aws ec2 describe-instances --query 'Reservations[].Instances[].InstanceId' --filter "Name=tag:Name,Values=jenkins-$JENKINS_NAME" "Name=instance-state-name,Values=running" --output text --region ${REGION}); do
        if [[ -n $INSTANCE ]]; then
            SecGroups=$(aws ec2 describe-instances --instance-ids $INSTANCE --region $REGION --query "Reservations[].Instances[].SecurityGroups[]" --output text | grep -E "*SSH*" | awk '{print $1}')
            if [[ -n $SecGroups ]]; then
                echo "$SecGroups $REGION"
            fi
        fi
    done
done