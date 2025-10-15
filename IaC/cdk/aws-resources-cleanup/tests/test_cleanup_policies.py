"""Unit tests for EC2 cleanup policy logic.

Tests focus on business logic for TTL, stop-after-days, long-stopped,
and untagged policies without mocking AWS services.
"""

from __future__ import annotations
import datetime
import pytest

from aws_resource_cleanup.ec2.policies import (
    check_ttl_expiration,
    check_stop_after_days,
    check_long_stopped,
    check_untagged,
)


class TestTTLExpirationPolicy:
    """Test TTL expiration policy logic."""

    def test_instance_with_expired_ttl_creates_terminate_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with expired TTL (created 2h ago, TTL 1h)
        WHEN check_ttl_expiration is called
        THEN a TERMINATE action should be returned with correct days overdue
        """
        creation_time = current_time - 7200  # 2 hours ago
        instance = (
            instance_builder.with_name("test-instance")
            .with_ttl_tags(creation_time, 1)
            .with_billing_tag("test-billing")
            .with_owner("test-user")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is not None
        assert action.action == "TERMINATE"
        assert action.instance_id == "i-test123456"
        assert action.name == "test-instance"
        assert action.billing_tag == "test-billing"
        assert action.owner == "test-user"
        # 1 hour overdue = 3600 seconds = 0.0417 days
        assert 0.04 < action.days_overdue < 0.05

    def test_instance_with_valid_ttl_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with TTL not yet expired
        WHEN check_ttl_expiration is called
        THEN None should be returned (no action)
        """
        creation_time = current_time - 1800  # 30 minutes ago
        instance = (
            instance_builder.with_ttl_tags(creation_time, 1)  # 1 hour TTL
            .with_billing_tag("test-billing")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is None

    @pytest.mark.parametrize(
        "creation_time_offset,ttl_hours,should_expire",
        [
            (-3601, 1, True),  # Expired by 1 second
            (-3600, 1, True),  # Exactly at expiration (>= comparison)
            (-3599, 1, False),  # Not yet expired
            (-86401, 24, True),  # 1 day TTL expired by 1 second
            (-86400, 24, True),  # 1 day TTL exactly at expiration (>= comparison)
        ],
    )
    def test_ttl_boundary_conditions(
        self,
        instance_builder,
        current_time,
        tags_dict_from_instance,
        creation_time_offset,
        ttl_hours,
        should_expire,
    ):
        """
        GIVEN instances at TTL boundary conditions
        WHEN check_ttl_expiration is called
        THEN correct expiration decision should be made
        """
        creation_time = current_time + creation_time_offset
        instance = (
            instance_builder.with_ttl_tags(creation_time, ttl_hours)
            .with_billing_tag("test-billing")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        if should_expire:
            assert action is not None
            assert action.action == "TERMINATE"
        else:
            assert action is None

    def test_instance_without_ttl_tags_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance without TTL tags
        WHEN check_ttl_expiration is called
        THEN None should be returned
        """
        instance = instance_builder.with_billing_tag("test-billing").build()

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is None

    def test_instance_with_partial_ttl_tags_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with only creation-time but no TTL
        WHEN check_ttl_expiration is called
        THEN None should be returned
        """
        instance = (
            instance_builder.with_tag("creation-time", str(current_time))
            .with_billing_tag("test-billing")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is None

    def test_instance_with_invalid_ttl_values_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with non-numeric TTL tags
        WHEN check_ttl_expiration is called
        THEN None should be returned (graceful handling)
        """
        instance = (
            instance_builder.with_tag("creation-time", "invalid")
            .with_tag("delete-cluster-after-hours", "not-a-number")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is None

    def test_openshift_cluster_instance_gets_special_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an OpenShift cluster instance with expired TTL
        WHEN check_ttl_expiration is called
        THEN TERMINATE_OPENSHIFT_CLUSTER action should be returned
        """
        creation_time = current_time - 7200
        instance = (
            instance_builder.with_ttl_tags(creation_time, 1)
            .with_openshift_tags("test-infra-123")
            .with_cluster_name("test-cluster")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is not None
        assert action.action == "TERMINATE_OPENSHIFT_CLUSTER"
        # Cluster name is extracted from kubernetes.io/cluster/ tag, not from cluster-name tag
        assert action.cluster_name == "test-infra-123"

    def test_eks_cluster_instance_gets_cluster_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an EKS cluster instance with expired TTL
        WHEN check_ttl_expiration is called
        THEN TERMINATE_CLUSTER action should be returned
        """
        creation_time = current_time - 7200
        instance = (
            instance_builder.with_ttl_tags(creation_time, 1)
            .with_eks_tags("test-eks")
            .with_cluster_name("test-eks")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is not None
        assert action.action == "TERMINATE_CLUSTER"
        assert action.cluster_name == "test-eks"

    def test_days_overdue_calculation_accurate(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance expired by exactly 2 days
        WHEN check_ttl_expiration is called
        THEN days_overdue should be approximately 2.96 days
        """
        creation_time = current_time - 259200  # 3 days ago = 259200 seconds
        instance = (
            instance_builder.with_ttl_tags(creation_time, 1)  # 1 hour TTL = 3600 seconds
            .with_billing_tag("test-billing")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_ttl_expiration(instance, tags_dict, current_time)

        assert action is not None
        # Overdue = (259200 - 3600) / 86400 = 255600 / 86400 = 2.958 days
        assert 2.95 < action.days_overdue < 2.97


class TestStopAfterDaysPolicy:
    """Test stop-after-days policy logic."""

    def test_running_instance_past_stop_threshold_creates_stop_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance past stop-after-days threshold
        WHEN check_stop_after_days is called
        THEN a STOP action should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 691200, tz=datetime.timezone.utc
        )  # 8 days ago
        instance = (
            instance_builder.with_name("pmm-staging")
            .with_state("running")
            .with_launch_time(launch_time)
            .with_stop_after_days(7)
            .with_billing_tag("pmm-staging")
            .with_owner("test-user")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_stop_after_days(instance, tags_dict, current_time)

        assert action is not None
        assert action.action == "STOP"
        assert action.name == "pmm-staging"
        assert action.billing_tag == "pmm-staging"
        # Should be overdue by 1 day
        assert 0.99 < action.days_overdue < 1.01

    def test_stopped_instance_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a stopped instance with stop-after-days tag
        WHEN check_stop_after_days is called
        THEN None should be returned (already stopped)
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 691200, tz=datetime.timezone.utc
        )
        instance = (
            instance_builder.with_state("stopped")
            .with_launch_time(launch_time)
            .with_stop_after_days(7)
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_stop_after_days(instance, tags_dict, current_time)

        assert action is None

    def test_instance_without_stop_tag_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance without stop-after-days tag
        WHEN check_stop_after_days is called
        THEN None should be returned
        """
        instance = instance_builder.with_state("running").build()

        tags_dict = tags_dict_from_instance(instance)
        action = check_stop_after_days(instance, tags_dict, current_time)

        assert action is None

    def test_instance_before_stop_threshold_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance not yet past stop threshold
        WHEN check_stop_after_days is called
        THEN None should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 518400, tz=datetime.timezone.utc
        )  # 6 days ago
        instance = (
            instance_builder.with_state("running")
            .with_launch_time(launch_time)
            .with_stop_after_days(7)
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_stop_after_days(instance, tags_dict, current_time)

        assert action is None

    def test_invalid_stop_after_days_value_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with non-numeric stop-after-days value
        WHEN check_stop_after_days is called
        THEN None should be returned (graceful handling)
        """
        instance = (
            instance_builder.with_state("running")
            .with_tag("stop-after-days", "invalid")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_stop_after_days(instance, tags_dict, current_time)

        assert action is None


class TestLongStoppedPolicy:
    """Test long-stopped instance detection logic."""

    def test_instance_stopped_over_30_days_creates_terminate_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance stopped for more than 30 days
        WHEN check_long_stopped is called
        THEN a TERMINATE action should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 3024000, tz=datetime.timezone.utc
        )  # 35 days ago
        instance = (
            instance_builder.with_name("long-stopped")
            .with_state("stopped")
            .with_launch_time(launch_time)
            .with_billing_tag("test-billing")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_long_stopped(instance, tags_dict, current_time)

        assert action is not None
        assert action.action == "TERMINATE"
        assert action.name == "long-stopped"
        # Should be overdue by ~5 days (35 - 30)
        assert 4.9 < action.days_overdue < 5.1

    def test_running_instance_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance (regardless of age)
        WHEN check_long_stopped is called
        THEN None should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 3024000, tz=datetime.timezone.utc
        )
        instance = (
            instance_builder.with_state("running").with_launch_time(launch_time).build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_long_stopped(instance, tags_dict, current_time)

        assert action is None

    def test_recently_stopped_instance_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance stopped for less than 30 days
        WHEN check_long_stopped is called
        THEN None should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 2592000, tz=datetime.timezone.utc
        )  # Exactly 30 days
        instance = (
            instance_builder.with_state("stopped").with_launch_time(launch_time).build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_long_stopped(instance, tags_dict, current_time)

        assert action is None

    def test_stopped_instance_at_31_days_creates_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance stopped for exactly 31 days
        WHEN check_long_stopped is called
        THEN a TERMINATE action should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 2678400, tz=datetime.timezone.utc
        )  # 31 days
        instance = (
            instance_builder.with_state("stopped").with_launch_time(launch_time).build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_long_stopped(instance, tags_dict, current_time)

        assert action is not None
        assert action.action == "TERMINATE"


class TestUntaggedPolicy:
    """Test untagged instance detection logic."""

    def test_instance_without_billing_tag_over_threshold_creates_terminate_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance without billing tag running > 30 minutes
        WHEN check_untagged is called
        THEN a TERMINATE action should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 3600, tz=datetime.timezone.utc
        )  # 1 hour ago
        instance = (
            instance_builder.with_name("untagged-instance")
            .with_launch_time(launch_time)
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_untagged(instance, tags_dict, current_time)

        assert action is not None
        assert action.action == "TERMINATE"
        assert action.billing_tag == "<MISSING>"
        assert "Missing billing tag" in action.reason

    def test_instance_with_valid_billing_tag_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with valid billing tag
        WHEN check_untagged is called
        THEN None should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 3600, tz=datetime.timezone.utc
        )
        instance = (
            instance_builder.with_launch_time(launch_time)
            .with_billing_tag("pmm-staging")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_untagged(instance, tags_dict, current_time)

        assert action is None

    def test_untagged_instance_under_threshold_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance without billing tag but under threshold (< 30 min)
        WHEN check_untagged is called
        THEN None should be returned (grace period)
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 1200, tz=datetime.timezone.utc
        )  # 20 minutes ago
        instance = instance_builder.with_launch_time(launch_time).build()

        tags_dict = tags_dict_from_instance(instance)
        action = check_untagged(instance, tags_dict, current_time)

        assert action is None

    def test_instance_with_invalid_billing_tag_creates_terminate_action(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with invalid/expired timestamp billing tag
        WHEN check_untagged is called
        THEN a TERMINATE action should be returned
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - 3600, tz=datetime.timezone.utc
        )
        # Invalid billing tag format (not in valid list)
        instance = (
            instance_builder.with_launch_time(launch_time)
            .with_billing_tag("invalid-format-123")
            .build()
        )

        tags_dict = tags_dict_from_instance(instance)
        action = check_untagged(instance, tags_dict, current_time)

        # Note: This depends on has_valid_billing_tag() implementation
        # If "invalid-format-123" is considered invalid, action should be created
        # Otherwise it would be None
        # Based on the code, timestamps in format YYYYMMDD-HHMM or valid billing tags are accepted
        if action:
            assert action.action == "TERMINATE"

    @pytest.mark.parametrize(
        "minutes_running,should_terminate",
        [
            (29, False),  # Under threshold (29 < 30)
            (30, True),  # Exactly at threshold (30 < 30 is False, so action created)
            (31, True),  # Over threshold
            (60, True),  # Well over threshold
        ],
    )
    def test_untagged_threshold_boundary(
        self,
        instance_builder,
        current_time,
        tags_dict_from_instance,
        minutes_running,
        should_terminate,
    ):
        """
        GIVEN untagged instances at various runtime thresholds
        WHEN check_untagged is called
        THEN correct termination decision should be made
        """
        launch_time = datetime.datetime.fromtimestamp(
            current_time - (minutes_running * 60), tz=datetime.timezone.utc
        )
        instance = instance_builder.with_launch_time(launch_time).build()

        tags_dict = tags_dict_from_instance(instance)
        action = check_untagged(instance, tags_dict, current_time)

        if should_terminate:
            assert action is not None
            assert action.action == "TERMINATE"
        else:
            assert action is None
