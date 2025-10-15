"""EC2 cleanup policy checks (TTL, stop-after-days, long-stopped, untagged)."""

from __future__ import annotations
import datetime
from typing import Any
from ..models import CleanupAction
from ..models.config import UNTAGGED_THRESHOLD_MINUTES, STOPPED_THRESHOLD_DAYS
from ..utils import extract_cluster_name, has_valid_billing_tag, get_logger

logger = get_logger()


def check_ttl_expiration(
    instance: dict[str, Any], tags_dict: dict[str, str], current_time: int
) -> CleanupAction | None:
    """
    Check if instance has expired based on creation-time + delete-cluster-after-hours.
    Returns CleanupAction if expired, None otherwise.
    """
    creation_time_str = tags_dict.get("creation-time")
    ttl_hours_str = tags_dict.get("delete-cluster-after-hours")

    if not creation_time_str or not ttl_hours_str:
        return None

    try:
        creation_time = int(creation_time_str)
        ttl_hours = int(ttl_hours_str)
    except ValueError:
        logger.warning(
            f"Invalid TTL tags for {instance['InstanceId']}: "
            f"creation-time={creation_time_str}, ttl={ttl_hours_str}"
        )
        return None

    expiration_time = creation_time + (ttl_hours * 3600)

    if current_time >= expiration_time:
        seconds_overdue = current_time - expiration_time
        days_overdue = seconds_overdue / 86400

        name = tags_dict.get("Name", "N/A")
        billing_tag = tags_dict.get("iit-billing-tag", "unknown")
        cluster_name = extract_cluster_name(tags_dict)
        owner = tags_dict.get("owner", "unknown")

        created_at = datetime.datetime.fromtimestamp(
            creation_time, tz=datetime.timezone.utc
        ).strftime("%Y-%m-%d %H:%M:%S UTC")
        expired_at = datetime.datetime.fromtimestamp(
            expiration_time, tz=datetime.timezone.utc
        ).strftime("%Y-%m-%d %H:%M:%S UTC")

        reason = f"TTL expired: {ttl_hours}h policy. Created {created_at}, expired {expired_at}"

        action = "TERMINATE"
        if cluster_name:
            is_openshift = billing_tag == "openshift" or any(
                tag.startswith("openshift-") for tag in tags_dict.keys()
            )
            action = (
                "TERMINATE_OPENSHIFT_CLUSTER" if is_openshift else "TERMINATE_CLUSTER"
            )

        return CleanupAction(
            instance_id=instance["InstanceId"],
            region="",  # Set by caller
            name=name,
            action=action,
            reason=reason,
            days_overdue=days_overdue,
            billing_tag=billing_tag,
            cluster_name=cluster_name,
            owner=owner,
        )

    return None


def check_stop_after_days(
    instance: dict[str, Any], tags_dict: dict[str, str], current_time: int
) -> CleanupAction | None:
    """
    Check if instance should be stopped based on stop-after-days policy.
    Used for PMM staging instances.
    """
    stop_after_days_str = tags_dict.get("stop-after-days")

    if not stop_after_days_str or instance["State"]["Name"] != "running":
        return None

    try:
        stop_after_days = int(stop_after_days_str)
    except ValueError:
        return None

    launch_time = instance.get("LaunchTime")
    if not launch_time:
        return None

    launch_timestamp = int(launch_time.timestamp())
    stop_at_time = launch_timestamp + (stop_after_days * 86400)

    if current_time >= stop_at_time:
        seconds_overdue = current_time - stop_at_time
        days_overdue = seconds_overdue / 86400

        name = tags_dict.get("Name", "N/A")
        billing_tag = tags_dict.get("iit-billing-tag", "unknown")
        owner = tags_dict.get("owner", "unknown")

        launched_at = launch_time.strftime("%Y-%m-%d %H:%M:%S UTC")

        reason = f"Stop policy: {stop_after_days}d. Launched {launched_at}"

        return CleanupAction(
            instance_id=instance["InstanceId"],
            region="",
            name=name,
            action="STOP",
            reason=reason,
            days_overdue=days_overdue,
            billing_tag=billing_tag,
            owner=owner,
        )

    return None


def check_long_stopped(
    instance: dict[str, Any], tags_dict: dict[str, str], current_time: int
) -> CleanupAction | None:
    """
    Check if instance has been stopped for more than the configured threshold.
    Stopped instances still incur EBS storage costs.
    Uses configurable threshold from environment variable.
    """
    if instance["State"]["Name"] != "stopped":
        return None

    launch_time = instance.get("LaunchTime")
    if not launch_time:
        return None

    launch_timestamp = int(launch_time.timestamp())
    days_since_launch = (current_time - launch_timestamp) / 86400

    if days_since_launch > STOPPED_THRESHOLD_DAYS:
        name = tags_dict.get("Name", "N/A")
        billing_tag = tags_dict.get("iit-billing-tag", "unknown")

        days_overdue = days_since_launch - STOPPED_THRESHOLD_DAYS
        reason = f"Stopped instance older than {STOPPED_THRESHOLD_DAYS} days"

        return CleanupAction(
            instance_id=instance["InstanceId"],
            region="",
            name=name,
            action="TERMINATE",
            reason=reason,
            days_overdue=days_overdue,
            billing_tag=billing_tag,
        )

    return None


def check_untagged(
    instance: dict[str, Any], tags_dict: dict[str, str], current_time: int
) -> CleanupAction | None:
    """
    Check if instance is untagged or has invalid billing tag.
    Uses configurable threshold from environment variable.
    """
    # Skip if has valid billing tag (including non-expired timestamps)
    if has_valid_billing_tag(tags_dict, instance.get("LaunchTime")):
        return None

    launch_time = instance.get("LaunchTime")
    if not launch_time:
        return None

    launch_timestamp = int(launch_time.timestamp())
    minutes_running = (current_time - launch_timestamp) / 60

    if minutes_running < UNTAGGED_THRESHOLD_MINUTES:
        return None

    days_running = minutes_running / 1440
    name = tags_dict.get("Name", "N/A")

    reason = f"Missing billing tag. Running {minutes_running:.0f} minutes (threshold: {UNTAGGED_THRESHOLD_MINUTES})"

    return CleanupAction(
        instance_id=instance["InstanceId"],
        region="",
        name=name,
        action="TERMINATE",
        reason=reason,
        days_overdue=days_running,
        billing_tag="<MISSING>",
    )
