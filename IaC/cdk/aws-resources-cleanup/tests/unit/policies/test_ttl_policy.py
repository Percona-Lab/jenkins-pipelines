"""Unit tests for TTL expiration policy logic.

Tests the check_ttl_expiration() policy function.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.ec2.policies import check_ttl_expiration


@pytest.mark.unit
@pytest.mark.policies
class TestTTLExpirationDetection:
    """Test TTL expiration detection logic."""
    
    def test_instance_with_expired_ttl_creates_terminate_action(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with expired TTL (created 2h ago, TTL 1h)
        WHEN check_ttl_expiration is called
        THEN a TERMINATE action should be returned with correct days overdue
        """
        instance = make_instance(
            name="test-instance",
            ttl_expired=True,
            hours_old=2,
            ttl_hours=1,
            billing_tag="test-billing",
            owner="test-user"
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
        # Manually add TTL tags for valid (non-expired) TTL
        # Use instance_builder directly from fixture
        creation_time = current_time - 1800  # 30 minutes ago
        instance = (
            instance_builder
            .with_ttl_tags(creation_time, 1)  # 1 hour TTL
            .with_billing_tag("test-billing")
            .build()
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_ttl_expiration(instance, tags_dict, current_time)
        
        assert action is None

    def test_instance_without_ttl_tags_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance without TTL tags
        WHEN check_ttl_expiration is called
        THEN None should be returned
        """
        instance = make_instance(billing_tag="test-billing")
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_ttl_expiration(instance, tags_dict, current_time)
        
        assert action is None

    def test_instance_with_partial_ttl_tags_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with only creation-time but no TTL
        WHEN check_ttl_expiration is called
        THEN None should be returned
        """
        instance = make_instance(
            billing_tag="test-billing",
            **{"creation-time": str(current_time)}
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_ttl_expiration(instance, tags_dict, current_time)
        
        assert action is None

    def test_instance_with_invalid_ttl_values_returns_none(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance with non-numeric TTL tags
        WHEN check_ttl_expiration is called
        THEN None should be returned (graceful handling)
        """
        instance = make_instance(
            **{
                "creation-time": "invalid",
                "delete-cluster-after-hours": "not-a-number"
            }
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_ttl_expiration(instance, tags_dict, current_time)
        
        assert action is None

    def test_days_overdue_calculation_accurate(
        self, instance_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an instance expired by exactly 3 days minus 1 hour
        WHEN check_ttl_expiration is called
        THEN days_overdue should be approximately 2.96 days
        """
        # Created 3 days ago with 1 hour TTL = overdue by 2.958 days
        creation_time = current_time - 259200  # 3 days ago
        instance = (
            instance_builder
            .with_ttl_tags(creation_time, 1)  # 1 hour TTL
            .with_billing_tag("test-billing")
            .build()
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_ttl_expiration(instance, tags_dict, current_time)
        
        assert action is not None
        # Overdue = (3 days - 1 hour) = (259200 - 3600) / 86400 = 2.958 days
        assert 2.95 < action.days_overdue < 2.97


@pytest.mark.unit
@pytest.mark.policies
class TestTTLBoundaryConditions:
    """Test TTL expiration at exact boundaries."""
    
    @pytest.mark.parametrize(
        "hours_offset,ttl_hours,should_expire",
        [
            (1.001, 1, True),   # Expired by ~1 second
            (1.0, 1, True),     # Exactly at expiration (>= comparison)
            (0.999, 1, False),  # Not yet expired
            (24.001, 24, True), # 1 day TTL expired by ~1 second
            (24.0, 24, True),   # 1 day TTL exactly at expiration
        ],
    )
    def test_ttl_boundary_conditions(
        self,
        instance_builder,
        current_time,
        tags_dict_from_instance,
        hours_offset,
        ttl_hours,
        should_expire,
    ):
        """
        GIVEN instances at TTL boundary conditions
        WHEN check_ttl_expiration is called
        THEN correct expiration decision should be made
        """
        if should_expire:
            creation_time = current_time - int(hours_offset * 3600)
        else:
            # Create with valid TTL (not yet expired)
            creation_time = current_time - int(hours_offset * 3600) + 100
        
        instance = (
            instance_builder
            .with_ttl_tags(creation_time, ttl_hours)
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


@pytest.mark.unit
@pytest.mark.policies
@pytest.mark.openshift
class TestTTLClusterHandling:
    """Test TTL policy for clustered resources."""
    
    def test_openshift_cluster_instance_gets_special_action(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an OpenShift cluster instance with expired TTL
        WHEN check_ttl_expiration is called
        THEN TERMINATE_OPENSHIFT_CLUSTER action should be returned
        """
        instance = make_instance(
            ttl_expired=True,
            hours_old=2,
            ttl_hours=1,
            openshift=True,
            infra_id="test-infra-123",
            cluster_name="test-cluster"
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_ttl_expiration(instance, tags_dict, current_time)
        
        assert action is not None
        assert action.action == "TERMINATE_OPENSHIFT_CLUSTER"
        # Cluster name is extracted from kubernetes.io/cluster/ tag, not from cluster-name tag
        assert action.cluster_name == "test-infra-123"

    def test_eks_cluster_instance_gets_cluster_action(
        self, make_instance, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an EKS cluster instance with expired TTL
        WHEN check_ttl_expiration is called
        THEN TERMINATE_CLUSTER action should be returned
        """
        instance = make_instance(
            ttl_expired=True,
            hours_old=2,
            ttl_hours=1,
            eks=True,
            eks_cluster="test-eks",
            cluster_name="test-eks"
        )
        tags_dict = tags_dict_from_instance(instance)
        
        action = check_ttl_expiration(instance, tags_dict, current_time)
        
        assert action is not None
        assert action.action == "TERMINATE_CLUSTER"
        assert action.cluster_name == "test-eks"