#!/usr/bin/env python3
"""
Test script for removeUntaggedEc2 logic.
Does not connect to AWS - uses mock instance objects.
"""

import datetime
from dataclasses import dataclass
from typing import Dict, List


# Mock instance class
@dataclass
class MockInstance:
    id: str
    tags: List[Dict[str, str]]
    launch_time: datetime.datetime
    key_name: str
    placement: Dict[str, str]


# Import the logic functions (without AWS SDK calls)
def convert_tags_to_dict(tags):
    return {tag["Key"]: tag["Value"] for tag in tags} if tags else {}


def get_eks_cluster_name(tags_dict):
    """Extract EKS cluster name from instance tags"""
    cluster_keys = ["aws:eks:cluster-name", "eks:eks-cluster-name"]

    for key in cluster_keys:
        if key in tags_dict:
            return tags_dict[key]

    for key in tags_dict.keys():
        if key.startswith("kubernetes.io/cluster/"):
            return key.replace("kubernetes.io/cluster/", "")

    return None


def has_valid_billing_tag(tags_dict, instance_launch_time):
    """
    Check if instance has a valid iit-billing-tag.

    For regular instances: any non-empty value is valid
    For timestamp-based tags: check if Unix timestamp is in the future
    """
    if "iit-billing-tag" not in tags_dict:
        return False

    tag_value = tags_dict["iit-billing-tag"]

    if not tag_value:
        return False

    try:
        expiration_timestamp = int(tag_value)
        current_timestamp = int(
            datetime.datetime.now(datetime.timezone.utc).timestamp()
        )

        if expiration_timestamp > current_timestamp:
            return True
        else:
            return False
    except ValueError:
        # Not a timestamp, treat as category string
        return True


def is_eks_managed_instance(instance, region, eks_skip_pattern="pe-.*"):
    """Check if instance is managed by EKS and if it should be skipped"""
    import re

    tags_dict = convert_tags_to_dict(instance.tags)

    eks_indicators = [
        "kubernetes.io/cluster/",
        "aws:eks:cluster-name",
        "eks:eks-cluster-name",
        "eks:kubernetes-node-pool-name",
        "aws:ec2:managed-launch",
    ]

    is_eks = False
    for key in tags_dict.keys():
        for indicator in eks_indicators:
            if indicator in key:
                is_eks = True
                break
        if is_eks:
            break

    if not is_eks:
        return False, None

    cluster_name = get_eks_cluster_name(tags_dict)
    has_billing_tag = has_valid_billing_tag(tags_dict, instance.launch_time)

    # If has valid billing tag, always skip (it's legitimate)
    if has_billing_tag:
        return True, "has valid billing tag"

    # No billing tag - check skip pattern
    if cluster_name and eks_skip_pattern:
        if re.match(eks_skip_pattern, cluster_name):
            return True, f"matches skip pattern '{eks_skip_pattern}'"
        else:
            return (
                True,
                "marked for cluster deletion (no billing tag, doesn't match pattern)",
            )

    return True, "EKS instance (no cluster name)"


def is_instance_to_terminate(instance, grace_period_seconds=600):
    """Check if instance should be terminated"""
    tags_dict = convert_tags_to_dict(instance.tags)
    has_billing_tag = has_valid_billing_tag(tags_dict, instance.launch_time)

    current_time = datetime.datetime.now(datetime.timezone.utc)
    running_time = current_time - instance.launch_time

    if not has_billing_tag and running_time.total_seconds() > grace_period_seconds:
        return True
    return False


