"""Pytest configuration and shared fixtures for AWS resource cleanup tests.

This file contains:
1. InstanceBuilder - Builder pattern for creating test EC2 instances
2. Fixture factories - Reusable functions for creating test data (make_instance, time_utils)
3. Time utilities - Helpers for time-based test scenarios
4. Legacy fixtures - Deprecated fixtures kept for backward compatibility
"""

from __future__ import annotations
import datetime
import pytest
from typing import Any, Callable


class VolumeBuilder:
    """Builder pattern for creating test EBS volumes.

    This builder helps create test volume data structures with various
    configurations without needing to mock AWS services.
    """

    def __init__(self):
        self._volume = {
            "VolumeId": "vol-test123456",
            "State": "available",
            "CreateTime": datetime.datetime.now(datetime.timezone.utc),
            "Size": 10,
            "VolumeType": "gp3",
            "Tags": [],
        }

    def with_volume_id(self, volume_id: str) -> VolumeBuilder:
        """Set volume ID."""
        self._volume["VolumeId"] = volume_id
        return self

    def with_name(self, name: str) -> VolumeBuilder:
        """Set Name tag."""
        self._add_tag("Name", name)
        return self

    def with_state(self, state: str) -> VolumeBuilder:
        """Set volume state (available, in-use, creating, deleting)."""
        self._volume["State"] = state
        return self

    def with_create_time(self, create_time: datetime.datetime) -> VolumeBuilder:
        """Set create time."""
        self._volume["CreateTime"] = create_time
        return self

    def with_size(self, size_gb: int) -> VolumeBuilder:
        """Set volume size in GB."""
        self._volume["Size"] = size_gb
        return self

    def with_billing_tag(self, billing_tag: str) -> VolumeBuilder:
        """Add iit-billing-tag."""
        self._add_tag("iit-billing-tag", billing_tag)
        return self

    def with_tag(self, key: str, value: str) -> VolumeBuilder:
        """Add custom tag."""
        self._add_tag(key, value)
        return self

    def _add_tag(self, key: str, value: str):
        """Internal method to add a tag."""
        self._volume["Tags"].append({"Key": key, "Value": value})

    def build(self) -> dict[str, Any]:
        """Build and return the volume dictionary."""
        return self._volume


class InstanceBuilder:
    """Builder pattern for creating test EC2 instances.

    This builder helps create test instance data structures with various
    configurations without needing to mock AWS services.
    """

    def __init__(self):
        self._instance = {
            "InstanceId": "i-test123456",
            "State": {"Name": "running"},
            "LaunchTime": datetime.datetime.now(datetime.timezone.utc),
            "Tags": [],
        }

    def with_instance_id(self, instance_id: str) -> InstanceBuilder:
        """Set instance ID."""
        self._instance["InstanceId"] = instance_id
        return self

    def with_name(self, name: str) -> InstanceBuilder:
        """Set Name tag."""
        self._add_tag("Name", name)
        return self

    def with_state(self, state: str) -> InstanceBuilder:
        """Set instance state (running, stopped)."""
        self._instance["State"]["Name"] = state
        return self

    def with_launch_time(self, launch_time: datetime.datetime) -> InstanceBuilder:
        """Set launch time."""
        self._instance["LaunchTime"] = launch_time
        return self

    def with_ttl_tags(
        self, creation_time: int, delete_after_hours: int
    ) -> InstanceBuilder:
        """Add TTL tags (creation-time and delete-cluster-after-hours)."""
        self._add_tag("creation-time", str(creation_time))
        self._add_tag("delete-cluster-after-hours", str(delete_after_hours))
        return self

    def with_billing_tag(self, billing_tag: str) -> InstanceBuilder:
        """Add iit-billing-tag."""
        self._add_tag("iit-billing-tag", billing_tag)
        return self

    def with_owner(self, owner: str) -> InstanceBuilder:
        """Add owner tag."""
        self._add_tag("owner", owner)
        return self

    def with_cluster_name(self, cluster_name: str) -> InstanceBuilder:
        """Add cluster-name tag."""
        self._add_tag("cluster-name", cluster_name)
        return self

    def with_stop_after_days(self, days: int) -> InstanceBuilder:
        """Add stop-after-days tag."""
        self._add_tag("stop-after-days", str(days))
        return self

    def with_openshift_tags(self, infra_id: str) -> InstanceBuilder:
        """Add OpenShift-specific tags."""
        self._add_tag("iit-billing-tag", "openshift")
        self._add_tag(f"kubernetes.io/cluster/{infra_id}", "owned")
        return self

    def with_eks_tags(self, cluster_name: str) -> InstanceBuilder:
        """Add EKS-specific tags."""
        self._add_tag("iit-billing-tag", "eks")
        self._add_tag(f"kubernetes.io/cluster/{cluster_name}", "owned")
        return self

    def with_tag(self, key: str, value: str) -> InstanceBuilder:
        """Add custom tag."""
        self._add_tag(key, value)
        return self

    def _add_tag(self, key: str, value: str):
        """Internal method to add a tag."""
        self._instance["Tags"].append({"Key": key, "Value": value})

    def build(self) -> dict[str, Any]:
        """Build and return the instance dictionary."""
        return self._instance


