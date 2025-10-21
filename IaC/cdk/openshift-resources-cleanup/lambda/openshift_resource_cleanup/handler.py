"""Main Lambda handler for OpenShift cluster cleanup."""

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
    TARGET_REGIONS,
    OPENSHIFT_CLEANUP_ENABLED,
    OPENSHIFT_BASE_DOMAIN,
    LOG_LEVEL,
)
from .utils import convert_tags_to_dict, get_logger
from .ec2 import execute_cleanup_action

logger = get_logger()
tracer = Tracer(service="openshift-cleanup")
metrics = Metrics(namespace="Percona/OpenShiftCleanup", service="openshift-cleanup")


def send_notification(actions: list[CleanupAction], region: str) -> None:
    """Send SNS notification about OpenShift cleanup actions."""
    if not SNS_TOPIC_ARN or not actions:
        return

    try:
        sns = boto3.client("sns")

        message_lines = [
            f"OpenShift Cluster Cleanup Report - {region}",
            f"Mode: {'DRY-RUN' if DRY_RUN else 'LIVE'}",
            f"Timestamp: {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}",
            "",
            f"Total Clusters: {len(actions)}",
            "",
        ]

        for action in actions:
            message_lines.append(f"Cluster: {action.cluster_name or 'Unknown'}")
            message_lines.append(f"  Instance: {action.instance_id}")
            message_lines.append(f"  Name: {action.name}")
            message_lines.append(f"  Action: {action.action}")
            message_lines.append(f"  Reason: {action.reason}")
            message_lines.append(f"  Billing Tag: {action.billing_tag}")
            if action.owner:
                message_lines.append(f"  Owner: {action.owner}")
            message_lines.append("")

        message = "\n".join(message_lines)
        subject = f"[{'DRY-RUN' if DRY_RUN else 'LIVE'}] OpenShift Cleanup: {len(actions)} clusters in {region}"

        sns.publish(
            TopicArn=SNS_TOPIC_ARN,
            Subject=subject[:100],
            Message=message,
        )

        logger.info(
            "Sent SNS notification",
            extra={"clusters_count": len(actions), "region": region},
        )

    except Exception as e:
        logger.error(f"Failed to send SNS notification: {e}")


def check_cluster_ttl(tags_dict: dict[str, str]) -> tuple[bool, float]:
    """Check if cluster TTL has expired.

    Args:
        tags_dict: Dictionary of instance tags

    Returns:
        Tuple of (should_delete, days_overdue):
        - (True, days_overdue) if TTL expired or no TTL tags (unmanaged)
        - (False, 0.0) if TTL not expired or malformed (fail-safe)
    """
    creation_time_str = tags_dict.get("creation-time")
    ttl_hours_str = tags_dict.get("delete-cluster-after-hours")

    # No TTL tags = unmanaged infrastructure, should delete
    if not creation_time_str or not ttl_hours_str:
        logger.info(
            "Cluster has no TTL tags, marking for deletion (unmanaged infrastructure)",
            extra={"tags": tags_dict},
        )
        return (True, 0.0)

    try:
        # Parse creation time - try Unix timestamp first (real clusters use this)
        # Then fall back to ISO format (for compatibility)
        try:
            # Try parsing as Unix timestamp (e.g., "1761053127")
            creation_timestamp = float(creation_time_str)
            creation_time = datetime.datetime.fromtimestamp(
                creation_timestamp, tz=datetime.timezone.utc
            )
        except (ValueError, OSError):
            # Fall back to ISO format parsing (e.g., "2025-01-15T05:00:00Z")
            creation_time = datetime.datetime.fromisoformat(
                creation_time_str.replace("Z", "+00:00")
            )

        # Parse TTL hours
        ttl_hours = float(ttl_hours_str)

        # Calculate expiry time
        expiry_time = creation_time + datetime.timedelta(hours=ttl_hours)
        current_time = datetime.datetime.now(datetime.timezone.utc)

        # Calculate time difference
        time_diff = current_time - expiry_time
        days_overdue = time_diff.total_seconds() / (24 * 3600)

        if days_overdue >= 0:
            # TTL expired
            logger.info(
                "Cluster TTL expired",
                extra={
                    "creation_time": creation_time_str,
                    "ttl_hours": ttl_hours,
                    "days_overdue": round(days_overdue, 2),
                },
            )
            return (True, days_overdue)
        else:
            # TTL not expired yet
            hours_remaining = -days_overdue * 24
            logger.info(
                "Cluster TTL not expired, skipping deletion",
                extra={
                    "creation_time": creation_time_str,
                    "ttl_hours": ttl_hours,
                    "hours_remaining": round(hours_remaining, 2),
                },
            )
            return (False, 0.0)

    except (ValueError, TypeError) as e:
        # Malformed TTL tags - fail-safe: don't delete
        logger.warning(
            f"Failed to parse TTL tags, skipping deletion (fail-safe): {e}",
            extra={
                "creation_time": creation_time_str,
                "ttl_hours": ttl_hours_str,
                "error": str(e),
            },
        )
        return (False, 0.0)


