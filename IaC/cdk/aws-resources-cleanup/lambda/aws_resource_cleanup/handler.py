"""Main Lambda handler for AWS resources cleanup."""

from __future__ import annotations
import json
import time
import datetime
import boto3
from typing import Any

from .models import CleanupAction
from .models.config import DRY_RUN, SNS_TOPIC_ARN
from .utils import convert_tags_to_dict, get_logger
from .ec2 import (
    cirrus_ci_add_iit_billing_tag,
    is_protected,
    execute_cleanup_action,
    check_ttl_expiration,
    check_stop_after_days,
    check_long_stopped,
    check_untagged,
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

        # Execute actions
        for action in actions:
            execute_cleanup_action(action, region)

        # Send notification
        if actions:
            send_notification(actions, region)

        logger.info(f"Completed {region}: {len(actions)} actions")

    except Exception as e:
        logger.error(f"Error processing region {region}: {e}")

    return actions


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