# ===== Core Fixtures =====


@pytest.fixture
def instance_builder():
    """Fixture that returns a new InstanceBuilder."""
    return InstanceBuilder()


@pytest.fixture
def volume_builder():
    """Fixture that returns a new VolumeBuilder."""
    return VolumeBuilder()


@pytest.fixture
def current_time():
    """Fixture for current time as Unix timestamp."""
    return 1000000


# ===== Fixture Factories =====


@pytest.fixture
def make_instance(instance_builder, current_time):
    """Factory fixture for creating test instances with various configurations.
    
    This replaces multiple similar fixtures with a single flexible factory.
    
    Args:
        name: Instance name (default: "test-instance")
        state: Instance state (default: "running")
        billing_tag: Billing tag value (default: None)
        ttl_expired: Whether TTL should be expired (default: False)
        ttl_hours: TTL duration in hours (default: 1)
        hours_old: How many hours ago instance was launched (default: 0)
        days_old: How many days ago instance was launched (default: 0)
        protected: Use protected billing tag (default: False)
        openshift: Add OpenShift tags (default: False)
        eks: Add EKS tags (default: False)
        owner: Owner tag (default: None)
        cluster_name: Cluster name tag (default: None)
        stop_after_days: Add stop-after-days tag (default: None)
        **kwargs: Additional custom tags
    
    Returns:
        dict: Instance data structure
        
    Example:
        # Simple instance
        instance = make_instance(name="test", billing_tag="pmm-staging")
        
        # Expired TTL instance
        instance = make_instance(ttl_expired=True, ttl_hours=1, hours_old=3)
        
        # Protected OpenShift instance
        instance = make_instance(protected=True, openshift=True)
    """
    def _make(
        name: str = "test-instance",
        state: str = "running",
        billing_tag: str | None = None,
        ttl_expired: bool = False,
        ttl_hours: int = 1,
        hours_old: int = 0,
        days_old: int = 0,
        protected: bool = False,
        openshift: bool = False,
        eks: bool = False,
        owner: str | None = None,
        cluster_name: str | None = None,
        stop_after_days: int | None = None,
        **kwargs
    ) -> dict[str, Any]:
        # Calculate launch time
        total_seconds = (days_old * 86400) + (hours_old * 3600)
        launch_time = datetime.datetime.fromtimestamp(
            current_time - total_seconds,
            tz=datetime.timezone.utc
        )
        
        # Build instance
        builder = (
            instance_builder
            .with_name(name)
            .with_state(state)
            .with_launch_time(launch_time)
        )
        
        # Apply protection
        if protected:
            builder = builder.with_billing_tag("jenkins-dev-pmm")
        elif billing_tag:
            builder = builder.with_billing_tag(billing_tag)
        
        # Apply TTL tags
        if ttl_expired:
            creation_time = current_time - (ttl_hours * 3600 + 3600)  # Expired by 1 hour
            builder = builder.with_ttl_tags(creation_time, ttl_hours)
        
        # Apply cluster tags
        if openshift:
            infra_id = kwargs.pop('infra_id', 'test-infra-123')
            builder = builder.with_openshift_tags(infra_id)
            if not cluster_name:
                cluster_name = 'test-openshift'
        
        if eks:
            eks_cluster = kwargs.pop('eks_cluster', 'test-eks-cluster')
            builder = builder.with_eks_tags(eks_cluster)
            if not cluster_name:
                cluster_name = eks_cluster
        
        # Apply optional tags
        if owner:
            builder = builder.with_owner(owner)
        if cluster_name:
            builder = builder.with_cluster_name(cluster_name)
        if stop_after_days is not None:
            builder = builder.with_stop_after_days(stop_after_days)
        
        # Apply custom tags
        for key, value in kwargs.items():
            builder = builder.with_tag(key, str(value))
        
        return builder.build()
    
    return _make