def is_openshift_instance(instance: dict, region: str) -> tuple[bool, str | None]:
    """Check if instance belongs to an OpenShift cluster (not EKS or other K8s).

    Args:
        instance: EC2 instance dictionary
        region: AWS region name

    Returns:
        Tuple of (is_openshift, infra_id):
        - (True, infra_id) if this is an OpenShift instance
        - (False, None) if not OpenShift
    """
    tags_dict = convert_tags_to_dict(instance.get("Tags", []))

    # Check 1: Red Hat ROSA specific tag (most reliable)
    if tags_dict.get("red-hat-clustertype") == "rosa":
        # Extract infra ID from kubernetes.io/cluster tag
        for tag in instance.get("Tags", []):
            if tag["Key"].startswith("kubernetes.io/cluster/"):
                infra_id = tag["Key"].split("/")[-1]
                logger.info(
                    "Detected OpenShift ROSA cluster via red-hat-clustertype tag",
                    extra={"infra_id": infra_id, "instance_id": instance["InstanceId"]},
                )
                return (True, infra_id)

    # Check 2: Red Hat managed tag
    if tags_dict.get("red-hat-managed") == "true":
        for tag in instance.get("Tags", []):
            if tag["Key"].startswith("kubernetes.io/cluster/"):
                infra_id = tag["Key"].split("/")[-1]
                logger.info(
                    "Detected OpenShift cluster via red-hat-managed tag",
                    extra={"infra_id": infra_id, "instance_id": instance["InstanceId"]},
                )
                return (True, infra_id)

    # Check 3: OpenShift Cluster API tag (not used by EKS)
    for tag in instance.get("Tags", []):
        if tag["Key"].startswith("sigs.k8s.io/cluster-api-provider-aws/cluster/"):
            infra_id = tag["Key"].split("/")[-1]
            logger.info(
                "Detected OpenShift cluster via cluster-api tag",
                extra={"infra_id": infra_id, "instance_id": instance["InstanceId"]},
            )
            return (True, infra_id)

    # Check 4: Instance name pattern (fallback for older detection)
    instance_name = tags_dict.get("Name", "")
    if "-master-" in instance_name or "openshift" in instance_name.lower():
        # Try to extract cluster name from instance name
        # Format: clustername-xxxxx-master-0
        parts = instance_name.split("-")
        if len(parts) >= 3:
            cluster_name = None
            for i, part in enumerate(parts):
                if part == "master" and i > 0:
                    cluster_name = "-".join(parts[: i - 1])
                    break

            if cluster_name:
                # Verify it's actually OpenShift by checking for infra ID
                from .openshift.detection import detect_openshift_infra_id

                infra_id = detect_openshift_infra_id(cluster_name, region)
                if infra_id:
                    logger.info(
                        "Detected OpenShift cluster via instance name pattern",
                        extra={
                            "infra_id": infra_id,
                            "cluster_name": cluster_name,
                            "instance_id": instance["InstanceId"],
                        },
                    )
                    return (True, infra_id)

    return (False, None)


