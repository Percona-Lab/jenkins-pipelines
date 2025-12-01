"""Configuration from environment variables."""

import os

# Core configuration
DRY_RUN = os.environ.get("DRY_RUN", "true").lower() == "true"
SNS_TOPIC_ARN = os.environ.get("SNS_TOPIC_ARN", "")

# OpenShift cleanup configuration
OPENSHIFT_CLEANUP_ENABLED = (
    os.environ.get("OPENSHIFT_CLEANUP_ENABLED", "true").lower() == "true"
)
OPENSHIFT_BASE_DOMAIN = os.environ.get("OPENSHIFT_BASE_DOMAIN", "cd.percona.com")

# Region filtering
TARGET_REGIONS = os.environ.get("TARGET_REGIONS", "all")

# Logging configuration
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()


class Config:
    """Configuration singleton for OpenShift cleanup."""

    def __init__(self):
        self.dry_run = DRY_RUN
        self.sns_topic_arn = SNS_TOPIC_ARN
        self.openshift_cleanup_enabled = OPENSHIFT_CLEANUP_ENABLED
        self.openshift_base_domain = OPENSHIFT_BASE_DOMAIN
        self.target_regions = TARGET_REGIONS
        self.log_level = LOG_LEVEL
