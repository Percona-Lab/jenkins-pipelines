"""Configuration from environment variables."""

import os

# Configuration from environment variables
DRY_RUN = os.environ.get("DRY_RUN", "true").lower() == "true"
SNS_TOPIC_ARN = os.environ.get("SNS_TOPIC_ARN", "")

# EC2 instance cleanup thresholds
UNTAGGED_THRESHOLD_MINUTES = int(os.environ.get("UNTAGGED_THRESHOLD_MINUTES", "30"))
STOPPED_THRESHOLD_DAYS = int(os.environ.get("STOPPED_THRESHOLD_DAYS", "30"))

# EKS cleanup configuration
EKS_CLEANUP_ENABLED = os.environ.get("EKS_CLEANUP_ENABLED", "true").lower() == "true"
EKS_SKIP_PATTERN = os.environ.get("EKS_SKIP_PATTERN", "pe-.*")

# OpenShift cleanup configuration
OPENSHIFT_CLEANUP_ENABLED = (
    os.environ.get("OPENSHIFT_CLEANUP_ENABLED", "true").lower() == "true"
)
OPENSHIFT_BASE_DOMAIN = os.environ.get("OPENSHIFT_BASE_DOMAIN", "cd.percona.com")
OPENSHIFT_MAX_RETRIES = int(os.environ.get("OPENSHIFT_MAX_RETRIES", "3"))

# Volume cleanup configuration
VOLUME_CLEANUP_ENABLED = (
    os.environ.get("VOLUME_CLEANUP_ENABLED", "true").lower() == "true"
)

# Region filtering
TARGET_REGIONS = os.environ.get("TARGET_REGIONS", "all")

# Logging configuration
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()

# Persistent billing tags (never auto-delete)
PERSISTENT_TAGS = {
    "jenkins-cloud",
    "jenkins-fb",
    "jenkins-pg",
    "jenkins-ps3",
    "jenkins-ps57",
    "jenkins-ps80",
    "jenkins-psmdb",
    "jenkins-pxb",
    "jenkins-pxc",
    "jenkins-rel",
    "pmm-dev",
}


class Config:
    """Configuration singleton."""

    def __init__(self):
        self.dry_run = DRY_RUN
        self.sns_topic_arn = SNS_TOPIC_ARN
        self.untagged_threshold_minutes = UNTAGGED_THRESHOLD_MINUTES
        self.stopped_threshold_days = STOPPED_THRESHOLD_DAYS
        self.eks_cleanup_enabled = EKS_CLEANUP_ENABLED
        self.eks_skip_pattern = EKS_SKIP_PATTERN
        self.openshift_cleanup_enabled = OPENSHIFT_CLEANUP_ENABLED
        self.openshift_base_domain = OPENSHIFT_BASE_DOMAIN
        self.openshift_max_retries = OPENSHIFT_MAX_RETRIES
        self.persistent_tags = PERSISTENT_TAGS
        self.volume_cleanup_enabled = VOLUME_CLEANUP_ENABLED
