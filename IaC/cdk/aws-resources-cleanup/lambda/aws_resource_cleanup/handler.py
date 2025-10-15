"""Main Lambda handler for AWS resources cleanup."""

from __future__ import annotations
import json
import time
import datetime
import boto3
from typing import Any

from aws_lambda_powertools import Tracer, Metrics
from aws_lambda_powertools.metrics import MetricUnit
from aws_lambda_powertools.utilities.typing import LambdaContext

from .models import CleanupAction
from .models.config import (
    DRY_RUN,
    SNS_TOPIC_ARN,
    VOLUME_CLEANUP_ENABLED,
    TARGET_REGIONS,
)
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
tracer = Tracer(service="aws-resource-cleanup")
metrics = Metrics(namespace="Percona/ResourceCleanup", service="aws-resource-cleanup")


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

        logger.info(
            "Sent SNS notification",
            extra={"actions_count": len(actions), "region": region},
        )

    except Exception as e:
        logger.error(f"Failed to send SNS notification: {e}")


@tracer.capture_method
def cleanup_region(region: str) -> list[CleanupAction]:
    """Process cleanup for a single region."""
    start_time = time.time()
    logger.info("Processing region", extra={"region": region})

    ec2 = boto3.client("ec2", region_name=region)
    current_time = int(time.time())
    actions = []

    # Track instance scan statistics
    instance_scan_count = 0
    instance_protected_count = 0
    instance_protection_reasons: dict[str, int] = {}

    try:
        response = ec2.describe_instances(
            Filters=[{"Name": "instance-state-name", "Values": ["running", "stopped"]}]
        )

        for reservation in response["Reservations"]:
            for instance in reservation["Instances"]:
                instance_scan_count += 1
                tags_dict = convert_tags_to_dict(instance.get("Tags", []))

                # Auto-tag CirrusCI instances (existing functionality)
                cirrus_ci_add_iit_billing_tag(instance, tags_dict)

                # Skip protected resources
                is_protected_flag, protection_reason = is_protected(
                    tags_dict, instance["InstanceId"]
                )
                if is_protected_flag:
                    instance_protected_count += 1
                    instance_protection_reasons[protection_reason] = (
                        instance_protection_reasons.get(protection_reason, 0) + 1
                    )
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

        # Log instance scan summary and emit metrics
        logger.info(
            "Instance scan complete",
            extra={
                "region": region,
                "instances_scanned": instance_scan_count,
                "actions_count": len(actions),
                "instances_protected": instance_protected_count,
                "protection_reasons": instance_protection_reasons,
            },
        )

        # Emit instance metrics with region dimension
        metrics.add_dimension(name="Region", value=region)
        metrics.add_metric(
            name="InstancesScanned", unit=MetricUnit.Count, value=instance_scan_count
        )
        metrics.add_metric(
            name="InstancesProtected",
            unit=MetricUnit.Count,
            value=instance_protected_count,
        )
        metrics.add_metric(
            name="InstanceActions", unit=MetricUnit.Count, value=len(actions)
        )

        # Execute instance actions
        for action in actions:
            execute_cleanup_action(action, region)

        # Volume cleanup phase (after instance cleanup)
        volume_actions = []
        volume_scan_count = 0
        volume_protected_count = 0
        volume_protection_reasons: dict[str, int] = {}

        if not VOLUME_CLEANUP_ENABLED:
            logger.info("Volume cleanup disabled", extra={"region": region})
        else:
            try:
                # Query all available (unattached) volumes
                # Note: Removed legacy Name tag filter to catch untagged volumes
                volumes_response = ec2.describe_volumes(
                    Filters=[{"Name": "status", "Values": ["available"]}]
                )

                for volume in volumes_response["Volumes"]:
                    volume_scan_count += 1
                    tags_dict = convert_tags_to_dict(volume.get("Tags", []))

                    # Check protection first to track statistics
                    from .ec2.volumes import is_volume_protected

                    is_protected_flag, protection_reason = is_volume_protected(
                        tags_dict, volume["VolumeId"]
                    )
                    if is_protected_flag:
                        volume_protected_count += 1
                        volume_protection_reasons[protection_reason] = (
                            volume_protection_reasons.get(protection_reason, 0) + 1
                        )
                        continue

                    # Check if volume should be deleted
                    volume_action = check_unattached_volume(
                        volume, tags_dict, current_time
                    )

                    if volume_action:
                        volume_action.region = region
                        volume_actions.append(volume_action)

                # Log volume scan summary and emit metrics
                logger.info(
                    "Volume scan complete",
                    extra={
                        "region": region,
                        "volumes_scanned": volume_scan_count,
                        "actions_count": len(volume_actions),
                        "volumes_protected": volume_protected_count,
                        "protection_reasons": volume_protection_reasons,
                    },
                )

                # Emit volume metrics (region dimension already set)
                metrics.add_metric(
                    name="VolumesScanned",
                    unit=MetricUnit.Count,
                    value=volume_scan_count,
                )
                metrics.add_metric(
                    name="VolumesProtected",
                    unit=MetricUnit.Count,
                    value=volume_protected_count,
                )
                metrics.add_metric(
                    name="VolumeActions",
                    unit=MetricUnit.Count,
                    value=len(volume_actions),
                )

                # Execute volume deletions
                for volume_action in volume_actions:
                    delete_volume(volume_action, region)

            except Exception as vol_error:
                logger.error(f"Error during volume cleanup in {region}: {vol_error}")

        # Combine all actions for notification
        all_actions = actions + volume_actions

        # Send notification
        if all_actions:
            send_notification(all_actions, region)

        # Region completion with timing
        duration = time.time() - start_time
        logger.info(
            "Region cleanup complete",
            extra={
                "region": region,
                "duration_seconds": round(duration, 1),
                "instances_scanned": instance_scan_count,
                "instance_actions": len(actions),
                "volumes_scanned": volume_scan_count,
                "volume_actions": len(volume_actions),
            },
        )

    except Exception as e:
        logger.error(f"Error processing region {region}: {e}")

    return actions + volume_actions if "volume_actions" in locals() else actions


