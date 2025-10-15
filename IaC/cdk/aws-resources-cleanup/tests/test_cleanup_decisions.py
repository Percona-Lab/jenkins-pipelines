"""Unit tests for cleanup decision logic, protection, and priority rules.

Tests focus on business logic for protection detection, cluster identification,
and policy priority without mocking AWS services.
"""

from __future__ import annotations
import datetime
import pytest

from aws_resource_cleanup.ec2.instances import is_protected
from aws_resource_cleanup.utils import (
    has_valid_billing_tag,
    extract_cluster_name,
    convert_tags_to_dict,
)


class TestProtectionLogic:
    """Test resource protection detection."""

    def test_instance_with_persistent_billing_tag_is_protected(
        self, instance_builder, tags_dict_from_instance
    ):
        """
        GIVEN an instance with a persistent billing tag
        WHEN is_protected is called
        THEN True should be returned (instance is protected)
        """
        instance = (
            instance_builder.with_billing_tag("jenkins-dev-pmm")
            .with_name("protected")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        assert is_protected(tags_dict) is True

    @pytest.mark.parametrize(
        "persistent_tag",
        [
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
        ],
    )
    def test_all_persistent_tags_are_protected(
        self, instance_builder, tags_dict_from_instance, persistent_tag
    ):
        """
        GIVEN an instance with any persistent billing tag
        WHEN is_protected is called
        THEN True should be returned
        """
        instance = instance_builder.with_billing_tag(persistent_tag).build()

        tags_dict = tags_dict_from_instance(instance)
        assert is_protected(tags_dict) is True

    def test_instance_with_valid_billing_tag_is_protected(
        self, instance_builder, tags_dict_from_instance
    ):
        """
        GIVEN an instance with valid non-persistent billing tag
        WHEN is_protected is called
        THEN True should be returned (protected unless TTL overrides)
        """
        instance = instance_builder.with_billing_tag("pmm-staging").build()

        tags_dict = tags_dict_from_instance(instance)
        assert is_protected(tags_dict) is True

    def test_instance_with_billing_tag_and_ttl_is_not_protected(
        self, instance_builder, tags_dict_from_instance, current_time
    ):
        """
        GIVEN an instance with billing tag but also TTL tags
        WHEN is_protected is called
        THEN False should be returned (TTL takes precedence)
        """
        instance = (
            instance_builder.with_billing_tag("pmm-staging")
            .with_ttl_tags(current_time, 1)
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        assert is_protected(tags_dict) is False

    def test_instance_with_billing_tag_and_stop_policy_not_protected(
        self, instance_builder, tags_dict_from_instance
    ):
        """
        GIVEN an instance with billing tag but also stop-after-days
        WHEN is_protected is called
        THEN False should be returned (stop policy takes precedence)
        """
        instance = (
            instance_builder.with_billing_tag("pmm-staging")
            .with_stop_after_days(7)
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        assert is_protected(tags_dict) is False

    def test_untagged_instance_is_not_protected(
        self, instance_builder, tags_dict_from_instance
    ):
        """
        GIVEN an instance without any billing tag
        WHEN is_protected is called
        THEN False should be returned
        """
        instance = instance_builder.with_name("untagged").build()

        tags_dict = tags_dict_from_instance(instance)
        assert is_protected(tags_dict) is False

    def test_instance_with_invalid_billing_tag_not_protected(
        self, instance_builder, tags_dict_from_instance
    ):
        """
        GIVEN an instance with expired timestamp billing tag
        WHEN is_protected is called
        THEN False should be returned
        """
        # Expired timestamp (past date)
        expired_timestamp = "1000000"  # Very old timestamp
        instance = instance_builder.with_billing_tag(expired_timestamp).build()

        tags_dict = tags_dict_from_instance(instance)
        # Note: is_protected uses has_valid_billing_tag which checks timestamps
        # An expired timestamp should not protect the instance
        result = is_protected(tags_dict)
        assert result is False


class TestBillingTagValidation:
    """Test billing tag validation logic."""

    def test_valid_category_billing_tag_accepted(self):
        """
        GIVEN a category-based billing tag (e.g., "pmm-staging")
        WHEN has_valid_billing_tag is called
        THEN True should be returned
        """
        tags_dict = {"iit-billing-tag": "pmm-staging"}
        assert has_valid_billing_tag(tags_dict) is True

    def test_future_timestamp_billing_tag_accepted(self):
        """
        GIVEN a future Unix timestamp billing tag
        WHEN has_valid_billing_tag is called
        THEN True should be returned
        """
        # Future timestamp (year 2030)
        future_timestamp = str(int(datetime.datetime(2030, 1, 1).timestamp()))
        tags_dict = {"iit-billing-tag": future_timestamp}
        assert has_valid_billing_tag(tags_dict) is True

    def test_expired_timestamp_billing_tag_rejected(self):
        """
        GIVEN an expired Unix timestamp billing tag
        WHEN has_valid_billing_tag is called
        THEN False should be returned
        """
        # Past timestamp (year 2020)
        past_timestamp = str(int(datetime.datetime(2020, 1, 1).timestamp()))
        tags_dict = {"iit-billing-tag": past_timestamp}
        assert has_valid_billing_tag(tags_dict) is False

    def test_missing_billing_tag_rejected(self):
        """
        GIVEN tags without iit-billing-tag
        WHEN has_valid_billing_tag is called
        THEN False should be returned
        """
        tags_dict = {"Name": "test-instance"}
        assert has_valid_billing_tag(tags_dict) is False

    def test_empty_billing_tag_rejected(self):
        """
        GIVEN an empty billing tag value
        WHEN has_valid_billing_tag is called
        THEN False should be returned
        """
        tags_dict = {"iit-billing-tag": ""}
        assert has_valid_billing_tag(tags_dict) is False

    @pytest.mark.parametrize(
        "billing_tag",
        [
            "pmm-staging",
            "CirrusCI",
            "eks",
            "openshift",
            "test-team",
            "custom-123",
        ],
    )
    def test_various_category_tags_accepted(self, billing_tag):
        """
        GIVEN various category-based billing tags
        WHEN has_valid_billing_tag is called
        THEN True should be returned for all
        """
        tags_dict = {"iit-billing-tag": billing_tag}
        assert has_valid_billing_tag(tags_dict) is True


class TestClusterNameExtraction:
    """Test cluster name extraction from tags."""

    def test_extract_kubernetes_cluster_name_from_tag(self):
        """
        GIVEN tags with kubernetes.io/cluster/<name> tag
        WHEN extract_cluster_name is called
        THEN cluster name should be extracted
        """
        tags_dict = {"kubernetes.io/cluster/test-eks-cluster": "owned"}
        cluster_name = extract_cluster_name(tags_dict)
        assert cluster_name == "test-eks-cluster"

    def test_extract_eks_cluster_name_from_aws_tag(self):
        """
        GIVEN tags with aws:eks:cluster-name tag
        WHEN extract_cluster_name is called
        THEN cluster name should be extracted
        """
        tags_dict = {"aws:eks:cluster-name": "my-eks-cluster"}
        cluster_name = extract_cluster_name(tags_dict)
        assert cluster_name == "my-eks-cluster"

    def test_extract_openshift_cluster_name(self):
        """
        GIVEN tags with OpenShift kubernetes tag
        WHEN extract_cluster_name is called
        THEN infra ID should be extracted as cluster name
        """
        tags_dict = {"kubernetes.io/cluster/openshift-infra-abc123": "owned"}
        cluster_name = extract_cluster_name(tags_dict)
        assert cluster_name == "openshift-infra-abc123"

    def test_no_cluster_name_returns_none(self):
        """
        GIVEN tags without cluster identifiers
        WHEN extract_cluster_name is called
        THEN None should be returned
        """
        tags_dict = {"Name": "standalone-instance", "iit-billing-tag": "test"}
        cluster_name = extract_cluster_name(tags_dict)
        assert cluster_name is None

    def test_kubernetes_tag_takes_precedence_over_aws_tag(self):
        """
        GIVEN tags with both kubernetes.io and aws:eks tags
        WHEN extract_cluster_name is called
        THEN kubernetes.io cluster name should be returned
        """
        tags_dict = {
            "kubernetes.io/cluster/k8s-cluster": "owned",
            "aws:eks:cluster-name": "eks-cluster",
        }
        cluster_name = extract_cluster_name(tags_dict)
        # kubernetes.io tag is checked first in the function
        assert cluster_name == "k8s-cluster"


class TestTagConversion:
    """Test tag list to dictionary conversion."""

    def test_convert_tags_list_to_dict(self):
        """
        GIVEN AWS tags list format
        WHEN convert_tags_to_dict is called
        THEN dictionary should be returned
        """
        tags_list = [
            {"Key": "Name", "Value": "test-instance"},
            {"Key": "iit-billing-tag", "Value": "pmm-staging"},
            {"Key": "owner", "Value": "test-user"},
        ]

        tags_dict = convert_tags_to_dict(tags_list)

        assert tags_dict == {
            "Name": "test-instance",
            "iit-billing-tag": "pmm-staging",
            "owner": "test-user",
        }

    def test_convert_empty_tags_list(self):
        """
        GIVEN empty tags list
        WHEN convert_tags_to_dict is called
        THEN empty dictionary should be returned
        """
        tags_dict = convert_tags_to_dict([])
        assert tags_dict == {}

    def test_convert_none_tags(self):
        """
        GIVEN None as tags
        WHEN convert_tags_to_dict is called
        THEN empty dictionary should be returned
        """
        tags_dict = convert_tags_to_dict(None)
        assert tags_dict == {}


class TestPolicyPriority:
    """Test policy evaluation priority and decision logic."""

    def test_ttl_policy_should_take_priority_over_untagged(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with expired TTL but no billing tag
        WHEN policies are evaluated in order
        THEN TTL policy should create action before untagged policy
        """
        from aws_resource_cleanup.ec2.policies import (
            check_ttl_expiration,
            check_untagged,
        )

        creation_time = current_time - 7200  # 2 hours ago
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 7200, tz=datetime.timezone.utc
        )

        instance = (
            instance_builder.with_ttl_tags(creation_time, 1)
            .with_launch_time(launch_time)
            .build()
        )  # No billing tag

        tags_dict = tags_dict_from_instance(instance)

        # TTL should be checked first
        ttl_action = check_ttl_expiration(instance, tags_dict, current_time)
        assert ttl_action is not None
        assert ttl_action.action in ["TERMINATE", "TERMINATE_CLUSTER"]

        # Untagged would also match but shouldn't be reached
        untagged_action = check_untagged(instance, tags_dict, current_time)
        assert untagged_action is not None  # Would also create action

    def test_stop_policy_checked_before_long_stopped(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance with stop-after-days policy
        WHEN policies are evaluated
        THEN stop-after-days should trigger before long-stopped
        """
        from aws_resource_cleanup.ec2.policies import (
            check_stop_after_days,
            check_long_stopped,
        )

        # Instance running for 8 days
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 691200, tz=datetime.timezone.utc
        )
        instance = (
            instance_builder.with_state("running")
            .with_launch_time(launch_time)
            .with_stop_after_days(7)
            .with_billing_tag("pmm-staging")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)

        # Stop policy should match
        stop_action = check_stop_after_days(instance, tags_dict, current_time)
        assert stop_action is not None
        assert stop_action.action == "STOP"

        # Long-stopped wouldn't match (instance is running)
        long_stopped_action = check_long_stopped(instance, tags_dict, current_time)
        assert long_stopped_action is None

    def test_protected_instance_skipped_by_all_policies(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a protected instance (persistent billing tag)
        WHEN is_protected is checked
        THEN it should return True (handler would skip policy checks)
        """
        # Protected instance with persistent tag
        instance = (
            instance_builder.with_billing_tag("jenkins-dev-pmm")
            .with_name("protected-instance")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)

        # Should be protected
        assert is_protected(tags_dict) is True

        # In the handler, protected instances are skipped before policy checks
        # So policies wouldn't even be evaluated

    def test_instance_with_multiple_matching_policies(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance matching multiple policies (stopped, old, untagged)
        WHEN policies are evaluated in order
        THEN first matching policy should determine the action
        """
        from aws_resource_cleanup.ec2.policies import (
            check_ttl_expiration,
            check_stop_after_days,
            check_long_stopped,
            check_untagged,
        )

        # Instance: stopped for 35 days, no billing tag
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 3024000, tz=datetime.timezone.utc
        )
        instance = (
            instance_builder.with_state("stopped")
            .with_launch_time(launch_time)
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)

        # TTL doesn't apply (no TTL tags)
        ttl_action = check_ttl_expiration(instance, tags_dict, current_time)
        assert ttl_action is None

        # Stop policy doesn't apply (already stopped)
        stop_action = check_stop_after_days(instance, tags_dict, current_time)
        assert stop_action is None

        # Long-stopped applies (stopped > 30 days)
        long_stopped_action = check_long_stopped(instance, tags_dict, current_time)
        assert long_stopped_action is not None
        assert long_stopped_action.action == "TERMINATE"

        # Untagged also applies but comes after long-stopped in priority
        untagged_action = check_untagged(instance, tags_dict, current_time)
        assert untagged_action is not None

        # In handler, long-stopped would be used (checked first in the or chain)
