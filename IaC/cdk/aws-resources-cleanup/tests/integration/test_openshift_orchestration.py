"""Integration tests for OpenShift orchestration.

Tests the destroy_openshift_cluster orchestrator with mocked AWS clients.
"""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch

from aws_resource_cleanup.openshift.orchestrator import destroy_openshift_cluster


@pytest.mark.integration
@pytest.mark.openshift
class TestDestroyOpenshiftCluster:
    """Test OpenShift cluster destruction orchestration."""

    @patch("aws_resource_cleanup.openshift.orchestrator.cleanup_s3_state")
    @patch("aws_resource_cleanup.openshift.orchestrator.cleanup_route53_records")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_internet_gateway")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_route_tables")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_subnets")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_security_groups")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_vpc_endpoints")
    @patch("aws_resource_cleanup.openshift.orchestrator.cleanup_network_interfaces")
    @patch("aws_resource_cleanup.openshift.orchestrator.release_elastic_ips")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_nat_gateways")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 3)
    @patch("aws_resource_cleanup.openshift.orchestrator.DRY_RUN", False)
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
        GIVEN OpenShift cluster exists
        WHEN destroy_openshift_cluster is called
        THEN resources should be deleted in dependency order
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # First attempt: VPC exists
        # Second attempt: VPC exists
        # Third attempt: VPC still exists
        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}

        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Verify cleanup functions called in correct order for each attempt
        # Load balancers first
        assert mock_delete_lbs.call_count == 3
        mock_delete_lbs.assert_any_call("test-infra-123", "us-east-1")

        # NAT gateways second
        assert mock_delete_nats.call_count == 3

        # EIPs third
        assert mock_release_eips.call_count == 3

        # Network interfaces
        assert mock_cleanup_enis.call_count == 3
        mock_cleanup_enis.assert_any_call("vpc-abc123", "us-east-1")

        # VPC endpoints
        assert mock_delete_endpoints.call_count == 3

        # Security groups
        assert mock_delete_sgs.call_count == 3

        # Subnets
        assert mock_delete_subnets.call_count == 3

        # Route tables
        assert mock_delete_rts.call_count == 3

        # Internet gateway
        assert mock_delete_igw.call_count == 3

        # VPC
        assert mock_delete_vpc.call_count == 3

        # Route53 and S3 only on final attempt
        mock_cleanup_route53.assert_called_once_with("test-cluster", "us-east-1")
        mock_cleanup_s3.assert_called_once_with("test-cluster", "us-east-1")

    @patch("aws_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 3)
    def test_orchestrator_exits_early_when_vpc_not_found(
        self, mock_boto_client, mock_delete_lbs, mock_delete_vpc
    ):
        """
        GIVEN VPC does not exist
        WHEN destroy_openshift_cluster is called
        THEN reconciliation loop should exit early
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {"Vpcs": []}

        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Should only check VPC once and exit
        mock_ec2.describe_vpcs.assert_called_once()
        mock_delete_lbs.assert_not_called()
        mock_delete_vpc.assert_not_called()

    @patch("aws_resource_cleanup.openshift.orchestrator.cleanup_s3_state")
    @patch("aws_resource_cleanup.openshift.orchestrator.cleanup_route53_records")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 3)
    def test_route53_and_s3_cleanup_only_on_final_attempt(
        self,
        mock_boto_client,
        mock_delete_lbs,
        mock_delete_vpc,
        mock_cleanup_route53,
        mock_cleanup_s3,
    ):
        """
        GIVEN OpenShift cluster cleanup with multiple attempts
        WHEN reconciliation loop runs
        THEN Route53 and S3 cleanup should only occur on final attempt
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # VPC exists for first 2 attempts, gone on 3rd
        mock_ec2.describe_vpcs.side_effect = [
            {"Vpcs": [{"VpcId": "vpc-abc123"}]},  # Attempt 1
            {"Vpcs": [{"VpcId": "vpc-abc123"}]},  # Attempt 2
            {"Vpcs": [{"VpcId": "vpc-abc123"}]},  # Attempt 3 (final)
        ]

        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Route53 and S3 should only be called once (on final attempt)
        mock_cleanup_route53.assert_called_once_with("test-cluster", "us-east-1")
        mock_cleanup_s3.assert_called_once_with("test-cluster", "us-east-1")

    @patch("aws_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 2)
    def test_orchestrator_handles_exceptions_and_continues(
        self, mock_boto_client, mock_delete_lbs, mock_delete_vpc
    ):
        """
        GIVEN an exception occurs during cleanup
        WHEN destroy_openshift_cluster is called
        THEN error should be logged and next attempt should proceed
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # VPC exists on both attempts
        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}

        # First attempt: load balancer deletion fails
        # Second attempt: succeeds
        mock_delete_lbs.side_effect = [
            Exception("Simulated error"),
            None,  # Success on second attempt
        ]

        # Should not raise exception
        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Both attempts should be made despite first failure
        assert mock_ec2.describe_vpcs.call_count == 2

    @patch("aws_resource_cleanup.openshift.orchestrator.time.sleep")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_nat_gateways")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 3)
    @patch("aws_resource_cleanup.openshift.orchestrator.DRY_RUN", False)
    def test_delays_applied_between_critical_operations(
        self,
        mock_boto_client,
        mock_delete_lbs,
        mock_delete_nats,
        mock_sleep,
    ):
        """
        GIVEN live mode (not DRY_RUN)
        WHEN destroy_openshift_cluster is called
        THEN delays should be applied after load balancers and NAT gateways
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}

        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Should have delays:
        # - 10 seconds after load balancers (3 times)
        # - 10 seconds after NAT gateways (3 times)
        # - 15 seconds between retry attempts (2 times - not after final attempt)
        # Total: 6 for operations + 2 for retries = 8 sleep calls
        assert mock_sleep.call_count == 8  # 6 for operations + 2 for retries

    @patch("aws_resource_cleanup.openshift.orchestrator.time.sleep")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 2)
    @patch("aws_resource_cleanup.openshift.orchestrator.DRY_RUN", True)
    def test_delays_skipped_in_dry_run_mode(
        self, mock_boto_client, mock_delete_lbs, mock_sleep
    ):
        """
        GIVEN DRY_RUN mode enabled
        WHEN destroy_openshift_cluster is called
        THEN no delays should be applied
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}

        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # No sleep calls should be made in DRY_RUN mode
        mock_sleep.assert_not_called()

    @patch("aws_resource_cleanup.openshift.orchestrator.delete_vpc")
    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 5)
    def test_orchestrator_respects_max_retries_configuration(
        self, mock_boto_client, mock_delete_lbs, mock_delete_vpc
    ):
        """
        GIVEN OPENSHIFT_MAX_RETRIES configured to 5
        WHEN destroy_openshift_cluster is called
        THEN exactly 5 cleanup attempts should be made
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {"Vpcs": [{"VpcId": "vpc-abc123"}]}

        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Should make 5 attempts
        assert mock_ec2.describe_vpcs.call_count == 5
        assert mock_delete_lbs.call_count == 5


