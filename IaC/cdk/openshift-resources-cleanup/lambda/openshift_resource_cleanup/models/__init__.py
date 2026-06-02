"""Data models for EC2 cleanup Lambda."""

from .cleanup_action import CleanupAction
from .config import Config

__all__ = ["CleanupAction", "Config"]
