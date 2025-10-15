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
    CfnParameter,
    CfnOutput,
    Tags
)
from constructs import Construct


class ResourceCleanupStack(Stack):
    """
    CDK Stack for comprehensive AWS resource cleanup.

    Manages EC2 instances, EKS clusters, and OpenShift infrastructure with:
    - TTL-based policies
    - Billing tag validation
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
            description="If true, Lambda will only log actions without terminating instances"
        )

        notification_email_param = CfnParameter(
            self, "NotificationEmail",
            type="String",
            default="",
            description="Email address for cleanup notifications (optional, leave empty to skip SNS)"
        )

        untagged_threshold_param = CfnParameter(
            self, "UntaggedThresholdMinutes",
            type="Number",
            default=30,
            min_value=10,
            max_value=1440,
            description="Minutes to wait before terminating untagged instances (default: 30)"
        )

        eks_skip_pattern_param = CfnParameter(
            self, "EKSSkipPattern",
            type="String",
            default="pe-.*",
            description="Regex pattern for EKS cluster names to skip from deletion"
        )

        openshift_cleanup_param = CfnParameter(
            self, "OpenShiftCleanupEnabled",
            type="String",
            default="true",
            allowed_values=["true", "false"],
            description="Enable advanced OpenShift cluster resource cleanup"
        )

        openshift_domain_param = CfnParameter(
            self, "OpenShiftBaseDomain",
            type="String",
            default="cd.percona.com",
            description="Base domain for Route53 DNS cleanup"
        )

        openshift_retries_param = CfnParameter(
            self, "OpenShiftMaxRetries",
            type="Number",
            default=3,
            min_value=1,
            max_value=5,
            description="Maximum reconciliation attempts for OpenShift cleanup"
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

        # Lambda Function
        cleanup_lambda = lambda_.Function(
            self, "ResourceCleanupLambda",
            function_name="LambdaAWSResourceCleanup",
            description="Comprehensive AWS resource cleanup: EC2, EKS, OpenShift (VPC, ELB, Route53, S3)",
            runtime=lambda_.Runtime.PYTHON_3_12,
            handler="aws_resource_cleanup.handler.lambda_handler",
            code=lambda_.Code.from_asset("lambda"),
            role=lambda_role,
            timeout=Duration.seconds(600),
            memory_size=1024,
            environment={
                "DRY_RUN": dry_run_param.value_as_string,
                "SNS_TOPIC_ARN": sns_topic.topic_arn,
                "UNTAGGED_THRESHOLD_MINUTES": untagged_threshold_param.value_as_string,
                "EKS_SKIP_PATTERN": eks_skip_pattern_param.value_as_string,
                "OPENSHIFT_CLEANUP_ENABLED": openshift_cleanup_param.value_as_string,
                "OPENSHIFT_BASE_DOMAIN": openshift_domain_param.value_as_string,
                "OPENSHIFT_MAX_RETRIES": openshift_retries_param.value_as_string
            }
        )

        Tags.of(cleanup_lambda).add("iit-billing-tag", "removeUntaggedEc2")

        # EventBridge Rule (15 minute schedule - matching legacy LambdaEC2Cleanup behavior)
        schedule_rule = events.Rule(
            self, "CleanupScheduleRule",
            rule_name="AWSResourceCleanupSchedule",
            description="Executes every 15 minutes for comprehensive AWS resource cleanup",
            schedule=events.Schedule.rate(Duration.minutes(15)),
            enabled=True
        )

        schedule_rule.add_target(targets.LambdaFunction(cleanup_lambda))

        # Outputs
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