def extract_cluster_name_from_infra_id(infra_id: str) -> str:
    """Extract base cluster name from infra ID.

    Example: jvp-rosa1-qmdkk -> jvp-rosa1
    """
    parts = infra_id.split("-")
    if len(parts) >= 2:
        # Infra ID format is typically: clustername-randomid
        # Return everything except the last part (random ID)
        return "-".join(parts[:-1])
    return infra_id


@tracer.capture_method
def cleanup_region(region: str, execution_id: str | None = None) -> list[CleanupAction]:
    """Process OpenShift cluster cleanup for a single region."""
    start_time = time.time()
    logger.info(
        "Processing region for OpenShift cleanup",
        extra={
            "region": region,
            "execution_id": execution_id,
            "stage": "region_start",
        },
    )

    ec2 = boto3.client("ec2", region_name=region)
    actions = []

    # Track instance scan statistics
    instance_scan_count = 0
    openshift_clusters_found = 0

    try:
        # Scan for running instances to detect OpenShift clusters
        response = ec2.describe_instances(
            Filters=[{"Name": "instance-state-name", "Values": ["running", "stopped"]}]
        )

        # Track clusters we've already processed (by infra_id)
        processed_clusters = set()

        for reservation in response["Reservations"]:
            for instance in reservation["Instances"]:
                instance_scan_count += 1
                tags_dict = convert_tags_to_dict(instance.get("Tags", []))
                instance_name = tags_dict.get("Name", "")

                # Check if this is an OpenShift instance (not EKS or other K8s)
                is_openshift, infra_id = is_openshift_instance(instance, region)

                if is_openshift and infra_id:
                    # Avoid processing the same cluster multiple times
                    if infra_id in processed_clusters:
                        continue

                    processed_clusters.add(infra_id)
                    openshift_clusters_found += 1

                    # Extract cluster name from infra ID
                    cluster_name = extract_cluster_name_from_infra_id(infra_id)

                    # Check TTL before marking for deletion
                    should_delete, days_overdue = check_cluster_ttl(tags_dict)

                    if should_delete:
                        # Create cleanup action for this cluster
                        reason = (
                            f"OpenShift cluster TTL expired ({days_overdue:.2f} days overdue)"
                            if days_overdue > 0
                            else "OpenShift cluster has no TTL tags (unmanaged infrastructure)"
                        )
                        action = CleanupAction(
                            instance_id=instance["InstanceId"],
                            region=region,
                            name=instance_name,
                            action="TERMINATE_OPENSHIFT_CLUSTER",
                            reason=reason,
                            days_overdue=days_overdue,
                            billing_tag=tags_dict.get("iit-billing-tag", ""),
                            cluster_name=cluster_name,
                            owner=tags_dict.get("owner", None),
                        )
                        actions.append(action)

        # Log scan summary
        logger.info(
            "OpenShift scan complete",
            extra={
                "region": region,
                "execution_id": execution_id,
                "stage": "scan_complete",
                "statistics": {
                    "instances_scanned": instance_scan_count,
                    "openshift_clusters_found": openshift_clusters_found,
                    "actions_count": len(actions),
                },
            },
        )

        # Emit metrics with region dimension
        metrics.add_dimension(name="Region", value=region)
        metrics.add_metric(
            name="InstancesScanned", unit=MetricUnit.Count, value=instance_scan_count
        )
        metrics.add_metric(
            name="OpenShiftClustersFound",
            unit=MetricUnit.Count,
            value=openshift_clusters_found,
        )
        metrics.add_metric(
            name="CleanupActions", unit=MetricUnit.Count, value=len(actions)
        )

        # Execute cleanup actions
        for action in actions:
            execute_cleanup_action(action, region)

        # Send notification
        if actions:
            send_notification(actions, region)

        # Region completion with timing
        duration = time.time() - start_time
        logger.info(
            "Region cleanup complete",
            extra={
                "region": region,
                "execution_id": execution_id,
                "stage": "region_complete",
                "performance": {
                    "duration_seconds": round(duration, 2),
                    "instances_per_second": (
                        round(instance_scan_count / duration, 2) if duration > 0 else 0
                    ),
                },
                "summary": {
                    "instances_scanned": instance_scan_count,
                    "openshift_clusters_found": openshift_clusters_found,
                    "total_actions": len(actions),
                },
            },
        )

    except Exception as e:
        logger.error(f"Error processing region {region}: {e}")

    return actions


