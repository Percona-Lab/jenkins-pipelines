"""OpenShift Cluster Cleanup Lambda for AWS."""

from .handler import lambda_handler

__version__ = "3.0.0"
__description__ = "Automated OpenShift cluster infrastructure cleanup"

__all__ = ["lambda_handler"]