@logger.inject_lambda_context
@tracer.capture_lambda_handler
@metrics.log_metrics(capture_cold_start_metric=True)
def lambda_handler(event: dict[str, Any], context: LambdaContext) -> dict[str, Any]:
    """Main Lambda handler."""
    start_time = time.time()
    logger.info("Starting AWS resources cleanup", extra={"dry_run": DRY_RUN})

    try:
        ec2 = boto3.client("ec2")
        all_regions = [
            region["RegionName"] for region in ec2.describe_regions()["Regions"]
        ]

        # Filter regions based on TARGET_REGIONS parameter
        if TARGET_REGIONS and TARGET_REGIONS.lower() != "all":
            target_list = [r.strip() for r in TARGET_REGIONS.split(",") if r.strip()]
            regions = [r for r in all_regions if r in target_list]
            logger.info(
                "Filtering to specific regions",
                extra={"regions": regions, "regions_count": len(regions)},
            )
        else:
            regions = all_regions
            logger.info("Processing all regions", extra={"regions_count": len(regions)})

        all_actions = []

        for region in regions:
            region_actions = cleanup_region(region)
            all_actions.extend(region_actions)

        # Calculate summary statistics
        total_duration = time.time() - start_time
        action_counts: dict[str, int] = {}
        volume_ages = []

        for action in all_actions:
            action_counts[action.action] = action_counts.get(action.action, 0) + 1
            # Collect volume ages for statistics
            if action.action == "DELETE_VOLUME":
                volume_ages.append(action.days_overdue)

        # Enhanced summary with volume statistics
        summary_extra = {
            "total_actions": len(all_actions),
            "regions_count": len(regions),
            "duration_seconds": round(total_duration, 1),
            "actions_by_type": action_counts,
        }

        if volume_ages:
            summary_extra["volume_age_stats"] = {
                "min_days": round(min(volume_ages), 1),
                "max_days": round(max(volume_ages), 1),
                "avg_days": round(sum(volume_ages) / len(volume_ages), 1),
            }

        logger.info("Cleanup complete", extra=summary_extra)

        # Emit summary metrics (no region dimension for totals)
        metrics.add_metric(
            name="TotalActions", unit=MetricUnit.Count, value=len(all_actions)
        )
        metrics.add_metric(
            name="RegionsProcessed", unit=MetricUnit.Count, value=len(regions)
        )
        metrics.add_metric(
            name="ExecutionDuration", unit=MetricUnit.Seconds, value=total_duration
        )

        # Emit metrics per action type
        for action_type, count in action_counts.items():
            metrics.add_metric(
                name=f"Actions_{action_type}", unit=MetricUnit.Count, value=count
            )

        # Emit volume age statistics if available
        if volume_ages:
            metrics.add_metric(
                name="VolumeAge_Min", unit=MetricUnit.Count, value=min(volume_ages)
            )
            metrics.add_metric(
                name="VolumeAge_Max", unit=MetricUnit.Count, value=max(volume_ages)
            )
            metrics.add_metric(
                name="VolumeAge_Avg",
                unit=MetricUnit.Count,
                value=sum(volume_ages) / len(volume_ages),
            )

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
