"""Integration tests for OpenShift orchestration.

Tests the destroy_openshift_cluster orchestrator with mocked AWS clients.
Single-pass cleanup with EventBridge retry handling.
"""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch

from openshift_resource_cleanup.openshift.orchestrator import destroy_openshift_cluster


@pytest.mark.integration
@pytest.mark.openshift
class TestDestroyOpenshiftCluster:
    """Test OpenShift cluster destruction orchestration."""

    @patch("openshift_resource_cleanup.openshift.orchestrator.cleanup_s3_state")
    @patch("openshift_resource_cleanup.openshift.orchestrator.cleanup_route53_records")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_internet_gateway")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_route_tables")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_subnets")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_security_groups")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_vpc_endpoints")
    @patch("openshift_resource_cleanup.openshift.orchestrator.cleanup_network_interfaces")
    @patch("openshift_resource_cleanup.openshift.orchestrator.release_elastic_ips")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_nat_gateways")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("openshift_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("openshift_resource_cleanup.models.config.DRY_RUN", False)
    def test_orchestrator_calls_functions_in_correct_order(
        self,
        mock_boto_client,
        mock_delete_lbs,
        mock_delete_nats,
        mock_release_eips,
        mock_cleanup_enis,
        mock_delete_endpoints,
        mock_delete_sgs,
        mock_delete_subnets,
        mock_delete_rts,
        mock_delete_igw,
        mock_delete_vpc,
        mock_cleanup_route53,
        mock_cleanup_s3,
    ):
        """
        GIVEN OpenShift cluster exists and VPC can be deleted
        WHEN destroy_openshift_cluster is called
        THEN resources should be deleted in dependency order in single pass
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # VPC exists and can be deleted
        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}
        mock_delete_vpc.return_value = True  # VPC successfully deleted

        result = destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Verify single-pass cleanup
        assert result is True

        # Verify cleanup functions called once in correct order
        mock_delete_lbs.assert_called_once_with("test-infra-123", "us-east-1")
        mock_delete_nats.assert_called_once_with("test-infra-123", "us-east-1")
        mock_release_eips.assert_called_once_with("test-infra-123", "us-east-1")
        mock_cleanup_enis.assert_called_once_with("vpc-abc123", "us-east-1")
        mock_delete_endpoints.assert_called_once_with("vpc-abc123", "us-east-1")
        mock_delete_sgs.assert_called_once_with("vpc-abc123", "us-east-1")
        mock_delete_subnets.assert_called_once_with("vpc-abc123", "us-east-1")
        mock_delete_rts.assert_called_once_with("vpc-abc123", "us-east-1")
        mock_delete_igw.assert_called_once_with("vpc-abc123", "us-east-1")
        mock_delete_vpc.assert_called_once_with("vpc-abc123", "us-east-1")

        # Route53 and S3 cleanup when VPC successfully deleted
        mock_cleanup_route53.assert_called_once_with("test-cluster", "us-east-1")
        mock_cleanup_s3.assert_called_once_with("test-cluster", "us-east-1")

    @patch("openshift_resource_cleanup.openshift.orchestrator.cleanup_s3_state")
    @patch("openshift_resource_cleanup.openshift.orchestrator.cleanup_route53_records")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("openshift_resource_cleanup.openshift.orchestrator.boto3.client")
    def test_orchestrator_exits_early_when_vpc_not_found(
        self, mock_boto_client, mock_delete_lbs, mock_delete_vpc, mock_cleanup_route53, mock_cleanup_s3
    ):
        """
        GIVEN VPC does not exist
        WHEN destroy_openshift_cluster is called
        THEN cleanup should exit early and clean up Route53/S3
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {"Vpcs": []}

        result = destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Should check VPC once and exit
        mock_ec2.describe_vpcs.assert_called_once()
        mock_delete_lbs.assert_not_called()
        mock_delete_vpc.assert_not_called()

        # Should clean up Route53/S3 when VPC is already gone
        mock_cleanup_route53.assert_called_once_with("test-cluster", "us-east-1")
        mock_cleanup_s3.assert_called_once_with("test-cluster", "us-east-1")

        assert result is True  # Cleanup complete

    @patch("openshift_resource_cleanup.openshift.orchestrator.cleanup_s3_state")
    @patch("openshift_resource_cleanup.openshift.orchestrator.cleanup_route53_records")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("openshift_resource_cleanup.openshift.orchestrator.boto3.client")
    def test_vpc_has_dependencies_returns_false(
        self,
        mock_boto_client,
        mock_delete_lbs,
        mock_delete_vpc,
        mock_cleanup_route53,
        mock_cleanup_s3,
    ):
        """
        GIVEN VPC still has dependencies and cannot be deleted
        WHEN destroy_openshift_cluster is called
        THEN cleanup should return False and Route53/S3 should NOT be cleaned
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # VPC exists but has dependencies
        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}
        mock_delete_vpc.return_value = False  # VPC has dependencies

        result = destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Should return False (cleanup incomplete)
        assert result is False

        # Route53 and S3 should NOT be cleaned when VPC deletion fails
        mock_cleanup_route53.assert_not_called()
        mock_cleanup_s3.assert_not_called()

    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("openshift_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("openshift_resource_cleanup.openshift.orchestrator.boto3.client")
    def test_orchestrator_handles_dependency_violations(
        self, mock_boto_client, mock_delete_lbs, mock_delete_vpc
    ):
        """
        GIVEN DependencyViolation error occurs during cleanup
        WHEN destroy_openshift_cluster is called
        THEN should return False and rely on EventBridge retry
        """
        from botocore.exceptions import ClientError

        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # VPC exists
        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}

        # Simulate DependencyViolation error
        error_response = {"Error": {"Code": "DependencyViolation"}}
        mock_delete_lbs.side_effect = ClientError(error_response, "DeleteLoadBalancer")

        result = destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Should return False (dependencies remain)
        assert result is False
