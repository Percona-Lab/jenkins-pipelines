"""Unit tests for EKS CloudFormation stack operations."""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch
from botocore.exceptions import ClientError

from aws_resource_cleanup.eks.cloudformation import (
    get_eks_cloudformation_billing_tag,
    cleanup_failed_stack_resources,
    delete_eks_cluster_stack,
)


@pytest.mark.unit
@pytest.mark.eks
class TestGetEksCloudformationBillingTag:
    """Test CloudFormation stack billing tag retrieval."""

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_retrieves_billing_tag_from_stack(self, mock_boto_client):
        """
        GIVEN CloudFormation stack exists with iit-billing-tag
        WHEN get_eks_cloudformation_billing_tag is called
        THEN billing tag should be returned
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [
                {
                    "StackName": "eksctl-test-cluster-cluster",
                    "Tags": [
                        {"Key": "iit-billing-tag", "Value": "eks-team"},
                        {"Key": "Environment", "Value": "test"},
                    ],
                }
            ]
        }

        billing_tag = get_eks_cloudformation_billing_tag("test-cluster", "us-east-1")

        assert billing_tag == "eks-team"
        mock_cfn.describe_stacks.assert_called_once_with(
            StackName="eksctl-test-cluster-cluster"
        )

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_returns_none_when_stack_not_found(self, mock_boto_client):
        """
        GIVEN CloudFormation stack does not exist
        WHEN get_eks_cloudformation_billing_tag is called
        THEN None should be returned
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.side_effect = ClientError(
            {"Error": {"Code": "ValidationError", "Message": "Stack does not exist"}},
            "DescribeStacks",
        )

        billing_tag = get_eks_cloudformation_billing_tag("nonexistent", "us-east-1")

        assert billing_tag is None

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_returns_none_when_no_billing_tag(self, mock_boto_client):
        """
        GIVEN CloudFormation stack exists without iit-billing-tag
        WHEN get_eks_cloudformation_billing_tag is called
        THEN None should be returned
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [
                {
                    "StackName": "eksctl-test-cluster-cluster",
                    "Tags": [{"Key": "Environment", "Value": "test"}],
                }
            ]
        }

        billing_tag = get_eks_cloudformation_billing_tag("test-cluster", "us-east-1")

        assert billing_tag is None


@pytest.mark.unit
@pytest.mark.eks
class TestCleanupFailedStackResources:
    """Test manual cleanup of failed stack resources."""

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_cleans_up_security_group_ingress_rules(self, mock_boto_client):
        """
        GIVEN stack with DELETE_FAILED security group ingress
        WHEN cleanup_failed_stack_resources is called
        THEN ingress rules should be revoked
        """
        mock_cfn = Mock()
        mock_ec2 = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "cloudformation":
                return mock_cfn
            elif service_name == "ec2":
                return mock_ec2
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_cfn.describe_stack_events.return_value = {
            "StackEvents": [
                {
                    "LogicalResourceId": "SecurityGroupIngress",
                    "ResourceType": "AWS::EC2::SecurityGroupIngress",
                    "ResourceStatus": "DELETE_FAILED",
                    "PhysicalResourceId": "sg-abc123|tcp|443|0.0.0.0/0",
                }
            ]
        }

        mock_ec2.describe_security_groups.return_value = {
            "SecurityGroups": [
                {
                    "GroupId": "sg-abc123",
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

        result = cleanup_failed_stack_resources("test-stack", "us-east-1")

        assert result is True
        mock_ec2.revoke_security_group_ingress.assert_called_once()

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_disassociates_route_table(self, mock_boto_client):
        """
        GIVEN stack with DELETE_FAILED route table association
        WHEN cleanup_failed_stack_resources is called
        THEN association should be removed
        """
        mock_cfn = Mock()
        mock_ec2 = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "cloudformation":
                return mock_cfn
            elif service_name == "ec2":
                return mock_ec2
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_cfn.describe_stack_events.return_value = {
            "StackEvents": [
                {
                    "LogicalResourceId": "RouteTableAssociation",
                    "ResourceType": "AWS::EC2::SubnetRouteTableAssociation",
                    "ResourceStatus": "DELETE_FAILED",
                    "PhysicalResourceId": "rtbassoc-abc123",
                }
            ]
        }

        result = cleanup_failed_stack_resources("test-stack", "us-east-1")

        assert result is True
        mock_ec2.disassociate_route_table.assert_called_once_with(
            AssociationId="rtbassoc-abc123"
        )

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_deletes_route(self, mock_boto_client):
        """
        GIVEN stack with DELETE_FAILED route
        WHEN cleanup_failed_stack_resources is called
        THEN route should be deleted
        """
        mock_cfn = Mock()
        mock_ec2 = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "cloudformation":
                return mock_cfn
            elif service_name == "ec2":
                return mock_ec2
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_cfn.describe_stack_events.return_value = {
            "StackEvents": [
                {
                    "LogicalResourceId": "Route",
                    "ResourceType": "AWS::EC2::Route",
                    "ResourceStatus": "DELETE_FAILED",
                    "PhysicalResourceId": "rtb-abc123_10.0.0.0/16",
                }
            ]
        }

        result = cleanup_failed_stack_resources("test-stack", "us-east-1")

        assert result is True
        mock_ec2.delete_route.assert_called_once_with(
            RouteTableId="rtb-abc123", DestinationCidrBlock="10.0.0.0/16"
        )

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_returns_true_when_no_failed_resources(self, mock_boto_client):
        """
        GIVEN stack with no DELETE_FAILED resources
        WHEN cleanup_failed_stack_resources is called
        THEN True should be returned without cleanup attempts
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stack_events.return_value = {
            "StackEvents": [
                {
                    "LogicalResourceId": "VPC",
                    "ResourceType": "AWS::EC2::VPC",
                    "ResourceStatus": "DELETE_COMPLETE",
                }
            ]
        }

        result = cleanup_failed_stack_resources("test-stack", "us-east-1")

        assert result is True

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_handles_resource_not_found_gracefully(self, mock_boto_client):
        """
        GIVEN failed resource that no longer exists
        WHEN cleanup_failed_stack_resources is called
        THEN error should be handled gracefully
        """
        mock_cfn = Mock()
        mock_ec2 = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "cloudformation":
                return mock_cfn
            elif service_name == "ec2":
                return mock_ec2
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_cfn.describe_stack_events.return_value = {
            "StackEvents": [
                {
                    "LogicalResourceId": "SecurityGroupIngress",
                    "ResourceType": "AWS::EC2::SecurityGroupIngress",
                    "ResourceStatus": "DELETE_FAILED",
                    "PhysicalResourceId": "sg-nonexistent|tcp|443",
                }
            ]
        }

        mock_ec2.describe_security_groups.side_effect = ClientError(
            {"Error": {"Code": "InvalidGroup.NotFound"}}, "DescribeSecurityGroups"
        )

        # Should not raise exception
        result = cleanup_failed_stack_resources("test-stack", "us-east-1")

        assert result is True


