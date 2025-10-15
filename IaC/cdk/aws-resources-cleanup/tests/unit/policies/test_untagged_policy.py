"""Unit tests for untagged instance detection logic.

Tests the check_untagged() policy function.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.ec2.policies import check_untagged


@pytest.mark.unit
@pytest.mark.policies
class TestUntaggedPolicy:
    """Test untagged instance detection logic."""
    
    def test_instance_without_billing_tag_over_threshold_creates_terminate_action(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance without billing tag running > 30 minutes
        WHEN check_untagged is called
        THEN a TERMINATE action should be returned
        """
        instance = make_instance(
            name="untagged-instance",
            hours_old=1
            # No billing tag
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_untagged(instance, tags_dict, current_time)
        
        assert action is not None
        assert action.action == "TERMINATE"
        assert action.billing_tag == "<MISSING>"
        assert "Missing billing tag" in action.reason

    def test_instance_with_valid_billing_tag_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with valid billing tag
        WHEN check_untagged is called
        THEN None should be returned
        """
        instance = make_instance(
            hours_old=1,
            billing_tag="pmm-staging"
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_untagged(instance, tags_dict, current_time)
        
        assert action is None

    def test_untagged_instance_under_threshold_returns_none(
        self, instance_builder, current_time, tags_dict_from_instance, time_utils
    ):
        """
        GIVEN an instance without billing tag but under threshold (< 30 min)
        WHEN check_untagged is called
        THEN None should be returned (grace period)
        """
        # Create instance with recent launch time
        instance = (
            instance_builder
            .with_launch_time(time_utils.seconds_ago(1200))  # 20 minutes ago
            .build()
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_untagged(instance, tags_dict, current_time)
        
        assert action is None

    @pytest.mark.parametrize(
        "minutes_running,should_terminate",
        [
            (29, False),  # Under threshold (29 < 30)
            (30, True),   # At threshold
            (31, True),   # Over threshold
            (60, True),   # Well over threshold
        ],
    )
    def test_untagged_threshold_boundary(
        self,
        instance_builder,
        current_time,
        tags_dict_from_instance,
        time_utils,
        minutes_running,
        should_terminate,
    ):
        """
        GIVEN untagged instances at various runtime thresholds
        WHEN check_untagged is called
        THEN correct termination decision should be made
        """
        instance = (
            instance_builder
            .with_launch_time(time_utils.seconds_ago(minutes_running * 60))
            .build()
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_untagged(instance, tags_dict, current_time)
        
        if should_terminate:
            assert action is not None
            assert action.action == "TERMINATE"
        else:
            assert action is None