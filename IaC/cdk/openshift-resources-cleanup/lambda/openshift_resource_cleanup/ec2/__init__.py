"""EC2 operations for OpenShift cluster cleanup."""

from .instances import execute_cleanup_action

__all__ = [
    "execute_cleanup_action",
]
