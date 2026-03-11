"""OpenShift compute resources (EC2, Load Balancers)."""

import boto3
from ..models.config import DRY_RUN
from ..utils import get_logger

logger = get_logger()


def delete_load_balancers(infra_id: str, region: str):
    """Delete Classic ELBs and ALB/NLBs for OpenShift cluster."""
    try:
        elb = boto3.client("elb", region_name=region)
        elbv2 = boto3.client("elbv2", region_name=region)
        ec2 = boto3.client("ec2", region_name=region)

        # Get VPC ID for cluster
        vpcs = ec2.describe_vpcs(
            Filters=[
                {"Name": "tag:kubernetes.io/cluster/" + infra_id, "Values": ["owned"]}
            ]
        )["Vpcs"]
        vpc_id = vpcs[0]["VpcId"] if vpcs else None

        # Delete Classic ELBs
        classic_elbs = elb.describe_load_balancers().get("LoadBalancerDescriptions", [])
        for lb in classic_elbs:
            if infra_id in lb["LoadBalancerName"] or (
                vpc_id and lb.get("VPCId") == vpc_id
            ):
                if DRY_RUN:
                    logger.info(
                        "Would DELETE load_balancer",
                        extra={
                            "dry_run": True,
                            "load_balancer_name": lb["LoadBalancerName"],
                            "load_balancer_type": "classic",
                            "vpc_id": vpc_id,
                            "infra_id": infra_id,
                        },
                    )
                else:
                    elb.delete_load_balancer(LoadBalancerName=lb["LoadBalancerName"])
                    logger.info(
                        "DELETE load_balancer",
                        extra={
                            "load_balancer_name": lb["LoadBalancerName"],
                            "load_balancer_type": "classic",
                            "vpc_id": vpc_id,
                            "infra_id": infra_id,
                        },
                    )

        # Delete ALB/NLBs
        alb_nlbs = elbv2.describe_load_balancers().get("LoadBalancers", [])
        for lb in alb_nlbs:
            if infra_id in lb["LoadBalancerName"] or (
                vpc_id and lb.get("VpcId") == vpc_id
            ):
                lb_type = lb.get("Type", "unknown")
                if DRY_RUN:
                    logger.info(
                        "Would DELETE load_balancer",
                        extra={
                            "dry_run": True,
                            "load_balancer_name": lb["LoadBalancerName"],
                            "load_balancer_arn": lb["LoadBalancerArn"],
                            "load_balancer_type": lb_type,
                            "vpc_id": vpc_id,
                            "infra_id": infra_id,
                        },
                    )
                else:
                    elbv2.delete_load_balancer(LoadBalancerArn=lb["LoadBalancerArn"])
                    logger.info(
                        "DELETE load_balancer",
                        extra={
                            "load_balancer_name": lb["LoadBalancerName"],
                            "load_balancer_arn": lb["LoadBalancerArn"],
                            "load_balancer_type": lb_type,
                            "vpc_id": vpc_id,
                            "infra_id": infra_id,
                        },
                    )

    except Exception as e:
        logger.error(f"Error deleting load balancers: {e}")
