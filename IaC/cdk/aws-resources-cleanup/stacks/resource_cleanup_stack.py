"""CDK Stack for AWS Resource Cleanup Lambda."""

from aws_cdk import (
    Stack,
    Duration,
    aws_lambda as lambda_,
    aws_iam as iam,
    aws_sns as sns,
    aws_sns_subscriptions as subscriptions,
    aws_events as events,
    aws_events_targets as targets,
    aws_logs as logs,
    aws_cloudwatch as cloudwatch,
    aws_cloudwatch_actions as cw_actions,
    CfnParameter,
    CfnOutput,
    Tags
)
from constructs import Construct


class ResourceCleanupStack(Stack):
    """
    CDK Stack for comprehensive AWS resource cleanup.

    Manages EC2 instances, EBS volumes, EKS clusters, and OpenShift infrastructure with:
    - TTL-based policies
    - Billing tag validation
    - Unattached volume cleanup (available EBS volumes)
    - Cluster-aware cleanup (EKS CloudFormation, OpenShift VPC/ELB/Route53/S3)
    - Configurable dry-run mode
    """

    def __init__(self, scope: Construct, construct_id: str, **kwargs) -> None:
        super().__init__(scope, construct_id, **kwargs)

        # Parameters
        dry_run_param = CfnParameter(
            self, "DryRunMode",
            type="String",
            default="true",
            allowed_values=["true", "false"],
            description="[SAFETY] Safe mode - logs all actions without executing them. Set to 'false' only when ready for actual resource deletion. Always test with 'true' first."
        )

        notification_email_param = CfnParameter(
            self, "NotificationEmail",
            type="String",
            default="",
            description="[NOTIFICATIONS] Email address for cleanup action reports. Leave empty to disable SNS notifications. Subscribe to SNS topic manually after deployment."
        )

        untagged_threshold_param = CfnParameter(
            self, "UntaggedThresholdMinutes",
            type="Number",
            default=30,
            min_value=10,
            max_value=1440,
            description="[POLICY] Grace period in minutes before terminating instances without iit-billing-tag. Default 30 minutes. Range: 10-1440 minutes (24 hours max)."
        )

        stopped_threshold_param = CfnParameter(
            self, "StoppedThresholdDays",
            type="Number",
            default=30,
            min_value=7,
            max_value=180,
            description="[POLICY] Days a stopped instance can remain before termination. Default 30 days. Range: 7-180 days. Helps reduce costs from forgotten stopped instances."
        )

        eks_cleanup_param = CfnParameter(
            self, "EKSCleanupEnabled",
            type="String",
            default="true",
            allowed_values=["true", "false"],
            description="[EKS] Enable full EKS cluster deletion via CloudFormation stack removal (eksctl-* stacks). When disabled, only terminates EC2 nodes."
        )

        eks_skip_pattern_param = CfnParameter(
            self, "EKSSkipPattern",
            type="String",
            default="pe-.*",
            description="[EKS] Regex pattern for cluster names to protect from deletion. Default 'pe-.*' protects production environment clusters. Use '(?!)' to disable protection."
        )

        openshift_cleanup_param = CfnParameter(
            self, "OpenShiftCleanupEnabled",
            type="String",
            default="true",
            allowed_values=["true", "false"],
            description="[OPENSHIFT] Enable comprehensive OpenShift cluster cleanup including VPC, load balancers, Route53 DNS, and S3 buckets. When disabled, only terminates EC2 nodes."
        )

        openshift_domain_param = CfnParameter(
            self, "OpenShiftBaseDomain",
            type="String",
            default="cd.percona.com",
            description="[OPENSHIFT] Base domain for Route53 DNS record cleanup. Only records under this domain will be removed. Must match your OpenShift installation domain."
        )

        volume_cleanup_param = CfnParameter(
            self, "VolumeCleanupEnabled",
            type="String",
            default="true",
            allowed_values=["true", "false"],
            description="[VOLUMES] Enable cleanup of unattached (available) EBS volumes. Only deletes volumes without protection tags (PerconaKeep, valid billing tags, 'do not remove' in name)."
        )

        # Scheduling
        schedule_rate_param = CfnParameter(
            self, "ScheduleRateMinutes",
            type="Number",
            default=15,
            description="[SCHEDULING] Execution frequency in minutes. Lambda scans all target regions at this interval. Recommended: 15 for normal use, 5 for aggressive cleanup, 60 for light monitoring."
        )

        # Advanced Cleanup
        regions_param = CfnParameter(
            self, "TargetRegions",
            type="String",
            default="all",
            description="[REGION FILTER] Target AWS regions to scan. Use 'all' for all regions, or comma-separated list (e.g., 'us-east-1,us-west-2'). Reduces execution time when limiting to specific regions."
        )

        # Logging
        log_retention_param = CfnParameter(
            self, "LogRetentionDays",
            type="Number",
            default=30,
            description="[LOGGING] CloudWatch log retention period in days. Valid options: 1, 3, 7, 14, 30, 60, 90, 120, 180. Affects storage costs - longer retention = higher costs."
        )

        log_level_param = CfnParameter(
            self, "LogLevel",
            type="String",
            default="INFO",
            allowed_values=["DEBUG", "INFO", "WARNING", "ERROR"],
            description="[LOGGING] Log verbosity. DEBUG = detailed (all protection decisions), INFO = standard (actions + summaries), WARNING = issues only, ERROR = failures only."
        )

        # SNS Topic for notifications
        # Note: Subscription must be added manually via AWS Console or CLI
        # CDK cannot conditionally create subscriptions based on parameter values
        sns_topic = sns.Topic(
            self, "CleanupNotificationTopic",
            topic_name="AWSResourceCleanupNotifications",
            display_name="AWS Resource Cleanup Notifications"
        )

        Tags.of(sns_topic).add("iit-billing-tag", "removeUntaggedEc2")

        # IAM Role for Lambda
        lambda_role = iam.Role(
            self, "ResourceCleanupRole",
            role_name="RoleAWSResourceCleanup",
            assumed_by=iam.ServicePrincipal("lambda.amazonaws.com"),
            managed_policies=[
                iam.ManagedPolicy.from_aws_managed_policy_name(
                    "service-role/AWSLambdaBasicExecutionRole"
                )
            ]
        )

        Tags.of(lambda_role).add("iit-billing-tag", "removeUntaggedEc2")

        # IAM Policy for Lambda
        lambda_role.add_to_policy(iam.PolicyStatement(
            effect=iam.Effect.ALLOW,
            actions=[
                "ec2:DescribeRegions",
                "ec2:DescribeInstances",
                "ec2:TerminateInstances",
                "ec2:StopInstances",
                "ec2:CreateTags",
                "ec2:DescribeVolumes",
                "ec2:DeleteVolume",
                "eks:DescribeCluster",
                "eks:ListClusters",
                "cloudformation:DescribeStacks",
                "cloudformation:DescribeStackEvents",
                "cloudformation:DeleteStack",
                "ec2:DescribeSecurityGroups",
                "ec2:RevokeSecurityGroupIngress",
                "ec2:DeleteSecurityGroup",
                "ec2:DisassociateRouteTable",
                "ec2:DeleteRoute",
                "ec2:DeleteRouteTable",
                "ec2:DescribeVpcs",
                "ec2:DeleteVpc",
                "ec2:DescribeSubnets",
                "ec2:DeleteSubnet",
                "ec2:DescribeInternetGateways",
                "ec2:DetachInternetGateway",
                "ec2:DeleteInternetGateway",
                "ec2:DescribeNatGateways",
                "ec2:DeleteNatGateway",
                "ec2:DescribeAddresses",
                "ec2:ReleaseAddress",
                "ec2:DescribeNetworkInterfaces",
                "ec2:DeleteNetworkInterface",
                "ec2:DescribeVpcEndpoints",
                "ec2:DeleteVpcEndpoints",
                "ec2:DescribeRouteTables",
                "elasticloadbalancing:DescribeLoadBalancers",
                "elasticloadbalancing:DeleteLoadBalancer",
                "elasticloadbalancing:DescribeTargetGroups",
                "elasticloadbalancing:DeleteTargetGroup",
                "route53:ListHostedZones",
                "route53:ListResourceRecordSets",
                "route53:ChangeResourceRecordSets",
                "route53:GetChange",
                "s3:ListBucket",
                "s3:DeleteObject",
                "s3:DeleteObjectVersion",
                "s3:GetBucketLocation",
                "sts:GetCallerIdentity"
            ],
            resources=["*"]
        ))

        # SNS publish permission (always add, will be empty topic ARN if no email)
        lambda_role.add_to_policy(iam.PolicyStatement(
            effect=iam.Effect.ALLOW,
            actions=["sns:Publish"],
            resources=[sns_topic.topic_arn]
        ))

        # Map log retention parameter to CDK enum
        log_retention_mapping = {
            1: logs.RetentionDays.ONE_DAY,
            3: logs.RetentionDays.THREE_DAYS,
            7: logs.RetentionDays.ONE_WEEK,
            14: logs.RetentionDays.TWO_WEEKS,
            30: logs.RetentionDays.ONE_MONTH,
            60: logs.RetentionDays.TWO_MONTHS,
            90: logs.RetentionDays.THREE_MONTHS,
            120: logs.RetentionDays.FOUR_MONTHS,
            180: logs.RetentionDays.SIX_MONTHS,
        }

        # Lambda Function
        cleanup_lambda = lambda_.Function(
            self, "ResourceCleanupLambda",
            function_name="LambdaAWSResourceCleanup",
            description="Comprehensive AWS resource cleanup: EC2, EBS volumes, EKS, OpenShift (VPC, ELB, Route53, S3)",
            runtime=lambda_.Runtime.PYTHON_3_13,
            architecture=lambda_.Architecture.ARM_64,
            handler="aws_resource_cleanup.handler.lambda_handler",
            code=lambda_.Code.from_asset("lambda"),
            role=lambda_role,
            timeout=Duration.seconds(600),
            memory_size=1024,
            reserved_concurrent_executions=1,
            log_retention=log_retention_mapping.get(
                log_retention_param.value_as_number,
                logs.RetentionDays.ONE_MONTH
            ),
            environment={
                "DRY_RUN": dry_run_param.value_as_string,
                "SNS_TOPIC_ARN": sns_topic.topic_arn,
                "UNTAGGED_THRESHOLD_MINUTES": untagged_threshold_param.value_as_string,
                "STOPPED_THRESHOLD_DAYS": stopped_threshold_param.value_as_string,
                "EKS_CLEANUP_ENABLED": eks_cleanup_param.value_as_string,
                "EKS_SKIP_PATTERN": eks_skip_pattern_param.value_as_string,
                "OPENSHIFT_CLEANUP_ENABLED": openshift_cleanup_param.value_as_string,
                "OPENSHIFT_BASE_DOMAIN": openshift_domain_param.value_as_string,
                "VOLUME_CLEANUP_ENABLED": volume_cleanup_param.value_as_string,
                "TARGET_REGIONS": regions_param.value_as_string,
                "LOG_LEVEL": log_level_param.value_as_string
            }
        )

        Tags.of(cleanup_lambda).add("iit-billing-tag", "removeUntaggedEc2")

        # EventBridge Rule (configurable schedule)
        schedule_rule = events.Rule(
            self, "CleanupScheduleRule",
            rule_name="AWSResourceCleanupSchedule",
            description=f"Executes every {schedule_rate_param.value_as_number} minutes for comprehensive AWS resource cleanup",
            schedule=events.Schedule.rate(Duration.minutes(schedule_rate_param.value_as_number)),
            enabled=True
        )

        # Add target with retry policy for failed invocations
        schedule_rule.add_target(targets.LambdaFunction(
            cleanup_lambda,
            retry_attempts=2,  # Retry failed invocations up to 2 times
            max_event_age=Duration.hours(1)  # Discard events older than 1 hour
        ))

        # CloudWatch Alarms for monitoring and blast radius protection
        lambda_errors_alarm = cloudwatch.Alarm(
            self, "LambdaErrorsAlarm",
            alarm_name="AWSResourceCleanup-LambdaErrors",
            alarm_description="Alert when cleanup Lambda encounters errors",
            metric=cleanup_lambda.metric_errors(
                period=Duration.minutes(15),
                statistic="Sum"
            ),
            threshold=1,
            evaluation_periods=1,
            treat_missing_data=cloudwatch.TreatMissingData.NOT_BREACHING
        )
        lambda_errors_alarm.add_alarm_action(cw_actions.SnsAction(sns_topic))

        # Alarm for Lambda timeout (indicates potential performance issues)
        lambda_timeout_alarm = cloudwatch.Alarm(
            self, "LambdaTimeoutAlarm",
            alarm_name="AWSResourceCleanup-LambdaTimeout",
            alarm_description="Alert when cleanup Lambda approaches timeout (>8 minutes)",
            metric=cleanup_lambda.metric_duration(
                period=Duration.minutes(15),
                statistic="Maximum"
            ),
            threshold=480000,  # 8 minutes in milliseconds (Lambda timeout is 10min)
            evaluation_periods=1,
            comparison_operator=cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
            treat_missing_data=cloudwatch.TreatMissingData.NOT_BREACHING
        )
        lambda_timeout_alarm.add_alarm_action(cw_actions.SnsAction(sns_topic))

        # Outputs
        CfnOutput(
            self, "LambdaFunctionName",
            description="Name of the Lambda function",
            value=cleanup_lambda.function_name,
            export_name="AWSResourceCleanupLambdaName"
        )

        CfnOutput(
            self, "LambdaFunctionArn",
            description="ARN of the Lambda function",
            value=cleanup_lambda.function_arn,
            export_name="AWSResourceCleanupLambdaArn"
        )

        CfnOutput(
            self, "SNSTopicArn",
            description="ARN of the SNS topic for notifications",
            value=sns_topic.topic_arn
        )

        CfnOutput(
            self, "DryRunModeOutput",
            description="Current dry-run mode setting",
            value=dry_run_param.value_as_string
        )
