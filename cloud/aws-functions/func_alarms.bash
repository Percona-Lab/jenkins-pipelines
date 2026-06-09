REGION="eu-west-3"
ACCOUNT="119175775298"

# 1. create SNS topic
TOPIC_ARN=$(aws sns create-topic \
  --name lambda-failures \
  --region $REGION \
  --query 'TopicArn' --output text)

echo "Topic: $TOPIC_ARN"

# 2. subscribe emails
EMAILS=(
  "natalia.marukovich@percona.com"
  "eleonora.zinchenko@percona.com"
  "julio.pasinatto@percona.com"
  "valmira.nogueira@percona.com"
)

for EMAIL in "${EMAILS[@]}"; do
  aws sns subscribe \
    --topic-arn $TOPIC_ARN \
    --protocol email \
    --notification-endpoint $EMAIL \
    --region $REGION
  echo "Subscribed $EMAIL"
done

echo "Each recipient must confirm subscription via email link"

# 3. create alarms for all functions except deleteLB (old one, we don't need it.)
FUNCTIONS=(
  "deleteOrphanedResource"
  "deleteOrphanedUsers"
  "deleteOrphanedEIp"
  "deleteOrphanedOIDC"
  "deleteOrphandedVpc"
  "deleteOrphanedResourcesOpenshift"
  "deleteOrphanedCloudformation"
)

for FUNC in "${FUNCTIONS[@]}"; do
  aws cloudwatch put-metric-alarm \
    --alarm-name "Lambda-${FUNC}-Failures" \
    --alarm-description "Lambda ${FUNC} failed" \
    --metric-name Errors \
    --namespace AWS/Lambda \
    --dimensions Name=FunctionName,Value=$FUNC \
    --statistic Sum \
    --period 3600 \
    --threshold 1 \
    --comparison-operator GreaterThanOrEqualToThreshold \
    --evaluation-periods 1 \
    --alarm-actions $TOPIC_ARN \
    --treat-missing-data notBreaching \
    --region $REGION

  echo "Alarm created for $FUNC"
done