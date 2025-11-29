"""Unit tests for policy evaluation priority and decision logic.

Tests the priority ordering of cleanup policies.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.ec2.policies import (
    check_ttl_expiration,
    check_stop_after_days,
    check_long_stopped,
    check_untagged,
)
from aws_resource_cleanup.ec2.instances import is_protected


@pytest.mark.unit
@pytest.mark.policies
class TestPolicyPriority:
    """Test policy evaluation priority and decision logic."""
    
    def test_ttl_policy_should_take_priority_over_untagged(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with expired TTL but no billing tag
        WHEN policies are evaluated in order
        THEN TTL policy should create action before untagged policy
        """
        instance = make_instance(
            ttl_expired=True,
            hours_old=2,
            ttl_hours=1
            # No billing tag
        )
        tags_dict = tags_dict_from_instance(instance)
        
        # TTL should be checked first
        ttl_action = check_ttl_expiration(instance, tags_dict, current_time)
        assert ttl_action is not None
        assert ttl_action.action in ["TERMINATE", "TERMINATE_CLUSTER"]
        
        # Untagged would also match but shouldn't be reached
        untagged_action = check_untagged(instance, tags_dict, current_time)
        assert untagged_action is not None  # Would also create action

    def test_stop_policy_checked_before_long_stopped(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a running instance with stop-after-days policy
        WHEN policies are evaluated
        THEN stop-after-days should trigger before long-stopped
        """
        # Instance running for 8 days
        instance = make_instance(
            state="running",
            days_old=8,
            stop_after_days=7,
            billing_tag="pmm-staging"
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
        self, make_instance, tags_dict_from_instance
    ):
        """
        GIVEN a protected instance (persistent billing tag)
        WHEN is_protected is checked
        THEN it should return True (handler would skip policy checks)
        """
        # Protected instance with persistent tag
        instance = make_instance(
            name="protected-instance",
            protected=True
        )
        tags_dict = tags_dict_from_instance(instance)

        # Should be protected (is_protected returns tuple: (bool, str))
        is_protected_flag, reason = is_protected(tags_dict)
        assert is_protected_flag is True
        assert reason != ""  # Should have a protection reason

        # In the handler, protected instances are skipped before policy checks
        # So policies wouldn't even be evaluated

    def test_instance_with_multiple_matching_policies(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance matching multiple policies (stopped, old, untagged)
        WHEN policies are evaluated in order
        THEN first matching policy should determine the action
        """
        # Instance: stopped for 35 days, no billing tag
        instance = make_instance(
            state="stopped",
            days_old=35
            # No billing tag
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