"""Unit tests for long-stopped instance detection logic.

Tests the check_long_stopped() policy function.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.ec2.policies import check_long_stopped


@pytest.mark.unit
@pytest.mark.policies
class TestLongStoppedPolicy:
    """Test long-stopped instance detection logic."""
    
    def test_instance_stopped_over_30_days_creates_terminate_action(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance stopped for more than 30 days
        WHEN check_long_stopped is called
        THEN a TERMINATE action should be returned
        """
        instance = make_instance(
            name="long-stopped",
            state="stopped",
            days_old=35,
            billing_tag="test-billing"
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_long_stopped(instance, tags_dict, current_time)
        
        assert action is not None
        assert action.action == "TERMINATE"
        assert action.name == "long-stopped"
        # Should be overdue by ~5 days (35 - 30)
        assert 4.9 < action.days_overdue < 5.1

    def test_running_instance_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance (regardless of age)
        WHEN check_long_stopped is called
        THEN None should be returned
        """
        instance = make_instance(
            state="running",
            days_old=35
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_long_stopped(instance, tags_dict, current_time)
        
        assert action is None

    def test_recently_stopped_instance_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance stopped for less than 30 days
        WHEN check_long_stopped is called
        THEN None should be returned
        """
        instance = make_instance(
            state="stopped",
            days_old=30  # Exactly 30 days
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_long_stopped(instance, tags_dict, current_time)
        
        assert action is None

    def test_stopped_instance_at_31_days_creates_action(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance stopped for exactly 31 days
        WHEN check_long_stopped is called
        THEN a TERMINATE action should be returned
        """
        instance = make_instance(
            state="stopped",
            days_old=31
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_long_stopped(instance, tags_dict, current_time)
        
        assert action is not None
        assert action.action == "TERMINATE"