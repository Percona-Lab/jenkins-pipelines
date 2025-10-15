"""EC2 instance operations."""

from __future__ import annotations
import boto3
from botocore.exceptions import ClientError
from typing import Any
from ..models import CleanupAction
from ..models.config import DRY_RUN, PERSISTENT_TAGS
from ..utils import has_valid_billing_tag, get_logger
from ..eks.cloudformation import delete_eks_cluster_stack
from ..openshift.orchestrator import destroy_openshift_cluster
from ..openshift.detection import detect_openshift_infra_id

logger = get_logger()


def cirrus_ci_add_iit_billing_tag(
    instance: dict[str, Any], tags_dict: dict[str, str]
) -> None:
    """Add iit-billing-tag to CirrusCI instances (existing functionality)."""
    has_cirrus_ci_tag = tags_dict.get("CIRRUS_CI", "").lower() == "true"
    has_iit_billing_tag = "iit-billing-tag" in tags_dict

    if has_cirrus_ci_tag and not has_iit_billing_tag:
        try:
            ec2_resource = boto3.resource(
                "ec2", region_name=instance["Placement"]["AvailabilityZone"][:-1]
            )
            ec2_instance = ec2_resource.Instance(instance["InstanceId"])
            ec2_instance.create_tags(
                Tags=[{"Key": "iit-billing-tag", "Value": "CirrusCI"}]
            )

            instance_name = tags_dict.get("Name")
            cirrus_repo = tags_dict.get("CIRRUS_REPO_FULL_NAME")
            cirrus_task = tags_dict.get("CIRRUS_TASK_ID")

            logger.info(
                f"Tagged {instance['InstanceId']} ({instance_name}) with 'iit-billing-tag: CirrusCI'. "
                f"Repo: {cirrus_repo}, Task: {cirrus_task}"
            )
        except ClientError as e:
            logger.error(
                f"Error tagging CirrusCI instance {instance['InstanceId']}: {e}"
            )


def is_protected(tags_dict: dict[str, str], instance_id: str = "") -> tuple[bool, str]:
    """
    Check if instance is protected from auto-deletion.

    Returns:
        Tuple of (is_protected, reason) where reason describes why it's protected
    """
    billing_tag = tags_dict.get("iit-billing-tag", "")
    name = tags_dict.get("Name", "<UNNAMED>")

    # Protected by persistent billing tag
    if billing_tag in PERSISTENT_TAGS:
        reason = f"Persistent billing tag '{billing_tag}'"
        if instance_id:
            logger.info(f"Instance {instance_id} protected: {reason} ({name})")
        return True, reason

    # Protected if has valid billing tag (category or non-expired timestamp)
    if has_valid_billing_tag(tags_dict):
        # But only if it doesn't have TTL tags (TTL takes precedence)
        has_ttl = (
            "delete-cluster-after-hours" in tags_dict or "stop-after-days" in tags_dict
        )
        if not has_ttl:
            reason = f"Valid billing tag '{billing_tag}'"
            if instance_id:
                logger.info(f"Instance {instance_id} protected: {reason} ({name})")
            return True, reason

    return False, ""


def execute_cleanup_action(action: CleanupAction, region: str) -> bool:
    """Execute a cleanup action (terminate, stop, etc.)."""
    ec2 = boto3.client("ec2", region_name=region)

    try:
        if action.action == "TERMINATE":
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would TERMINATE instance {action.instance_id} in {region}: {action.reason}"
                )
            else:
                logger.info(
                    f"TERMINATE instance {action.instance_id} in {region}: {action.reason}"
                )
                ec2.terminate_instances(InstanceIds=[action.instance_id])
            return True

        elif action.action == "TERMINATE_CLUSTER":
            from ..models.config import EKS_CLEANUP_ENABLED

            if not action.cluster_name:
                logger.error(
                    f"Missing cluster_name for TERMINATE_CLUSTER action on {action.instance_id}"
                )
                return False

            if EKS_CLEANUP_ENABLED:
                if DRY_RUN:
                    logger.info(
                        f"[DRY-RUN] Would TERMINATE_CLUSTER eks {action.cluster_name} in {region}"
                    )
                    logger.info(
                        f"[DRY-RUN] Would TERMINATE instance {action.instance_id} for cluster {action.cluster_name}"
                    )
                else:
                    logger.info(
                        f"TERMINATE_CLUSTER eks {action.cluster_name} in {region}"
                    )
                    delete_eks_cluster_stack(action.cluster_name, region)
                    logger.info(
                        f"TERMINATE instance {action.instance_id} for cluster {action.cluster_name}"
                    )
                    ec2.terminate_instances(InstanceIds=[action.instance_id])
            else:
                logger.info(
                    f"EKS cleanup disabled, would only TERMINATE instance {action.instance_id}"
                )
            return True

        elif action.action == "TERMINATE_OPENSHIFT_CLUSTER":
            from ..models.config import OPENSHIFT_CLEANUP_ENABLED

            if not action.cluster_name:
                logger.error(
                    f"Missing cluster_name for TERMINATE_OPENSHIFT_CLUSTER action on {action.instance_id}"
                )
                return False

            if OPENSHIFT_CLEANUP_ENABLED:
                cluster_name = action.cluster_name
                infra_id = detect_openshift_infra_id(cluster_name, region)
                if infra_id:
                    if DRY_RUN:
                        logger.info(
                            f"[DRY-RUN] Would TERMINATE_OPENSHIFT_CLUSTER {cluster_name} ({infra_id}) in {region}"
                        )
                    else:
                        logger.info(
                            f"TERMINATE_OPENSHIFT_CLUSTER {cluster_name} ({infra_id}) in {region}"
                        )
                        destroy_openshift_cluster(cluster_name, infra_id, region)
                if DRY_RUN:
                    logger.info(
                        f"[DRY-RUN] Would TERMINATE instance {action.instance_id} for cluster {cluster_name}"
                    )
                else:
                    logger.info(
                        f"TERMINATE instance {action.instance_id} for cluster {cluster_name}"
                    )
                    ec2.terminate_instances(InstanceIds=[action.instance_id])
            else:
                logger.info(
                    f"OpenShift cleanup disabled, would only TERMINATE instance {action.instance_id}"
                )
            return True

        elif action.action == "STOP":
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would STOP instance {action.instance_id} in {region}: {action.reason}"
                )
            else:
                logger.info(
                    f"STOP instance {action.instance_id} in {region}: {action.reason}"
                )
                ec2.stop_instances(InstanceIds=[action.instance_id])
            return True

    except ClientError as e:
        logger.error(f"Failed to execute {action.action} on {action.instance_id}: {e}")
        return False

    return False