# Test cases
def create_test_instances():
    """Create test instances with different scenarios"""
    now = datetime.datetime.now(datetime.timezone.utc)
    one_hour_ago = now - datetime.timedelta(hours=1)
    five_minutes_ago = now - datetime.timedelta(minutes=5)
    future_timestamp = int((now + datetime.timedelta(days=1)).timestamp())
    past_timestamp = int((now - datetime.timedelta(hours=1)).timestamp())

    instances = [
        # Regular EC2 instances
        MockInstance(
            id="i-regular-with-tag",
            tags=[
                {"Key": "Name", "Value": "jenkins-worker"},
                {"Key": "iit-billing-tag", "Value": "jenkins-pmm-slave"},
            ],
            launch_time=one_hour_ago,
            key_name="jenkins-key",
            placement={"AvailabilityZone": "us-east-2a"},
        ),
        MockInstance(
            id="i-regular-no-tag-old",
            tags=[{"Key": "Name", "Value": "orphaned-instance"}],
            launch_time=one_hour_ago,
            key_name="test-key",
            placement={"AvailabilityZone": "us-east-2b"},
        ),
        MockInstance(
            id="i-regular-no-tag-new",
            tags=[{"Key": "Name", "Value": "just-launched"}],
            launch_time=five_minutes_ago,
            key_name="test-key",
            placement={"AvailabilityZone": "us-east-2c"},
        ),
        MockInstance(
            id="i-regular-timestamp-valid",
            tags=[
                {"Key": "Name", "Value": "temp-instance"},
                {"Key": "iit-billing-tag", "Value": str(future_timestamp)},
            ],
            launch_time=one_hour_ago,
            key_name="test-key",
            placement={"AvailabilityZone": "us-east-2a"},
        ),
        MockInstance(
            id="i-regular-timestamp-expired",
            tags=[
                {"Key": "Name", "Value": "expired-instance"},
                {"Key": "iit-billing-tag", "Value": str(past_timestamp)},
            ],
            launch_time=one_hour_ago,
            key_name="test-key",
            placement={"AvailabilityZone": "us-east-2b"},
        ),
        # EKS instances - protected cluster (pe-*)
        MockInstance(
            id="i-eks-pe-cluster-no-tag",
            tags=[
                {"Key": "Name", "Value": "pe-crossplane-node"},
                {"Key": "kubernetes.io/cluster/pe-crossplane", "Value": "owned"},
                {"Key": "eks:cluster-name", "Value": "pe-crossplane"},
            ],
            launch_time=one_hour_ago,
            key_name="eks-key",
            placement={"AvailabilityZone": "us-east-2a"},
        ),
        MockInstance(
            id="i-eks-pe-cluster-with-tag",
            tags=[
                {"Key": "Name", "Value": "pe-infra-node"},
                {"Key": "kubernetes.io/cluster/pe-infra", "Value": "owned"},
                {"Key": "iit-billing-tag", "Value": "platform-eng"},
            ],
            launch_time=one_hour_ago,
            key_name="eks-key",
            placement={"AvailabilityZone": "us-east-2b"},
        ),
        # EKS instances - non-protected cluster
        MockInstance(
            id="i-eks-pmm-no-tag",
            tags=[
                {"Key": "Name", "Value": "pmm-ha-node"},
                {"Key": "kubernetes.io/cluster/pmm-ha", "Value": "owned"},
                {"Key": "aws:eks:cluster-name", "Value": "pmm-ha"},
            ],
            launch_time=one_hour_ago,
            key_name="eks-key",
            placement={"AvailabilityZone": "us-east-2c"},
        ),
        MockInstance(
            id="i-eks-pmm-with-category-tag",
            tags=[
                {"Key": "Name", "Value": "pmm-test-node"},
                {"Key": "kubernetes.io/cluster/pmm-test", "Value": "owned"},
                {"Key": "iit-billing-tag", "Value": "pmm-eks"},
            ],
            launch_time=one_hour_ago,
            key_name="eks-key",
            placement={"AvailabilityZone": "us-east-2a"},
        ),
        MockInstance(
            id="i-eks-pmm-with-timestamp-valid",
            tags=[
                {"Key": "Name", "Value": "pmm-temp-node"},
                {"Key": "kubernetes.io/cluster/pmm-temp", "Value": "owned"},
                {"Key": "iit-billing-tag", "Value": str(future_timestamp)},
            ],
            launch_time=one_hour_ago,
            key_name="eks-key",
            placement={"AvailabilityZone": "us-east-2b"},
        ),
        MockInstance(
            id="i-eks-pmm-with-timestamp-expired",
            tags=[
                {"Key": "Name", "Value": "pmm-expired-node"},
                {"Key": "kubernetes.io/cluster/pmm-expired", "Value": "owned"},
                {"Key": "iit-billing-tag", "Value": str(past_timestamp)},
            ],
            launch_time=one_hour_ago,
            key_name="eks-key",
            placement={"AvailabilityZone": "us-east-2c"},
        ),
        # CirrusCI instance
        MockInstance(
            id="i-cirrus-ci",
            tags=[
                {"Key": "Name", "Value": "cirrus-runner"},
                {"Key": "CIRRUS_CI", "Value": "true"},
                {"Key": "iit-billing-tag", "Value": "CirrusCI"},
            ],
            launch_time=one_hour_ago,
            key_name="cirrus-key",
            placement={"AvailabilityZone": "us-east-2a"},
        ),
    ]

    return instances