@pytest.fixture
def time_utils(current_time):
    """Utility functions for time-based test scenarios.
    
    Provides consistent time handling across all tests.
    
    Example:
        # Get times relative to current_time
        three_hours_ago = time_utils.hours_ago(3)
        thirty_days_ago = time_utils.days_ago(30)
        
        # Get timestamps
        ts = time_utils.timestamp()
        old_ts = time_utils.timestamp(time_utils.days_ago(5))
    """
    class TimeUtils:
        @staticmethod
        def now() -> datetime.datetime:
            """Get current time as datetime."""
            return datetime.datetime.fromtimestamp(
                current_time,
                tz=datetime.timezone.utc
            )
        
        @staticmethod
        def timestamp(dt: datetime.datetime | None = None) -> int:
            """Convert datetime to Unix timestamp."""
            if dt is None:
                return current_time
            return int(dt.timestamp())
        
        @staticmethod
        def hours_ago(hours: int) -> datetime.datetime:
            """Get datetime N hours in the past."""
            return datetime.datetime.fromtimestamp(
                current_time - (hours * 3600),
                tz=datetime.timezone.utc
            )
        
        @staticmethod
        def days_ago(days: int) -> datetime.datetime:
            """Get datetime N days in the past."""
            return datetime.datetime.fromtimestamp(
                current_time - (days * 86400),
                tz=datetime.timezone.utc
            )
        
        @staticmethod
        def seconds_ago(seconds: int) -> datetime.datetime:
            """Get datetime N seconds in the past."""
            return datetime.datetime.fromtimestamp(
                current_time - seconds,
                tz=datetime.timezone.utc
            )
    
    return TimeUtils()


# ===== Legacy Fixtures (Deprecated - Use make_instance instead) =====
# These fixtures are kept for backward compatibility during migration.
# New tests should use make_instance fixture factory.


@pytest.fixture
def instance_with_valid_billing_tag(instance_builder):
    """Instance with valid billing tag."""
    return (
        instance_builder.with_name("test-instance")
        .with_billing_tag("pmm-staging")
        .with_owner("test-user")
        .build()
    )


@pytest.fixture
def instance_with_expired_ttl(instance_builder, current_time):
    """Instance with expired TTL (created 2 hours ago, TTL 1 hour)."""
    creation_time = current_time - 7200  # 2 hours ago
    return (
        instance_builder.with_name("expired-instance")
        .with_ttl_tags(creation_time, 1)  # 1 hour TTL
        .with_billing_tag("test-billing")
        .with_owner("test-user")
        .build()
    )


@pytest.fixture
def instance_without_billing_tag(instance_builder):
    """Instance without any billing tag."""
    now = datetime.datetime.now(datetime.timezone.utc)
    old_time = now - datetime.timedelta(hours=2)
    return (
        instance_builder.with_name("untagged-instance")
        .with_launch_time(old_time)
        .build()
    )


@pytest.fixture
def instance_stopped_long_term(instance_builder):
    """Instance stopped for more than 30 days."""
    now = datetime.datetime.now(datetime.timezone.utc)
    old_time = now - datetime.timedelta(days=35)
    return (
        instance_builder.with_name("long-stopped")
        .with_state("stopped")
        .with_launch_time(old_time)
        .with_billing_tag("test-billing")
        .build()
    )


@pytest.fixture
def instance_with_stop_policy(instance_builder):
    """Instance with stop-after-days policy."""
    now = datetime.datetime.now(datetime.timezone.utc)
    old_time = now - datetime.timedelta(days=8)
    return (
        instance_builder.with_name("pmm-staging")
        .with_state("running")
        .with_launch_time(old_time)
        .with_stop_after_days(7)
        .with_billing_tag("pmm-staging")
        .build()
    )


@pytest.fixture
def protected_instance(instance_builder):
    """Instance with persistent billing tag (protected)."""
    return (
        instance_builder.with_name("protected-instance")
        .with_billing_tag("jenkins-dev-pmm")
        .build()
    )


@pytest.fixture
def openshift_cluster_instance(instance_builder, current_time):
    """Instance that's part of an OpenShift cluster with expired TTL."""
    creation_time = current_time - 7200  # 2 hours ago
    return (
        instance_builder.with_name("openshift-master")
        .with_ttl_tags(creation_time, 1)
        .with_openshift_tags("test-infra-123")
        .with_cluster_name("test-openshift")
        .with_owner("test-user")
        .build()
    )


@pytest.fixture
def eks_cluster_instance(instance_builder, current_time):
    """Instance that's part of an EKS cluster with expired TTL."""
    creation_time = current_time - 7200  # 2 hours ago
    return (
        instance_builder.with_name("eks-node")
        .with_ttl_tags(creation_time, 1)
        .with_eks_tags("test-eks-cluster")
        .with_cluster_name("test-eks-cluster")
        .with_owner("test-user")
        .build()
    )


@pytest.fixture
def tags_dict_from_instance():
    """Helper function to convert instance tags to dictionary format."""

    def _convert(instance: dict[str, Any]) -> dict[str, str]:
        """Convert Tags list to dict."""
        return {tag["Key"]: tag["Value"] for tag in instance.get("Tags", [])}

    return _convert