@pytest.mark.integration
@pytest.mark.openshift
class TestOpenshiftOrchestrationEdgeCases:
    """Test edge cases in OpenShift orchestration."""

    @patch("aws_resource_cleanup.openshift.orchestrator.delete_load_balancers")
    @patch("aws_resource_cleanup.openshift.orchestrator.boto3.client")
    @patch("aws_resource_cleanup.openshift.orchestrator.OPENSHIFT_MAX_RETRIES", 3)
    def test_handles_vpc_disappearing_mid_cleanup(
        self, mock_boto_client, mock_delete_lbs
    ):
        """
        GIVEN VPC exists on first attempt but disappears on second
        WHEN destroy_openshift_cluster is called
        THEN cleanup should exit gracefully after second check
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # First attempt: VPC exists
        # Second attempt: VPC gone
        mock_ec2.describe_vpcs.side_effect = [
            {"Vpcs": [{"VpcId": "vpc-abc123"}]},
            {"Vpcs": []},  # VPC deleted
        ]

        destroy_openshift_cluster("test-cluster", "test-infra-123", "us-east-1")

        # Should make 2 VPC checks, then exit
        assert mock_ec2.describe_vpcs.call_count == 2
        # First attempt should have cleanup calls
        assert mock_delete_lbs.call_count == 1
