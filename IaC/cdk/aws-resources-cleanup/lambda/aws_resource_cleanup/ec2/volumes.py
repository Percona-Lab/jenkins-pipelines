"""EBS volume cleanup detection and execution."""

from __future__ import annotations
import boto3
from botocore.exceptions import ClientError
from typing import Any
from ..models import CleanupAction
from ..models.config import DRY_RUN, PERSISTENT_TAGS
from ..utils import convert_tags_to_dict, has_valid_billing_tag, get_logger

logger = get_logger()


def is_volume_protected(tags_dict: dict[str, str]) -> bool:
    """
    Check if volume is protected from auto-deletion.

    Protection mechanisms (from legacy LambdaVolumeCleanup.yml):
    1. Name tag contains "do not remove"
    2. Has "PerconaKeep" tag
    3. Has persistent billing tag (jenkins-*, pmm-dev)
    4. Has valid billing tag (category or non-expired timestamp)
    """
    name = tags_dict.get("Name", "")

    # Legacy protection: "do not remove" in Name tag
    if "do not remove" in name.lower():
        logger.info(f"Volume protected: Name contains 'do not remove' ({name})")
        return True

    # Legacy protection: PerconaKeep tag
    if "PerconaKeep" in tags_dict:
        logger.info(f"Volume protected: Has PerconaKeep tag ({name})")
        return True

    # Protection by persistent billing tag
    billing_tag = tags_dict.get("iit-billing-tag", "")
    if billing_tag in PERSISTENT_TAGS:
        logger.info(f"Volume protected: Persistent billing tag '{billing_tag}' ({name})")
        return True

    # Protected if has valid billing tag (category or non-expired timestamp)
    if has_valid_billing_tag(tags_dict):
        logger.info(f"Volume protected: Has valid billing tag '{billing_tag}' ({name})")
        return True

    return False


def check_unattached_volume(
    volume: dict[str, Any], tags_dict: dict[str, str], current_time: int
) -> CleanupAction | None:
    """
    Check if volume is unattached and eligible for deletion.

    Port of legacy LambdaVolumeCleanup.yml logic:
    - Must be in "available" state (unattached)
    - Must have Name tag (filter: tag:Name exists)
    - Must not be protected
    """
    # Must be available (unattached)
    if volume["State"] != "available":
        return None

    # Must have Name tag (legacy filter requirement)
    if "Name" not in tags_dict:
        return None

    # Check protection
    if is_volume_protected(tags_dict):
        return None

    # Calculate age
    create_time = volume.get("CreateTime")
    if not create_time:
        logger.warning(f"Volume {volume['VolumeId']} has no CreateTime, skipping")
        return None

    create_timestamp = int(create_time.timestamp())
    age_seconds = current_time - create_timestamp
    age_days = age_seconds / 86400

    name = tags_dict.get("Name", "N/A")
    billing_tag = tags_dict.get("iit-billing-tag", "<MISSING>")
    volume_id = volume["VolumeId"]
    size_gb = volume.get("Size", 0)
    volume_type = volume.get("VolumeType", "unknown")

    reason = (
        f"Unattached volume ({size_gb}GB {volume_type}, "
        f"created {create_time.strftime('%Y-%m-%d %H:%M:%S UTC')}, "
        f"{age_days:.1f} days old)"
    )

    return CleanupAction(
        instance_id="",  # Empty for volumes
        region="",  # Set by caller
        name=name,
        action="DELETE_VOLUME",
        reason=reason,
        days_overdue=age_days,
        billing_tag=billing_tag,
        resource_type="volume",
        volume_id=volume_id,
    )


def delete_volume(action: CleanupAction, region: str) -> bool:
    """
    Delete an EBS volume.

    Args:
        action: CleanupAction with volume_id
        region: AWS region

    Returns:
        True if successful (or DRY_RUN), False otherwise
    """
    if not action.volume_id:
        logger.error(f"Cannot delete volume: missing volume_id in action {action}")
        return False

    try:
        ec2 = boto3.client("ec2", region_name=region)

        if DRY_RUN:
            logger.info(
                f"[DRY-RUN] Would delete volume {action.volume_id} "
                f"({action.name}, {action.reason})"
            )
            return True

        # Final safety check: verify volume is still available and not protected
        volumes = ec2.describe_volumes(VolumeIds=[action.volume_id])["Volumes"]
        if not volumes:
            logger.warning(f"Volume {action.volume_id} not found, skipping deletion")
            return False

        volume = volumes[0]
        if volume["State"] != "available":
            logger.warning(
                f"Volume {action.volume_id} is no longer available "
                f"(state: {volume['State']}), skipping deletion"
            )
            return False

        # Re-check protection (safety)
        tags_dict = convert_tags_to_dict(volume.get("Tags", []))
        if is_volume_protected(tags_dict):
            logger.warning(
                f"Volume {action.volume_id} is now protected, skipping deletion"
            )
            return False

        # Delete volume
        ec2.delete_volume(VolumeId=action.volume_id)
        logger.info(
            f"Successfully deleted volume {action.volume_id} "
            f"({action.name}, {action.reason})"
        )
        return True

    except ClientError as e:
        error_code = e.response.get("Error", {}).get("Code", "Unknown")
        error_msg = e.response.get("Error", {}).get("Message", str(e))

        if error_code == "InvalidVolume.NotFound":
            logger.warning(
                f"Volume {action.volume_id} not found (already deleted?): {error_msg}"
            )
            return False
        elif error_code == "VolumeInUse":
            logger.warning(
                f"Volume {action.volume_id} is in use, cannot delete: {error_msg}"
            )
            return False
        else:
            logger.error(
                f"Failed to delete volume {action.volume_id}: {error_code} - {error_msg}"
            )
            return False

    except Exception as e:
        logger.error(f"Unexpected error deleting volume {action.volume_id}: {e}")
        return False
