"""CleanupAction data class."""

from __future__ import annotations
from dataclasses import dataclass, asdict
from typing import Any


@dataclass
class CleanupAction:
    """Represents a cleanup action to be taken on AWS resources (instances, volumes, etc)."""

    instance_id: str  # For instances; empty string for volumes
    region: str
    name: str
    action: str  # TERMINATE, STOP, DELETE_VOLUME, TERMINATE_CLUSTER, TERMINATE_OPENSHIFT_CLUSTER
    reason: str
    days_overdue: float
    billing_tag: str = ""
    cluster_name: str | None = None
    owner: str | None = None
    resource_type: str = "instance"  # "instance" or "volume"
    volume_id: str | None = None  # For volumes; None for instances

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        data = asdict(self)
        data["days_overdue"] = round(self.days_overdue, 2)
        return data
