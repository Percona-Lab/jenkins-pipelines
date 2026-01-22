"""OpenShift cluster destruction orchestration.

Single-pass cleanup with dependency order enforcement.
EventBridge schedule (every 15 minutes) handles retries naturally.
"""

import boto3
from botocore.exceptions import ClientError
from ..utils import get_logger
from .compute import delete_load_balancers
from .network import (
    delete_nat_gateways,
    release_elastic_ips,
    cleanup_network_interfaces,
    delete_vpc_endpoints,
    delete_security_groups,
    delete_subnets,
    delete_route_tables,
    delete_internet_gateway,
    delete_vpc,
)
from .dns import cleanup_route53_records
from .storage import cleanup_s3_state

logger = get_logger()


def destroy_openshift_cluster(cluster_name: str, infra_id: str, region: str) -> bool:
    """
    Single-pass OpenShift cluster cleanup.

    Deletes resources in dependency order. If resources still have dependencies,
    exits gracefully and relies on next EventBridge schedule (15min) to retry.

    Returns:
        True if VPC successfully deleted (cleanup complete)
        False if resources remain (will retry on next schedule)
    """
    logger.info(
        "Starting OpenShift cluster cleanup",
        extra={
            "cluster_name": cluster_name,
            "infra_id": infra_id,
            "cluster_type": "openshift",
            "region": region,
        },
    )

    try:
        ec2 = boto3.client("ec2", region_name=region)

        # Check if VPC still exists
        vpcs = ec2.describe_vpcs(
            Filters=[
                {
                    "Name": "tag:kubernetes.io/cluster/" + infra_id,
                    "Values": ["owned"],
                }
            ]
        )["Vpcs"]

        if not vpcs:
            logger.info(
                "VPC not found - cleanup complete",
                extra={"cluster_name": cluster_name, "infra_id": infra_id},
            )
            # Clean up Route53 and S3 when VPC is gone
            cleanup_route53_records(cluster_name, region)
            cleanup_s3_state(cluster_name, region)
            return True

        vpc_id = vpcs[0]["VpcId"]
        logger.info(
            "Found VPC, proceeding with cleanup",
            extra={"cluster_name": cluster_name, "vpc_id": vpc_id},
        )

        # Delete resources in dependency order
        # Each function handles its own DependencyViolation errors gracefully
        delete_load_balancers(infra_id, region)
        delete_nat_gateways(infra_id, region)
        release_elastic_ips(infra_id, region)
        cleanup_network_interfaces(vpc_id, region)
        delete_vpc_endpoints(vpc_id, region)
        delete_security_groups(vpc_id, region)
        delete_subnets(vpc_id, region)
        delete_route_tables(vpc_id, region)
        delete_internet_gateway(vpc_id, region)

        # Try to delete VPC - if it fails due to dependencies, we'll retry on next run
        vpc_deleted = delete_vpc(vpc_id, region)

        if vpc_deleted:
            logger.info(
                "Successfully deleted VPC",
                extra={"cluster_name": cluster_name, "vpc_id": vpc_id},
            )
            # Clean up Route53 and S3 when VPC is successfully deleted
            cleanup_route53_records(cluster_name, region)
            cleanup_s3_state(cluster_name, region)
            return True
        else:
            logger.info(
                "VPC still has dependencies, will retry on next schedule",
                extra={
                    "cluster_name": cluster_name,
                    "vpc_id": vpc_id,
                    "retry_interval_minutes": 15,
                },
            )
            return False

    except ClientError as e:
        error_code = e.response.get("Error", {}).get("Code", "")
        if error_code == "DependencyViolation":
            logger.info(
                "Dependencies remain, will retry on next schedule",
                extra={"cluster_name": cluster_name, "error_code": error_code},
            )
            return False
        else:
            logger.error(
                "Error during OpenShift cleanup",
                extra={
                    "cluster_name": cluster_name,
                    "error": str(e),
                    "error_code": error_code,
                },
            )
            raise
    except Exception as e:
        logger.error(
            "Unexpected error during OpenShift cleanup",
            extra={"cluster_name": cluster_name, "error": str(e)},
        )
        raise
