"""Unit tests for OpenShift network cleanup functions.

Tests the individual network resource deletion functions with mocked boto3 clients.
"""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch
from botocore.exceptions import ClientError

from openshift_resource_cleanup.openshift.network import (
    delete_nat_gateways,
    release_elastic_ips,
    cleanup_network_interfaces,
    delete_vpc_endpoints,
    delete_security_groups,
    delete_subnets,
    delete_route_tables,
    delete_internet_gateway,
    delete_vpc,
)


@pytest.mark.unit
@pytest.mark.openshift
class TestDeleteNatGateways:
    """Test NAT gateway deletion."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_deletes_nat_gateways_live_mode(self, mock_boto_client):
        """
        GIVEN NAT gateways exist for OpenShift cluster
        WHEN delete_nat_gateways is called in live mode
        THEN delete_nat_gateway should be called for each NAT gateway
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_nat_gateways.return_value = {
            "NatGateways": [
                {"NatGatewayId": "nat-abc123"},
                {"NatGatewayId": "nat-def456"},
            ]
        }

        delete_nat_gateways("test-infra-123", "us-east-1")

        mock_ec2.describe_nat_gateways.assert_called_once_with(
            Filters=[
                {
                    "Name": "tag:kubernetes.io/cluster/test-infra-123",
                    "Values": ["owned"],
                },
                {"Name": "state", "Values": ["available", "pending"]},
            ]
        )
        assert mock_ec2.delete_nat_gateway.call_count == 2
        mock_ec2.delete_nat_gateway.assert_any_call(NatGatewayId="nat-abc123")
        mock_ec2.delete_nat_gateway.assert_any_call(NatGatewayId="nat-def456")

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", True)
    def test_skips_deletion_in_dry_run_mode(self, mock_boto_client):
        """
        GIVEN NAT gateways exist for OpenShift cluster
        WHEN delete_nat_gateways is called in DRY_RUN mode
        THEN no deletion should occur
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_nat_gateways.return_value = {
            "NatGateways": [{"NatGatewayId": "nat-abc123"}]
        }

        delete_nat_gateways("test-infra-123", "us-east-1")

        mock_ec2.describe_nat_gateways.assert_called_once()
        mock_ec2.delete_nat_gateway.assert_not_called()

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    def test_handles_empty_nat_gateway_list(self, mock_boto_client):
        """
        GIVEN no NAT gateways exist
        WHEN delete_nat_gateways is called
        THEN function should complete without errors
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_nat_gateways.return_value = {"NatGateways": []}

        delete_nat_gateways("test-infra-123", "us-east-1")

        mock_ec2.delete_nat_gateway.assert_not_called()


@pytest.mark.unit
@pytest.mark.openshift
class TestReleaseElasticIps:
    """Test Elastic IP release."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_releases_elastic_ips_live_mode(self, mock_boto_client):
        """
        GIVEN Elastic IPs exist for OpenShift cluster
        WHEN release_elastic_ips is called in live mode
        THEN release_address should be called for each EIP
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_addresses.return_value = {
            "Addresses": [
                {"AllocationId": "eipalloc-abc123"},
                {"AllocationId": "eipalloc-def456"},
            ]
        }

        release_elastic_ips("test-infra-123", "us-east-1")

        mock_ec2.describe_addresses.assert_called_once_with(
            Filters=[
                {
                    "Name": "tag:kubernetes.io/cluster/test-infra-123",
                    "Values": ["owned"],
                }
            ]
        )
        assert mock_ec2.release_address.call_count == 2
        mock_ec2.release_address.assert_any_call(AllocationId="eipalloc-abc123")
        mock_ec2.release_address.assert_any_call(AllocationId="eipalloc-def456")

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_handles_client_error_gracefully(self, mock_boto_client):
        """
        GIVEN an EIP that cannot be released (already released)
        WHEN release_elastic_ips is called
        THEN ClientError should be caught and function continues
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_addresses.return_value = {
            "Addresses": [{"AllocationId": "eipalloc-abc123"}]
        }
        mock_ec2.release_address.side_effect = ClientError(
            {"Error": {"Code": "InvalidAllocationID.NotFound"}}, "ReleaseAddress"
        )

        # Should not raise exception
        release_elastic_ips("test-infra-123", "us-east-1")

        mock_ec2.release_address.assert_called_once()


@pytest.mark.unit
@pytest.mark.openshift
class TestCleanupNetworkInterfaces:
    """Test network interface cleanup."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_deletes_available_enis(self, mock_boto_client):
        """
        GIVEN available (orphaned) network interfaces in VPC
        WHEN cleanup_network_interfaces is called in live mode
        THEN delete_network_interface should be called for each ENI
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_network_interfaces.return_value = {
            "NetworkInterfaces": [
                {"NetworkInterfaceId": "eni-abc123"},
                {"NetworkInterfaceId": "eni-def456"},
            ]
        }

        cleanup_network_interfaces("vpc-123456", "us-east-1")

        mock_ec2.describe_network_interfaces.assert_called_once_with(
            Filters=[
                {"Name": "vpc-id", "Values": ["vpc-123456"]},
                {"Name": "status", "Values": ["available"]},
            ]
        )
        assert mock_ec2.delete_network_interface.call_count == 2


@pytest.mark.unit
@pytest.mark.openshift
class TestDeleteVpcEndpoints:
    """Test VPC endpoint deletion."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_deletes_vpc_endpoints(self, mock_boto_client):
        """
        GIVEN VPC endpoints exist in VPC
        WHEN delete_vpc_endpoints is called in live mode
        THEN delete_vpc_endpoints should be called for each endpoint
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_vpc_endpoints.return_value = {
            "VpcEndpoints": [
                {"VpcEndpointId": "vpce-abc123"},
                {"VpcEndpointId": "vpce-def456"},
            ]
        }

        delete_vpc_endpoints("vpc-123456", "us-east-1")

        assert mock_ec2.delete_vpc_endpoints.call_count == 2
        mock_ec2.delete_vpc_endpoints.assert_any_call(VpcEndpointIds=["vpce-abc123"])


@pytest.mark.unit
@pytest.mark.openshift
class TestDeleteSecurityGroups:
    """Test security group deletion with dependency handling."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_removes_ingress_rules_before_deletion(self, mock_boto_client):
        """
        GIVEN security groups with ingress rules
        WHEN delete_security_groups is called
        THEN ingress rules should be revoked before deletion
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_security_groups.return_value = {
            "SecurityGroups": [
                {
                    "GroupId": "sg-abc123",
                    "GroupName": "openshift-sg",
                    "IpPermissions": [
                        {
                            "IpProtocol": "tcp",
                            "FromPort": 443,
                            "ToPort": 443,
                            "IpRanges": [{"CidrIp": "0.0.0.0/0"}],
                        }
                    ],
                }
            ]
        }

        delete_security_groups("vpc-123456", "us-east-1")

        # Should revoke ingress rules first
        mock_ec2.revoke_security_group_ingress.assert_called_once()
        # Then delete the security group
        mock_ec2.delete_security_group.assert_called_once_with(GroupId="sg-abc123")

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_skips_default_security_group(self, mock_boto_client):
        """
        GIVEN a VPC with default security group
        WHEN delete_security_groups is called
        THEN default security group should not be deleted
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_security_groups.return_value = {
            "SecurityGroups": [
                {
                    "GroupId": "sg-default",
                    "GroupName": "default",
                    "IpPermissions": [],
                }
            ]
        }

        delete_security_groups("vpc-123456", "us-east-1")

        mock_ec2.delete_security_group.assert_not_called()