def run_tests():
    """Run all test cases and display results"""
    print("=" * 80)
    print("Testing removeUntaggedEc2 Logic")
    print("=" * 80)
    print()

    instances = create_test_instances()
    region = "us-east-2"

    results = {
        "terminated": [],
        "skipped_eks": [],
        "skipped_has_tag": [],
        "skipped_grace_period": [],
        "eks_clusters_to_delete": set(),
    }

    for instance in instances:
        print(f"\n{'─' * 80}")
        print(f"Instance: {instance.id}")
        tags_dict = convert_tags_to_dict(instance.tags)
        print(f"Tags: {tags_dict}")
        print(f"Launch: {instance.launch_time.strftime('%Y-%m-%d %H:%M:%S UTC')}")

        # Check if EKS
        is_eks, eks_reason = is_eks_managed_instance(instance, region)

        if is_eks:
            print(f"✓ EKS Instance: {eks_reason}")
            results["skipped_eks"].append(instance.id)

            # Check if should mark cluster for deletion
            cluster_name = get_eks_cluster_name(tags_dict)
            if cluster_name and "marked for cluster deletion" in eks_reason:
                results["eks_clusters_to_delete"].add(cluster_name)
                print(f"  → Cluster '{cluster_name}' marked for deletion")
            continue

        # Check billing tag
        has_tag = has_valid_billing_tag(tags_dict, instance.launch_time)

        if has_tag:
            print(f"✓ Has valid billing tag: {tags_dict.get('iit-billing-tag')}")
            results["skipped_has_tag"].append(instance.id)
            continue

        # Check if should terminate
        should_terminate = is_instance_to_terminate(instance)

        if should_terminate:
            print("✗ TERMINATE (no tag, running > 10 minutes)")
            results["terminated"].append(instance.id)
        else:
            print("○ SKIP (grace period, running < 10 minutes)")
            results["skipped_grace_period"].append(instance.id)

    # Summary
    print("\n" + "=" * 80)
    print("SUMMARY")
    print("=" * 80)
    print(f"\nInstances to TERMINATE ({len(results['terminated'])}):")
    for instance_id in results["terminated"]:
        print(f"  - {instance_id}")

    print(f"\nInstances SKIPPED - EKS ({len(results['skipped_eks'])}):")
    for instance_id in results["skipped_eks"]:
        print(f"  - {instance_id}")

    print(f"\nInstances SKIPPED - Has billing tag ({len(results['skipped_has_tag'])}):")
    for instance_id in results["skipped_has_tag"]:
        print(f"  - {instance_id}")

    print(
        f"\nInstances SKIPPED - Grace period ({len(results['skipped_grace_period'])}):"
    )
    for instance_id in results["skipped_grace_period"]:
        print(f"  - {instance_id}")

    print(
        f"\nEKS Clusters marked for DELETION ({len(results['eks_clusters_to_delete'])}):"
    )
    for cluster in results["eks_clusters_to_delete"]:
        print(f"  - {cluster}")

    # Validation
    print("\n" + "=" * 80)
    print("VALIDATION")
    print("=" * 80)

    expected_terminated = ["i-regular-no-tag-old", "i-regular-timestamp-expired"]
    expected_eks_delete = ["pmm-ha", "pmm-expired"]

    terminated_match = set(results["terminated"]) == set(expected_terminated)
    eks_delete_match = results["eks_clusters_to_delete"] == set(expected_eks_delete)

    print(f"\n✓ Terminated instances correct: {terminated_match}")
    if not terminated_match:
        print(f"  Expected: {expected_terminated}")
        print(f"  Got: {results['terminated']}")

    print(f"✓ EKS clusters for deletion correct: {eks_delete_match}")
    if not eks_delete_match:
        print(f"  Expected: {expected_eks_delete}")
        print(f"  Got: {list(results['eks_clusters_to_delete'])}")

    if terminated_match and eks_delete_match:
        print("\n🎉 All validations PASSED!")
        return 0
    else:
        print("\n❌ Some validations FAILED!")
        return 1


if __name__ == "__main__":
    exit(run_tests())
