"""EC2 instance operations for OpenShift cluster cleanup."""

from __future__ import annotations
import boto3
from botocore.exceptions import ClientError
from ..models import CleanupAction
from ..models.config import DRY_RUN, OPENSHIFT_CLEANUP_ENABLED
from ..utils import get_logger
from ..openshift.orchestrator import destroy_openshift_cluster
from ..openshift.detection import detect_openshift_infra_id

logger = get_logger()


def execute_cleanup_action(action: CleanupAction, region: str) -> bool:
    """Execute OpenShift cluster cleanup action."""
    ec2 = boto3.client("ec2", region_name=region)

    try:
        if action.action == "TERMINATE_OPENSHIFT_CLUSTER":
            if not action.cluster_name:
                logger.error(
                    "Missing cluster_name for TERMINATE_OPENSHIFT_CLUSTER action",
                    extra={"instance_id": action.instance_id, "action": action.action},
                )
                return False

            if OPENSHIFT_CLEANUP_ENABLED:
                cluster_name = action.cluster_name
                infra_id = detect_openshift_infra_id(cluster_name, region)
                if infra_id:
                    if DRY_RUN:
                        logger.info(
                            "Would TERMINATE_OPENSHIFT_CLUSTER",
                            extra={
                                "dry_run": True,
                                "cluster_name": cluster_name,
                                "infra_id": infra_id,
                                "cluster_type": "openshift",
                                "region": region,
                            },
                        )
                    else:
                        logger.info(
                            "TERMINATE_OPENSHIFT_CLUSTER",
                            extra={
                                "cluster_name": cluster_name,
                                "infra_id": infra_id,
                                "cluster_type": "openshift",
                                "region": region,
                            },
                        )
                        destroy_openshift_cluster(cluster_name, infra_id, region)
                if DRY_RUN:
                    logger.info(
                        "Would TERMINATE instance for cluster",
                        extra={
                            "dry_run": True,
                            "instance_id": action.instance_id,
                            "cluster_name": cluster_name,
                        },
                    )
                else:
                    logger.info(
                        "TERMINATE instance for cluster",
                        extra={
                            "instance_id": action.instance_id,
                            "cluster_name": cluster_name,
                        },
                    )
                    ec2.terminate_instances(InstanceIds=[action.instance_id])
            else:
                logger.info(
                    "OpenShift cleanup disabled",
                    extra={
                        "instance_id": action.instance_id,
                        "action": "SKIP",
                    },
                )
            return True

    except ClientError as e:
        logger.error(
            "Failed to execute cleanup action",
            extra={
                "action": action.action,
                "instance_id": action.instance_id,
                "error": str(e),
            },
        )
        return False

    return False
