"""AWS helper functions."""

from __future__ import annotations
import datetime
from typing import Any
from .logging_config import get_logger

logger = get_logger()


def convert_tags_to_dict(tags: list[dict[str, str]] | None) -> dict[str, str]:
    """Convert AWS tag list to dictionary."""
    return {tag["Key"]: tag["Value"] for tag in tags} if tags else {}


def has_valid_billing_tag(
    tags_dict: dict[str, str], instance_launch_time: Any = None
) -> bool:
    """
    Check if instance has a valid iit-billing-tag.

    For regular instances: any non-empty value is valid
    For timestamp-based tags: check if Unix timestamp is in the future
    """
    if "iit-billing-tag" not in tags_dict:
        return False

    tag_value = tags_dict["iit-billing-tag"]

    # Empty tag is invalid
    if not tag_value:
        return False

    # Try to parse as Unix timestamp (for EKS auto-expiration)
    try:
        expiration_timestamp = int(tag_value)
        current_timestamp = int(
            datetime.datetime.now(datetime.timezone.utc).timestamp()
        )

        # If it's a valid future timestamp, check if it's expired
        if expiration_timestamp > current_timestamp:
            return True
        else:
            logger.debug(
                f"Billing tag expired: {expiration_timestamp} < {current_timestamp} "
                f"(expired {current_timestamp - expiration_timestamp}s ago)"
            )
            return False
    except ValueError:
        # Not a timestamp, treat as category string (e.g., "pmm-staging", "CirrusCI")
        return True


def extract_cluster_name(tags_dict: dict[str, str]) -> str | None:
    """Extract cluster name from kubernetes tags."""
    for key in tags_dict.keys():
        if key.startswith("kubernetes.io/cluster/"):
            return key.split("/")[-1]
    return tags_dict.get("aws:eks:cluster-name")
