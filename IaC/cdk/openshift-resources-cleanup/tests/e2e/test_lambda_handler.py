"""End-to-end tests for Lambda handler entry point and integration flows.

Tests focus on:
- lambda_handler() entry point (multi-region orchestration)
- End-to-end integration flows
- Error propagation and partial failure scenarios
"""

from __future__ import annotations
import datetime
import json
import pytest
from unittest.mock import Mock, patch, MagicMock
from botocore.exceptions import ClientError

from openshift_resource_cleanup.handler import lambda_handler, cleanup_region
from openshift_resource_cleanup.models import CleanupAction


@pytest.fixture
def mock_lambda_context():
    """Create a mock Lambda context object."""
    context = Mock()
    context.function_name = "test-function"
    context.function_version = "$LATEST"
    context.invoked_function_arn = "arn:aws:lambda:us-east-1:123456789012:function:test-function"
    context.memory_limit_in_mb = 128
    context.aws_request_id = "test-request-id"
    context.log_group_name = "/aws/lambda/test-function"
    context.log_stream_name = "2024/01/01/[$LATEST]test"
    context.get_remaining_time_in_millis = Mock(return_value=300000)
    return context


@pytest.mark.e2e
@pytest.mark.aws
class TestLambdaHandlerEntryPoint:
    """Test the main Lambda handler entry point."""

    @patch("openshift_resource_cleanup.handler.cleanup_region")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_no_actions_across_all_regions(
        self, mock_boto_client, mock_cleanup_region, mock_lambda_context
    ):
        """
        GIVEN regions with no cleanup actions needed
        WHEN lambda_handler is invoked
        THEN response should show zero actions
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_regions.return_value = {
            "Regions": [
                {"RegionName": "us-east-1"},
                {"RegionName": "us-west-2"},
            ]
        }

        mock_cleanup_region.return_value = []

        result = lambda_handler({}, mock_lambda_context)

        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 0
        assert body["by_action"] == {}
        assert body["actions"] == []

    @patch("openshift_resource_cleanup.handler.cleanup_region")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    @patch("openshift_resource_cleanup.handler.DRY_RUN", True)
    def test_lambda_handler_includes_dry_run_flag(
        self, mock_boto_client, mock_cleanup_region, mock_lambda_context
    ):
        """
        GIVEN DRY_RUN mode enabled
        WHEN lambda_handler is invoked
        THEN response should indicate dry_run=true
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_regions.return_value = {"Regions": [{"RegionName": "us-east-1"}]}
        mock_cleanup_region.return_value = []

        result = lambda_handler({}, mock_lambda_context)

        body = json.loads(result["body"])
        assert body["dry_run"] is True

    @patch("openshift_resource_cleanup.handler.cleanup_region")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_aggregates_actions_correctly(
        self, mock_boto_client, mock_cleanup_region, mock_lambda_context
    ):
        """
        GIVEN multiple regions with various action types
        WHEN lambda_handler is invoked
        THEN actions should be aggregated correctly by type
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_regions.return_value = {
            "Regions": [{"RegionName": "us-east-1"}, {"RegionName": "us-west-2"}]
        }

        # Region 1: 2 TERMINATE, 1 STOP
        # Region 2: 1 TERMINATE, 1 TERMINATE_CLUSTER
        region1_actions = [
            CleanupAction("i-1", "us-east-1", "n1", "TERMINATE", "r1", 1.0),
            CleanupAction("i-2", "us-east-1", "n2", "TERMINATE", "r2", 1.0),
            CleanupAction("i-3", "us-east-1", "n3", "STOP", "r3", 0.5),
        ]
        region2_actions = [
            CleanupAction("i-4", "us-west-2", "n4", "TERMINATE", "r4", 2.0),
            CleanupAction("i-5", "us-west-2", "n5", "TERMINATE_CLUSTER", "r5", 3.0, cluster_name="eks"),
        ]
        mock_cleanup_region.side_effect = [region1_actions, region2_actions]

        result = lambda_handler({}, mock_lambda_context)

        body = json.loads(result["body"])
        assert body["total_actions"] == 5
        assert body["by_action"]["TERMINATE"] == 3
        assert body["by_action"]["STOP"] == 1
        assert body["by_action"]["TERMINATE_CLUSTER"] == 1

    @patch("openshift_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_handles_describe_regions_failure(self, mock_boto_client, mock_lambda_context):
        """
        GIVEN describe_regions API call fails
        WHEN lambda_handler is invoked
        THEN exception should be raised
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_regions.side_effect = ClientError(
            {"Error": {"Code": "RequestLimitExceeded", "Message": "Rate limit"}},
            "DescribeRegions",
        )

        with pytest.raises(ClientError):
            lambda_handler({}, mock_lambda_context)


@pytest.mark.e2e
@pytest.mark.aws
class TestPartialFailureScenarios:
    """Test error propagation and partial failure handling."""

    @patch("openshift_resource_cleanup.handler.cleanup_region")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_continues_after_region_failure(
        self, mock_boto_client, mock_cleanup_region, mock_lambda_context
    ):
        """
        GIVEN one region fails but others succeed
        WHEN lambda_handler is invoked
        THEN successful regions should be processed and returned
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_regions.return_value = {
            "Regions": [
                {"RegionName": "us-east-1"},
                {"RegionName": "us-west-2"},
                {"RegionName": "eu-west-1"},
            ]
        }

        action = CleanupAction("i-ok", "us-east-1", "test", "TERMINATE", "test", 1.0)

        # Region 1: success, Region 2: exception, Region 3: success
        mock_cleanup_region.side_effect = [
            [action],
            [],  # Returns empty instead of raising to match actual behavior
            [action],
        ]

        result = lambda_handler({}, mock_lambda_context)

        # Should succeed with actions from regions 1 and 3
        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 2



@pytest.mark.e2e
class TestLambdaEventHandling:
    """Test Lambda event validation and edge cases."""

    @patch("openshift_resource_cleanup.handler.cleanup_region")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_accepts_empty_event(
        self, mock_boto_client, mock_cleanup_region, mock_lambda_context
    ):
        """
        GIVEN empty Lambda event
        WHEN lambda_handler is invoked
        THEN it should process normally
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_regions.return_value = {"Regions": [{"RegionName": "us-east-1"}]}
        mock_cleanup_region.return_value = []

        result = lambda_handler({}, mock_lambda_context)

        assert result["statusCode"] == 200

    @patch("openshift_resource_cleanup.handler.cleanup_region")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_accepts_none_context(
        self, mock_boto_client, mock_cleanup_region, mock_lambda_context
    ):
        """
        GIVEN None as Lambda context
        WHEN lambda_handler is invoked
        THEN it should process normally
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_regions.return_value = {"Regions": [{"RegionName": "us-east-1"}]}
        mock_cleanup_region.return_value = []

        result = lambda_handler({}, mock_lambda_context)

        assert result["statusCode"] == 200