@pytest.mark.unit
@pytest.mark.eks
class TestDeleteEksClusterStack:
    """Test EKS cluster CloudFormation stack deletion."""

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    @patch("aws_resource_cleanup.eks.cloudformation.DRY_RUN", False)
    def test_deletes_stack_in_create_complete_state(self, mock_boto_client):
        """
        GIVEN stack in CREATE_COMPLETE state
        WHEN delete_eks_cluster_stack is called in live mode
        THEN stack deletion should be initiated
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [{"StackStatus": "CREATE_COMPLETE"}]
        }

        result = delete_eks_cluster_stack("test-cluster", "us-east-1")

        assert result is True
        mock_cfn.delete_stack.assert_called_once_with(
            StackName="eksctl-test-cluster-cluster"
        )

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    @patch("aws_resource_cleanup.eks.cloudformation.DRY_RUN", True)
    def test_skips_deletion_in_dry_run_mode(self, mock_boto_client):
        """
        GIVEN stack exists
        WHEN delete_eks_cluster_stack is called in DRY_RUN mode
        THEN no deletion should occur
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [{"StackStatus": "CREATE_COMPLETE"}]
        }

        result = delete_eks_cluster_stack("test-cluster", "us-east-1")

        assert result is True
        mock_cfn.delete_stack.assert_not_called()

    @patch("aws_resource_cleanup.eks.cloudformation.cleanup_failed_stack_resources")
    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    @patch("aws_resource_cleanup.eks.cloudformation.DRY_RUN", False)
    def test_retries_deletion_for_delete_failed_stack(
        self, mock_boto_client, mock_cleanup_resources
    ):
        """
        GIVEN stack in DELETE_FAILED state
        WHEN delete_eks_cluster_stack is called
        THEN failed resources should be cleaned up and deletion retried
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [{"StackStatus": "DELETE_FAILED"}]
        }
        mock_cleanup_resources.return_value = True

        result = delete_eks_cluster_stack("test-cluster", "us-east-1")

        assert result is True
        mock_cleanup_resources.assert_called_once_with(
            "eksctl-test-cluster-cluster", "us-east-1"
        )
        mock_cfn.delete_stack.assert_called_once()

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_returns_true_for_already_deleting_stack(self, mock_boto_client):
        """
        GIVEN stack in DELETE_IN_PROGRESS state
        WHEN delete_eks_cluster_stack is called
        THEN True should be returned without initiating new deletion
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [{"StackStatus": "DELETE_IN_PROGRESS"}]
        }

        result = delete_eks_cluster_stack("test-cluster", "us-east-1")

        assert result is True
        mock_cfn.delete_stack.assert_not_called()

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_returns_false_when_stack_not_found(self, mock_boto_client):
        """
        GIVEN stack does not exist
        WHEN delete_eks_cluster_stack is called
        THEN False should be returned
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.side_effect = ClientError(
            {"Error": {"Code": "ValidationError", "Message": "does not exist"}},
            "DescribeStacks",
        )

        result = delete_eks_cluster_stack("nonexistent", "us-east-1")

        assert result is False
        mock_cfn.delete_stack.assert_not_called()

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_handles_unexpected_errors(self, mock_boto_client):
        """
        GIVEN unexpected AWS API error
        WHEN delete_eks_cluster_stack is called
        THEN error should be handled and False returned
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.side_effect = Exception("Unexpected error")

        result = delete_eks_cluster_stack("test-cluster", "us-east-1")

        assert result is False

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_constructs_correct_stack_name(self, mock_boto_client):
        """
        GIVEN cluster name
        WHEN delete_eks_cluster_stack is called
        THEN correct eksctl stack name should be used
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [{"StackStatus": "CREATE_COMPLETE"}]
        }

        delete_eks_cluster_stack("my-eks-cluster", "us-west-2")

        # Should use eksctl naming convention
        mock_cfn.describe_stacks.assert_called_with(
            StackName="eksctl-my-eks-cluster-cluster"
        )

    @patch("aws_resource_cleanup.eks.cloudformation.boto3.client")
    def test_handles_rollback_complete_state(self, mock_boto_client):
        """
        GIVEN stack in ROLLBACK_COMPLETE state
        WHEN delete_eks_cluster_stack is called
        THEN deletion should proceed normally
        """
        mock_cfn = Mock()
        mock_boto_client.return_value = mock_cfn

        mock_cfn.describe_stacks.return_value = {
            "Stacks": [{"StackStatus": "ROLLBACK_COMPLETE"}]
        }

        result = delete_eks_cluster_stack("test-cluster", "us-east-1")

        assert result is True