@logger.inject_lambda_context
@tracer.capture_lambda_handler
@metrics.log_metrics(capture_cold_start_metric=True)
def lambda_handler(event: dict[str, Any], context: LambdaContext) -> dict[str, Any]:
    """Main Lambda handler for OpenShift cleanup."""
    start_time = time.time()
    execution_id = context.aws_request_id

    # Log configuration at startup
    logger.info(
        "OpenShift Cleanup Lambda initialized",
        extra={
            "execution_id": execution_id,
            "lambda_name": context.function_name,
            "lambda_version": context.function_version,
            "mode": "DRY_RUN" if DRY_RUN else "LIVE",
            "configuration": {
                "dry_run": DRY_RUN,
                "log_level": LOG_LEVEL,
                "cleanup_features": {
                    "openshift_cleanup_enabled": OPENSHIFT_CLEANUP_ENABLED,
                    "openshift_base_domain": OPENSHIFT_BASE_DOMAIN,
                },
                "notifications": {
                    "sns_enabled": bool(SNS_TOPIC_ARN),
                    "sns_topic": SNS_TOPIC_ARN if SNS_TOPIC_ARN else "disabled",
                },
                "regions": {
                    "target_regions": (
                        TARGET_REGIONS if TARGET_REGIONS != "all" else "all"
                    ),
                },
            },
            "event": event if event else {},
        },
    )

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
                "Target regions configured",
                extra={
                    "execution_id": execution_id,
                    "regions": regions,
                    "regions_count": len(regions),
                    "filter_type": "specific",
                },
            )
        else:
            regions = all_regions
            logger.info(
                "Target regions configured",
                extra={
                    "execution_id": execution_id,
                    "regions_count": len(regions),
                    "filter_type": "all",
                },
            )

        all_actions = []
        regions_processed = []
        regions_with_actions = []

        for region in regions:
            region_actions = cleanup_region(region, execution_id)
            all_actions.extend(region_actions)
            regions_processed.append(region)
            if region_actions:
                regions_with_actions.append(region)

        # Calculate summary statistics
        total_duration = time.time() - start_time
        action_counts: dict[str, int] = {}

        for action in all_actions:
            action_counts[action.action] = action_counts.get(action.action, 0) + 1

        summary = {
            "execution_id": execution_id,
            "stage": "execution_complete",
            "mode": "DRY_RUN" if DRY_RUN else "LIVE",
            "performance": {
                "total_duration_seconds": round(total_duration, 2),
                "regions_per_second": (
                    round(len(regions) / total_duration, 2) if total_duration > 0 else 0
                ),
                "actions_per_second": (
                    round(len(all_actions) / total_duration, 2)
                    if total_duration > 0
                    else 0
                ),
            },
            "regions": {
                "total_regions": len(regions),
                "regions_processed": len(regions_processed),
                "regions_with_actions": len(regions_with_actions),
                "regions_list": regions_with_actions,
            },
            "openshift": {
                "total_clusters_found": len(all_actions),
                "clusters_by_region": action_counts,
            },
        }

        logger.info("OpenShift Cleanup execution complete", extra=summary)

        # Emit summary metrics
        metrics.add_metric(
            name="TotalActions", unit=MetricUnit.Count, value=len(all_actions)
        )
        metrics.add_metric(
            name="RegionsProcessed", unit=MetricUnit.Count, value=len(regions)
        )
        metrics.add_metric(
            name="ExecutionDuration", unit=MetricUnit.Seconds, value=total_duration
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
