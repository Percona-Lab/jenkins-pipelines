"""Unit tests for stop-after-days policy logic.

Tests the check_stop_after_days() policy function.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.ec2.policies import check_stop_after_days


@pytest.mark.unit
@pytest.mark.policies
class TestStopAfterDaysPolicy:
    """Test stop-after-days policy logic."""
    
    def test_running_instance_past_stop_threshold_creates_stop_action(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance past stop-after-days threshold
        WHEN check_stop_after_days is called
        THEN a STOP action should be returned
        """
        instance = make_instance(
            name="pmm-staging",
            state="running",
            days_old=8,
            stop_after_days=7,
            billing_tag="pmm-staging",
            owner="test-user"
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
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a stopped instance with stop-after-days tag
        WHEN check_stop_after_days is called
        THEN None should be returned (already stopped)
        """
        instance = make_instance(
            state="stopped",
            days_old=8,
            stop_after_days=7
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_stop_after_days(instance, tags_dict, current_time)
        
        assert action is None

    def test_instance_without_stop_tag_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance without stop-after-days tag
        WHEN check_stop_after_days is called
        THEN None should be returned
        """
        instance = make_instance(state="running")
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_stop_after_days(instance, tags_dict, current_time)
        
        assert action is None

    def test_instance_before_stop_threshold_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance not yet past stop threshold
        WHEN check_stop_after_days is called
        THEN None should be returned
        """
        instance = make_instance(
            state="running",
            days_old=6,
            stop_after_days=7
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_stop_after_days(instance, tags_dict, current_time)
        
        assert action is None

    def test_invalid_stop_after_days_value_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with non-numeric stop-after-days value
        WHEN check_stop_after_days is called
        THEN None should be returned (graceful handling)
        """
        instance = make_instance(
            state="running",
            **{"stop-after-days": "invalid"}
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_stop_after_days(instance, tags_dict, current_time)
        
        assert action is None