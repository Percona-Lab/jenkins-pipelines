"""Unit tests for OpenShift cluster detection."""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch

from aws_resource_cleanup.openshift.detection import detect_openshift_infra_id


@pytest.mark.unit
@pytest.mark.openshift
class TestDetectOpenshiftInfraId:
    """Test OpenShift infrastructure ID detection."""

    @patch("aws_resource_cleanup.openshift.detection.boto3.client")
    def test_detects_infra_id_from_exact_match(self, mock_boto_client):
        """
        GIVEN VPC with exact cluster name tag
        WHEN detect_openshift_infra_id is called
        THEN infrastructure ID should be extracted from tag
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {
            "Vpcs": [
                {
                    "VpcId": "vpc-abc123",
                    "Tags": [
                        {
                            "Key": "kubernetes.io/cluster/test-infra-abc123",
                            "Value": "owned",
                        },
                        {"Key": "Name", "Value": "openshift-vpc"},
                    ],
                }
            ]
        }

        infra_id = detect_openshift_infra_id("test-infra-abc123", "us-east-1")

        assert infra_id == "test-infra-abc123"
        mock_ec2.describe_vpcs.assert_called_once_with(
            Filters=[
                {
                    "Name": "tag-key",
                    "Values": ["kubernetes.io/cluster/test-infra-abc123"],
                }
            ]
        )

    @patch("aws_resource_cleanup.openshift.detection.boto3.client")
    def test_detects_infra_id_from_wildcard_match(self, mock_boto_client):
        """
        GIVEN VPC with cluster name prefix (wildcard match needed)
        WHEN detect_openshift_infra_id is called with cluster name
        THEN infrastructure ID should be extracted from tag
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # First call returns empty (exact match fails)
        # Second call returns VPC (wildcard match succeeds)
        mock_ec2.describe_vpcs.side_effect = [
            {"Vpcs": []},
            {
                "Vpcs": [
                    {
                        "VpcId": "vpc-def456",
                        "Tags": [
                            {
                                "Key": "kubernetes.io/cluster/test-cluster-xyz789",
                                "Value": "owned",
                            }
                        ],
                    }
                ]
            },
        ]

        infra_id = detect_openshift_infra_id("test-cluster", "us-east-1")

        assert infra_id == "test-cluster-xyz789"
        assert mock_ec2.describe_vpcs.call_count == 2

        # First call: exact match
        first_call = mock_ec2.describe_vpcs.call_args_list[0]
        assert first_call.kwargs["Filters"][0]["Values"] == [
            "kubernetes.io/cluster/test-cluster"
        ]

        # Second call: wildcard match
        second_call = mock_ec2.describe_vpcs.call_args_list[1]
        assert second_call.kwargs["Filters"][0]["Values"] == [
            "kubernetes.io/cluster/test-cluster-*"
        ]

    @patch("aws_resource_cleanup.openshift.detection.boto3.client")
    def test_returns_none_when_no_vpc_found(self, mock_boto_client):
        """
        GIVEN no VPC exists with cluster tags
        WHEN detect_openshift_infra_id is called
        THEN None should be returned
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {"Vpcs": []}

        infra_id = detect_openshift_infra_id("nonexistent-cluster", "us-east-1")

        assert infra_id is None

    @patch("aws_resource_cleanup.openshift.detection.boto3.client")
    def test_returns_none_when_vpc_has_no_cluster_tags(self, mock_boto_client):
        """
        GIVEN VPC exists but has no kubernetes cluster tags
        WHEN detect_openshift_infra_id is called
        THEN None should be returned
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.return_value = {
            "Vpcs": [
                {
                    "VpcId": "vpc-abc123",
                    "Tags": [
                        {"Key": "Name", "Value": "regular-vpc"},
                        {"Key": "Environment", "Value": "test"},
                    ],
                }
            ]
        }

        infra_id = detect_openshift_infra_id("test-cluster", "us-east-1")

        assert infra_id is None

    @patch("aws_resource_cleanup.openshift.detection.boto3.client")
    def test_handles_aws_api_exception(self, mock_boto_client):
        """
        GIVEN AWS API raises exception
        WHEN detect_openshift_infra_id is called
        THEN exception should be handled and None returned
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        mock_ec2.describe_vpcs.side_effect = Exception("AWS API Error")

        infra_id = detect_openshift_infra_id("test-cluster", "us-east-1")

        assert infra_id is None
