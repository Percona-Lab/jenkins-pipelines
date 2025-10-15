"""Main Lambda handler for AWS resources cleanup."""

from __future__ import annotations
import json
import time
import datetime
import boto3
from typing import Any

from .models import CleanupAction
from .models.config import DRY_RUN, SNS_TOPIC_ARN, VOLUME_CLEANUP_ENABLED
from .utils import convert_tags_to_dict, get_logger
from .ec2 import (
    cirrus_ci_add_iit_billing_tag,
    is_protected,
    execute_cleanup_action,
    check_ttl_expiration,
    check_stop_after_days,
    check_long_stopped,
    check_untagged,
    check_unattached_volume,
    delete_volume,
)

logger = get_logger()


def send_notification(actions: list[CleanupAction], region: str) -> None:
    """Send SNS notification about cleanup actions."""
    if not SNS_TOPIC_ARN or not actions:
        return

    try:
        sns = boto3.client("sns")

        message_lines = [
            f"AWS Resources Cleanup Report - {region}",
            f"Mode: {'DRY-RUN' if DRY_RUN else 'LIVE'}",
            f"Timestamp: {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}",
            "",
            f"Total Actions: {len(actions)}",
            "",
        ]

        for action in actions:
            # Handle volumes differently from instances
            if action.resource_type == "volume":
                message_lines.append(f"Volume: {action.volume_id}")
            else:
                message_lines.append(f"Instance: {action.instance_id}")

            message_lines.append(f"  Name: {action.name}")
            message_lines.append(f"  Action: {action.action}")
            message_lines.append(f"  Days Overdue: {action.days_overdue:.2f}")
            message_lines.append(f"  Reason: {action.reason}")
            message_lines.append(f"  Billing Tag: {action.billing_tag}")
            if action.owner:
                message_lines.append(f"  Owner: {action.owner}")
            if action.cluster_name:
                message_lines.append(f"  Cluster: {action.cluster_name}")
            message_lines.append("")

        message = "\n".join(message_lines)
        subject = f"[{'DRY-RUN' if DRY_RUN else 'LIVE'}] AWS Resources Cleanup: {len(actions)} actions in {region}"

        sns.publish(
            TopicArn=SNS_TOPIC_ARN,
            Subject=subject[:100],  # SNS subject limit
            Message=message,
        )

        logger.info(f"Sent SNS notification for {len(actions)} actions in {region}")

    except Exception as e:
        logger.error(f"Failed to send SNS notification: {e}")


def cleanup_region(region: str) -> list[CleanupAction]:
    """Process cleanup for a single region."""
    logger.info(f"Processing region: {region}")

    ec2 = boto3.client("ec2", region_name=region)
    current_time = int(time.time())
    actions = []

    try:
        response = ec2.describe_instances(
            Filters=[{"Name": "instance-state-name", "Values": ["running", "stopped"]}]
        )

        for reservation in response["Reservations"]:
            for instance in reservation["Instances"]:
                tags_dict = convert_tags_to_dict(instance.get("Tags", []))

                # Auto-tag CirrusCI instances (existing functionality)
                cirrus_ci_add_iit_billing_tag(instance, tags_dict)

                # Skip protected resources
                if is_protected(tags_dict):
                    continue

                # Check all cleanup policies (priority order)
                action = (
                    check_ttl_expiration(instance, tags_dict, current_time)
                    or check_stop_after_days(instance, tags_dict, current_time)
                    or check_long_stopped(instance, tags_dict, current_time)
                    or check_untagged(instance, tags_dict, current_time)
                )

                if action:
                    action.region = region
                    actions.append(action)

        # Execute instance actions
        for action in actions:
            execute_cleanup_action(action, region)

        # Volume cleanup phase (after instance cleanup)
        volume_actions = []

        if not VOLUME_CLEANUP_ENABLED:
            logger.info(f"Volume cleanup disabled for region: {region}")
        else:
            logger.info(f"Starting volume cleanup for region: {region}")

            try:
                # Query all volumes with Name tag (matching legacy LambdaVolumeCleanup filter)
                volumes_response = ec2.describe_volumes(
                    Filters=[{"Name": "tag-key", "Values": ["Name"]}]
                )

                for volume in volumes_response["Volumes"]:
                    tags_dict = convert_tags_to_dict(volume.get("Tags", []))

                    # Check if volume should be deleted
                    volume_action = check_unattached_volume(volume, tags_dict, current_time)

                    if volume_action:
                        volume_action.region = region
                        volume_actions.append(volume_action)

                # Execute volume deletions
                for volume_action in volume_actions:
                    delete_volume(volume_action, region)

                logger.info(f"Completed volume cleanup for {region}: {len(volume_actions)} volumes")

            except Exception as vol_error:
                logger.error(f"Error during volume cleanup in {region}: {vol_error}")

        # Combine all actions for notification
        all_actions = actions + volume_actions

        # Send notification
        if all_actions:
            send_notification(all_actions, region)

        logger.info(f"Completed {region}: {len(all_actions)} total actions ({len(actions)} instances, {len(volume_actions)} volumes)")

    except Exception as e:
        logger.error(f"Error processing region {region}: {e}")

    return actions + volume_actions if 'volume_actions' in locals() else actions


def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Main Lambda handler."""
    logger.info(f"Starting AWS resources cleanup (DRY_RUN={DRY_RUN})")

    try:
        ec2 = boto3.client("ec2")
        regions = [region["RegionName"] for region in ec2.describe_regions()["Regions"]]

        all_actions = []

        for region in regions:
            region_actions = cleanup_region(region)
            all_actions.extend(region_actions)

        # Summary
        action_counts: dict[str, int] = {}
        for action in all_actions:
            action_counts[action.action] = action_counts.get(action.action, 0) + 1

        logger.info(f"Cleanup complete: {len(all_actions)} total actions")
        for action_type, count in action_counts.items():
            logger.info(f"  {action_type}: {count}")

        return {
            "statusCode": 200,
            "body": json.dumps(
                {
                    "dry_run": DRY_RUN,
                    "total_actions": len(all_actions),
                    "by_action": action_counts,
                    "actions": [action.to_dict() for action in all_actions],
                }
            ),
        }

    except Exception as e:
        logger.error(f"Lambda execution failed: {e}")
        raise