@pytest.mark.unit
@pytest.mark.openshift
class TestDeleteSubnets:
    """Test subnet deletion."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_deletes_all_subnets(self, mock_boto_client):
        """
        GIVEN subnets exist in VPC
        WHEN delete_subnets is called in live mode
        THEN delete_subnet should be called for each subnet
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_subnets.return_value = {
            "Subnets": [
                {"SubnetId": "subnet-abc123"},
                {"SubnetId": "subnet-def456"},
            ]
        }

        delete_subnets("vpc-123456", "us-east-1")

        assert mock_ec2.delete_subnet.call_count == 2


@pytest.mark.unit
@pytest.mark.openshift
class TestDeleteRouteTables:
    """Test route table deletion."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_skips_main_route_table(self, mock_boto_client):
        """
        GIVEN route tables including main route table
        WHEN delete_route_tables is called
        THEN main route table should not be deleted
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_route_tables.return_value = {
            "RouteTables": [
                {
                    "RouteTableId": "rtb-main",
                    "Associations": [{"Main": True}],
                },
                {
                    "RouteTableId": "rtb-custom",
                    "Associations": [{"Main": False}],
                },
            ]
        }

        delete_route_tables("vpc-123456", "us-east-1")

        # Should only delete non-main route table
        mock_ec2.delete_route_table.assert_called_once_with(RouteTableId="rtb-custom")


@pytest.mark.unit
@pytest.mark.openshift
class TestDeleteInternetGateway:
    """Test internet gateway deletion."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_detaches_and_deletes_igw(self, mock_boto_client):
        """
        GIVEN internet gateway attached to VPC
        WHEN delete_internet_gateway is called
        THEN IGW should be detached then deleted
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_internet_gateways.return_value = {
            "InternetGateways": [{"InternetGatewayId": "igw-abc123"}]
        }

        delete_internet_gateway("vpc-123456", "us-east-1")

        mock_ec2.detach_internet_gateway.assert_called_once_with(
            InternetGatewayId="igw-abc123", VpcId="vpc-123456"
        )
        mock_ec2.delete_internet_gateway.assert_called_once_with(
            InternetGatewayId="igw-abc123"
        )


@pytest.mark.unit
@pytest.mark.openshift
class TestDeleteVpc:
    """Test VPC deletion."""

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", False)
    def test_deletes_vpc_live_mode(self, mock_boto_client):
        """
        GIVEN VPC exists
        WHEN delete_vpc is called in live mode
        THEN delete_vpc should be called
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        delete_vpc("vpc-123456", "us-east-1")

        mock_ec2.delete_vpc.assert_called_once_with(VpcId="vpc-123456")

    @patch("openshift_resource_cleanup.openshift.network.boto3.client")
    @patch("openshift_resource_cleanup.openshift.network.DRY_RUN", True)
    def test_skips_deletion_in_dry_run(self, mock_boto_client):
        """
        GIVEN VPC exists
        WHEN delete_vpc is called in DRY_RUN mode
        THEN no deletion should occur
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        delete_vpc("vpc-123456", "us-east-1")

        mock_ec2.delete_vpc.assert_not_called()
