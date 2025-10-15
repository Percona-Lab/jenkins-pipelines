"""OpenShift cluster destruction orchestration with reconciliation loop."""

import time
import boto3
from ..models.config import DRY_RUN, OPENSHIFT_MAX_RETRIES
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


def destroy_openshift_cluster(cluster_name: str, infra_id: str, region: str):
    """Main orchestration function for OpenShift cluster cleanup with reconciliation."""
    logger.info(
        f"Starting OpenShift cluster cleanup: {cluster_name} "
        f"(infra: {infra_id}) in {region}"
    )

    for attempt in range(1, OPENSHIFT_MAX_RETRIES + 1):
        logger.info(f"Cleanup attempt {attempt}/{OPENSHIFT_MAX_RETRIES}")

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
                logger.info("VPC not found, cleanup may be complete")
                break

            vpc_id = vpcs[0]["VpcId"]

            # Delete resources in dependency order
            delete_load_balancers(infra_id, region)
            if not DRY_RUN:
                time.sleep(10)

            delete_nat_gateways(infra_id, region)
            if not DRY_RUN:
                time.sleep(10)

            release_elastic_ips(infra_id, region)
            cleanup_network_interfaces(vpc_id, region)
            delete_vpc_endpoints(vpc_id, region)
            delete_security_groups(vpc_id, region)
            delete_subnets(vpc_id, region)
            delete_route_tables(vpc_id, region)
            delete_internet_gateway(vpc_id, region)
            delete_vpc(vpc_id, region)

            # On final attempt, also clean up Route53 and S3
            if attempt == OPENSHIFT_MAX_RETRIES:
                cleanup_route53_records(cluster_name, region)
                cleanup_s3_state(cluster_name, region)

        except Exception as e:
            logger.error(f"Error in cleanup attempt {attempt}: {e}")

        # Wait between attempts (except after last attempt)
        if attempt < OPENSHIFT_MAX_RETRIES:
            if not DRY_RUN:
                time.sleep(15)

    logger.info(f"Completed OpenShift cluster cleanup: {cluster_name}")
