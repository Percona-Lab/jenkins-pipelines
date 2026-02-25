"""CleanupAction data class."""

from __future__ import annotations
from dataclasses import dataclass, asdict
from typing import Any


@dataclass
class CleanupAction:
    """Represents an OpenShift cluster cleanup action."""

    instance_id: str
    region: str
    name: str
    action: str  # TERMINATE_OPENSHIFT_CLUSTER
    reason: str
    days_overdue: float
    billing_tag: str = ""
    cluster_name: str | None = None
    owner: str | None = None

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        data = asdict(self)
        data["days_overdue"] = round(self.days_overdue, 2)
        return data
