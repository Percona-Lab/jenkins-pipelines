"""Pytest configuration and shared fixtures for AWS resource cleanup tests."""

from __future__ import annotations
import datetime
import pytest
from typing import Any


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


# Shared fixtures


@pytest.fixture
def instance_builder():
    """Fixture that returns a new InstanceBuilder."""
    return InstanceBuilder()


@pytest.fixture
def current_time():
    """Fixture for current time as Unix timestamp."""
    return 1000000


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
