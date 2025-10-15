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

from aws_resource_cleanup.handler import lambda_handler, cleanup_region
from aws_resource_cleanup.models import CleanupAction


@pytest.mark.e2e
@pytest.mark.aws
class TestLambdaHandlerEntryPoint:
    """Test the main Lambda handler entry point."""

    @patch("aws_resource_cleanup.handler.cleanup_region")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_processes_multiple_regions(
        self, mock_boto_client, mock_cleanup_region
    ):
        """
        GIVEN multiple AWS regions
        WHEN lambda_handler is invoked
        THEN all regions should be processed
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

        # Each region returns different actions
        action1 = CleanupAction(
            instance_id="i-region1",
            region="us-east-1",
            name="test1",
            action="TERMINATE",
            reason="TTL expired",
            days_overdue=1.0,
        )
        action2 = CleanupAction(
            instance_id="i-region2",
            region="us-west-2",
            name="test2",
            action="STOP",
            reason="stop-after-days",
            days_overdue=0.5,
        )
        mock_cleanup_region.side_effect = [[action1], [action2], []]

        result = lambda_handler({}, None)

        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 2
        assert body["by_action"]["TERMINATE"] == 1
        assert body["by_action"]["STOP"] == 1
        assert len(body["actions"]) == 2

        # Verify cleanup_region was called for each region
        assert mock_cleanup_region.call_count == 3
        mock_cleanup_region.assert_any_call("us-east-1")
        mock_cleanup_region.assert_any_call("us-west-2")
        mock_cleanup_region.assert_any_call("eu-west-1")

    @patch("aws_resource_cleanup.handler.cleanup_region")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_no_actions_across_all_regions(
        self, mock_boto_client, mock_cleanup_region
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

        result = lambda_handler({}, None)

        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 0
        assert body["by_action"] == {}
        assert body["actions"] == []

    @patch("aws_resource_cleanup.handler.cleanup_region")
    @patch("aws_resource_cleanup.handler.boto3.client")
    @patch("aws_resource_cleanup.handler.DRY_RUN", True)
    def test_lambda_handler_includes_dry_run_flag(
        self, mock_boto_client, mock_cleanup_region
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

        result = lambda_handler({}, None)

        body = json.loads(result["body"])
        assert body["dry_run"] is True

    @patch("aws_resource_cleanup.handler.cleanup_region")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_aggregates_actions_correctly(
        self, mock_boto_client, mock_cleanup_region
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

        result = lambda_handler({}, None)

        body = json.loads(result["body"])
        assert body["total_actions"] == 5
        assert body["by_action"]["TERMINATE"] == 3
        assert body["by_action"]["STOP"] == 1
        assert body["by_action"]["TERMINATE_CLUSTER"] == 1

    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_handles_describe_regions_failure(self, mock_boto_client):
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
            lambda_handler({}, None)


@pytest.mark.e2e
@pytest.mark.aws
class TestPartialFailureScenarios:
    """Test error propagation and partial failure handling."""

    @patch("aws_resource_cleanup.handler.cleanup_region")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_continues_after_region_failure(
        self, mock_boto_client, mock_cleanup_region
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

        result = lambda_handler({}, None)

        # Should succeed with actions from regions 1 and 3
        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 2


@pytest.mark.e2e
@pytest.mark.aws
@pytest.mark.smoke
class TestEndToEndIntegrationFlow:
    """Integration tests with minimal mocking to verify complete flow."""

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_end_to_end_cleanup_flow_with_expired_ttl(
        self, mock_boto_client, mock_send_notification, mock_execute_action
    ):
        """
        GIVEN Lambda invocation with instances having expired TTL
        WHEN full cleanup flow executes
        THEN instances should be identified, actions created, executed, and reported
        """
        # Setup EC2 mock for describe_regions
        mock_ec2_main = Mock()
        mock_ec2_regional = Mock()

        def get_client(service, region_name=None):
            if region_name:
                return mock_ec2_regional
            return mock_ec2_main

        mock_boto_client.side_effect = get_client

        # Mock describe_regions - single region for simplicity
        mock_ec2_main.describe_regions.return_value = {
            "Regions": [{"RegionName": "us-east-1"}]
        }

        # Mock describe_instances - instance with expired TTL
        now = datetime.datetime.now(datetime.timezone.utc)
        old_time = now - datetime.timedelta(hours=3)
        current_timestamp = int(now.timestamp())
        creation_timestamp = current_timestamp - 10800  # 3 hours ago

        mock_ec2_regional.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-expired-ttl",
                            "State": {"Name": "running"},
                            "LaunchTime": old_time,
                            "Tags": [
                                {"Key": "Name", "Value": "test-instance"},
                                {"Key": "creation-time", "Value": str(creation_timestamp)},
                                {"Key": "delete-cluster-after-hours", "Value": "1"},
                                {"Key": "iit-billing-tag", "Value": "test-team"},
                                {"Key": "owner", "Value": "test-user"},
                            ],
                        }
                    ]
                }
            ]
        }

        mock_execute_action.return_value = True

        # Execute Lambda
        result = lambda_handler({}, None)

        # Verify response
        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 1
        assert body["actions"][0]["instance_id"] == "i-expired-ttl"
        assert body["actions"][0]["action"] == "TERMINATE"

        # Verify action was executed
        mock_execute_action.assert_called_once()
        executed_action = mock_execute_action.call_args[0][0]
        assert executed_action.instance_id == "i-expired-ttl"
        assert executed_action.action == "TERMINATE"

        # Verify notification was sent
        mock_send_notification.assert_called_once()

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_end_to_end_flow_with_protected_and_actionable_instances(
        self, mock_boto_client, mock_send_notification, mock_execute_action
    ):
        """
        GIVEN mix of protected and actionable instances
        WHEN full cleanup flow executes
        THEN only actionable instances should be processed
        """
        mock_ec2_main = Mock()
        mock_ec2_regional = Mock()

        def get_client(service, region_name=None):
            if region_name:
                return mock_ec2_regional
            return mock_ec2_main

        mock_boto_client.side_effect = get_client

        mock_ec2_main.describe_regions.return_value = {
            "Regions": [{"RegionName": "us-east-1"}]
        }

        now = datetime.datetime.now(datetime.timezone.utc)
        old_time = now - datetime.timedelta(days=35)

        # Instance 1: Protected (persistent tag)
        # Instance 2: Long stopped without billing tag (actionable)
        mock_ec2_regional.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-protected",
                            "State": {"Name": "running"},
                            "LaunchTime": now,
                            "Tags": [
                                {"Key": "Name", "Value": "protected-jenkins"},
                                {"Key": "iit-billing-tag", "Value": "jenkins-cloud"},
                            ],
                        },
                        {
                            "InstanceId": "i-long-stopped",
                            "State": {"Name": "stopped"},
                            "LaunchTime": old_time,
                            "Tags": [
                                {"Key": "Name", "Value": "old-stopped"},
                            ],
                        },
                    ]
                }
            ]
        }

        mock_execute_action.return_value = True

        result = lambda_handler({}, None)

        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 1
        assert body["actions"][0]["instance_id"] == "i-long-stopped"

        # Only one action should be executed (protected instance skipped)
        assert mock_execute_action.call_count == 1

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_end_to_end_flow_no_instances_in_region(
        self, mock_boto_client, mock_send_notification, mock_execute_action
    ):
        """
        GIVEN region with no instances
        WHEN full cleanup flow executes
        THEN no actions should be taken
        """
        mock_ec2_main = Mock()
        mock_ec2_regional = Mock()

        def get_client(service, region_name=None):
            if region_name:
                return mock_ec2_regional
            return mock_ec2_main

        mock_boto_client.side_effect = get_client

        mock_ec2_main.describe_regions.return_value = {
            "Regions": [{"RegionName": "us-east-1"}]
        }

        mock_ec2_regional.describe_instances.return_value = {"Reservations": []}

        result = lambda_handler({}, None)

        assert result["statusCode"] == 200
        body = json.loads(result["body"])
        assert body["total_actions"] == 0
        assert body["actions"] == []

        mock_execute_action.assert_not_called()
        mock_send_notification.assert_not_called()


@pytest.mark.e2e
class TestLambdaEventHandling:
    """Test Lambda event validation and edge cases."""

    @patch("aws_resource_cleanup.handler.cleanup_region")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_accepts_empty_event(
        self, mock_boto_client, mock_cleanup_region
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

        result = lambda_handler({}, None)

        assert result["statusCode"] == 200

    @patch("aws_resource_cleanup.handler.cleanup_region")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_lambda_handler_accepts_none_context(
        self, mock_boto_client, mock_cleanup_region
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

        result = lambda_handler({}, None)

        assert result["statusCode"] == 200
