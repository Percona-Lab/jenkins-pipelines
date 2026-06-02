"""OpenShift cluster detection."""

from __future__ import annotations
import boto3
from ..utils import get_logger

logger = get_logger()


def detect_openshift_infra_id(cluster_name: str, region: str) -> str | None:
    """Detect OpenShift infrastructure ID from cluster name."""
    try:
        ec2 = boto3.client("ec2", region_name=region)

        # Try exact match first
        vpcs = ec2.describe_vpcs(
            Filters=[
                {"Name": "tag-key", "Values": [f"kubernetes.io/cluster/{cluster_name}"]}
            ]
        )["Vpcs"]

        # Try wildcard match if exact doesn't work
        if not vpcs:
            vpcs = ec2.describe_vpcs(
                Filters=[
                    {
                        "Name": "tag-key",
                        "Values": [f"kubernetes.io/cluster/{cluster_name}-*"],
                    }
                ]
            )["Vpcs"]

        if vpcs:
            for tag in vpcs[0].get("Tags", []):
                if tag["Key"].startswith("kubernetes.io/cluster/"):
                    infra_id: str = tag["Key"].split("/")[-1]
                    logger.info(
                        f"Detected OpenShift infra ID: {infra_id} from cluster: {cluster_name}"
                    )
                    return infra_id

    except Exception as e:
        logger.error(f"Error detecting OpenShift infra ID: {e}")

    return None
