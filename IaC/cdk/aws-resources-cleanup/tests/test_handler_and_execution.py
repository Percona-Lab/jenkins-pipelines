"""Unit tests for handler orchestration and action execution.

Tests focus on the critical execution paths:
- Action execution (execute_cleanup_action)
- Region cleanup orchestration (cleanup_region)
- Error handling
"""

from __future__ import annotations
import datetime
import pytest
from unittest.mock import Mock, patch, MagicMock
from botocore.exceptions import ClientError

from aws_resource_cleanup.models import CleanupAction
from aws_resource_cleanup.ec2.instances import execute_cleanup_action
from aws_resource_cleanup.handler import cleanup_region


class TestExecuteCleanupAction:
    """Test action execution for all action types."""

    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", False)
    def test_terminate_action_live_mode(self, mock_boto_client):
        """
        GIVEN a TERMINATE action in live mode
        WHEN execute_cleanup_action is called
        THEN EC2 terminate_instances should be called
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        action = CleanupAction(
            instance_id="i-test123",
            region="us-east-1",
            name="test-instance",
            action="TERMINATE",
            reason="TTL expired",
            days_overdue=2.5,
            billing_tag="test-tag",
        )

        result = execute_cleanup_action(action, "us-east-1")

        assert result is True
        mock_boto_client.assert_called_once_with("ec2", region_name="us-east-1")
        mock_ec2.terminate_instances.assert_called_once_with(InstanceIds=["i-test123"])

    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", True)
    def test_terminate_action_dry_run_mode(self, mock_boto_client):
        """
        GIVEN a TERMINATE action in DRY_RUN mode
        WHEN execute_cleanup_action is called
        THEN no AWS API calls should be made
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        action = CleanupAction(
            instance_id="i-test456",
            region="us-east-1",
            name="test-instance",
            action="TERMINATE",
            reason="Untagged",
            days_overdue=1.0,
        )

        result = execute_cleanup_action(action, "us-east-1")

        assert result is True
        mock_boto_client.assert_called_once_with("ec2", region_name="us-east-1")
        mock_ec2.terminate_instances.assert_not_called()

    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", False)
    def test_stop_action_live_mode(self, mock_boto_client):
        """
        GIVEN a STOP action in live mode
        WHEN execute_cleanup_action is called
        THEN EC2 stop_instances should be called
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        action = CleanupAction(
            instance_id="i-test789",
            region="us-west-2",
            name="test-instance",
            action="STOP",
            reason="stop-after-days policy",
            days_overdue=1.0,
            billing_tag="pmm-staging",
        )

        result = execute_cleanup_action(action, "us-west-2")

        assert result is True
        mock_boto_client.assert_called_once_with("ec2", region_name="us-west-2")
        mock_ec2.stop_instances.assert_called_once_with(InstanceIds=["i-test789"])

    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", True)
    def test_stop_action_dry_run_mode(self, mock_boto_client):
        """
        GIVEN a STOP action in DRY_RUN mode
        WHEN execute_cleanup_action is called
        THEN no AWS API calls should be made
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        action = CleanupAction(
            instance_id="i-test101",
            region="us-west-2",
            name="test-instance",
            action="STOP",
            reason="stop-after-days",
            days_overdue=0.5,
        )

        result = execute_cleanup_action(action, "us-west-2")

        assert result is True
        mock_ec2.stop_instances.assert_not_called()

    @patch("aws_resource_cleanup.ec2.instances.delete_eks_cluster_stack")
    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", False)
    def test_terminate_cluster_action_live_mode(
        self, mock_boto_client, mock_delete_stack
    ):
        """
        GIVEN a TERMINATE_CLUSTER action for EKS in live mode
        WHEN execute_cleanup_action is called
        THEN CloudFormation stack deletion and instance termination should occur
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_delete_stack.return_value = True

        action = CleanupAction(
            instance_id="i-eks123",
            region="us-east-1",
            name="eks-node",
            action="TERMINATE_CLUSTER",
            reason="TTL expired",
            days_overdue=3.0,
            billing_tag="eks",
            cluster_name="test-eks-cluster",
        )

        result = execute_cleanup_action(action, "us-east-1")

        assert result is True
        mock_delete_stack.assert_called_once_with("test-eks-cluster", "us-east-1")
        mock_ec2.terminate_instances.assert_called_once_with(InstanceIds=["i-eks123"])

    @patch("aws_resource_cleanup.ec2.instances.delete_eks_cluster_stack")
    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", True)
    def test_terminate_cluster_action_dry_run_mode(
        self, mock_boto_client, mock_delete_stack
    ):
        """
        GIVEN a TERMINATE_CLUSTER action in DRY_RUN mode
        WHEN execute_cleanup_action is called
        THEN no actual deletions should occur
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        action = CleanupAction(
            instance_id="i-eks456",
            region="us-east-1",
            name="eks-node",
            action="TERMINATE_CLUSTER",
            reason="TTL expired",
            days_overdue=2.0,
            cluster_name="test-eks-cluster",
        )

        result = execute_cleanup_action(action, "us-east-1")

        assert result is True
        mock_delete_stack.assert_not_called()
        mock_ec2.terminate_instances.assert_not_called()

    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    def test_terminate_cluster_without_cluster_name_fails(self, mock_boto_client):
        """
        GIVEN a TERMINATE_CLUSTER action without cluster_name
        WHEN execute_cleanup_action is called
        THEN it should return False (invalid action)
        """
        action = CleanupAction(
            instance_id="i-invalid",
            region="us-east-1",
            name="eks-node",
            action="TERMINATE_CLUSTER",
            reason="TTL expired",
            days_overdue=1.0,
            cluster_name=None,  # Missing!
        )

        result = execute_cleanup_action(action, "us-east-1")

        assert result is False

    @patch("aws_resource_cleanup.ec2.instances.destroy_openshift_cluster")
    @patch("aws_resource_cleanup.ec2.instances.detect_openshift_infra_id")
    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", False)
    @patch("aws_resource_cleanup.models.config.OPENSHIFT_CLEANUP_ENABLED", True)
    def test_terminate_openshift_cluster_action_live_mode(
        self, mock_boto_client, mock_detect_infra, mock_destroy_cluster
    ):
        """
        GIVEN a TERMINATE_OPENSHIFT_CLUSTER action in live mode
        WHEN execute_cleanup_action is called
        THEN OpenShift cleanup and instance termination should occur
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_detect_infra.return_value = "openshift-infra-abc123"

        action = CleanupAction(
            instance_id="i-openshift123",
            region="us-east-2",
            name="openshift-master",
            action="TERMINATE_OPENSHIFT_CLUSTER",
            reason="TTL expired",
            days_overdue=4.0,
            billing_tag="openshift",
            cluster_name="test-openshift",
        )

        result = execute_cleanup_action(action, "us-east-2")

        assert result is True
        mock_detect_infra.assert_called_once_with("test-openshift", "us-east-2")
        mock_destroy_cluster.assert_called_once_with(
            "test-openshift", "openshift-infra-abc123", "us-east-2"
        )
        mock_ec2.terminate_instances.assert_called_once_with(
            InstanceIds=["i-openshift123"]
        )

    @patch("aws_resource_cleanup.ec2.instances.detect_openshift_infra_id")
    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", True)
    @patch("aws_resource_cleanup.models.config.OPENSHIFT_CLEANUP_ENABLED", True)
    def test_terminate_openshift_cluster_action_dry_run_mode(
        self, mock_boto_client, mock_detect_infra
    ):
        """
        GIVEN a TERMINATE_OPENSHIFT_CLUSTER action in DRY_RUN mode
        WHEN execute_cleanup_action is called
        THEN no actual deletions should occur
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_detect_infra.return_value = "openshift-infra-xyz"

        action = CleanupAction(
            instance_id="i-openshift456",
            region="us-east-2",
            name="openshift-master",
            action="TERMINATE_OPENSHIFT_CLUSTER",
            reason="TTL expired",
            days_overdue=3.5,
            cluster_name="test-openshift",
        )

        result = execute_cleanup_action(action, "us-east-2")

        assert result is True
        mock_detect_infra.assert_called_once_with("test-openshift", "us-east-2")
        mock_ec2.terminate_instances.assert_not_called()

    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    def test_terminate_openshift_without_cluster_name_fails(self, mock_boto_client):
        """
        GIVEN a TERMINATE_OPENSHIFT_CLUSTER action without cluster_name
        WHEN execute_cleanup_action is called
        THEN it should return False
        """
        action = CleanupAction(
            instance_id="i-invalid",
            region="us-east-2",
            name="openshift-master",
            action="TERMINATE_OPENSHIFT_CLUSTER",
            reason="TTL expired",
            days_overdue=1.0,
            cluster_name=None,
        )

        result = execute_cleanup_action(action, "us-east-2")

        assert result is False

    @patch("aws_resource_cleanup.ec2.instances.boto3.client")
    @patch("aws_resource_cleanup.ec2.instances.DRY_RUN", False)
    def test_execute_action_handles_client_error(self, mock_boto_client):
        """
        GIVEN a valid action that triggers ClientError
        WHEN execute_cleanup_action is called
        THEN it should catch the error and return False
        """
        mock_ec2 = Mock()
        mock_ec2.terminate_instances.side_effect = ClientError(
            {"Error": {"Code": "InvalidInstanceID.NotFound", "Message": "Not found"}},
            "TerminateInstances",
        )
        mock_boto_client.return_value = mock_ec2

        action = CleanupAction(
            instance_id="i-notfound",
            region="us-east-1",
            name="test",
            action="TERMINATE",
            reason="Test",
            days_overdue=1.0,
        )

        result = execute_cleanup_action(action, "us-east-1")

        assert result is False


class TestCleanupRegion:
    """Test region cleanup orchestration."""

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_cleanup_region_happy_path(
        self, mock_boto_client, mock_send_notification, mock_execute
    ):
        """
        GIVEN a region with instances that match cleanup policies
        WHEN cleanup_region is called
        THEN actions should be created and executed
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        # Mock EC2 response with instances
        now = datetime.datetime.now(datetime.timezone.utc)
        old_time = now - datetime.timedelta(days=35)

        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-stopped-old",
                            "State": {"Name": "stopped"},
                            "LaunchTime": old_time,
                            "Tags": [
                                {"Key": "Name", "Value": "long-stopped-instance"}
                            ],
                        }
                    ]
                }
            ]
        }

        mock_execute.return_value = True

        actions = cleanup_region("us-east-1")

        assert len(actions) == 1
        assert actions[0].instance_id == "i-stopped-old"
        assert actions[0].action == "TERMINATE"
        assert actions[0].region == "us-east-1"

        mock_execute.assert_called_once()
        mock_send_notification.assert_called_once()

    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_cleanup_region_no_instances(self, mock_boto_client):
        """
        GIVEN a region with no instances
        WHEN cleanup_region is called
        THEN no actions should be created
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_instances.return_value = {"Reservations": []}

        actions = cleanup_region("us-west-2")

        assert len(actions) == 0

    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_cleanup_region_protected_instances_skipped(self, mock_boto_client):
        """
        GIVEN a region with only protected instances
        WHEN cleanup_region is called
        THEN no actions should be created
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        now = datetime.datetime.now(datetime.timezone.utc)
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-protected",
                            "State": {"Name": "running"},
                            "LaunchTime": now,
                            "Tags": [
                                {"Key": "Name", "Value": "protected-instance"},
                                {"Key": "iit-billing-tag", "Value": "jenkins-cloud"},
                            ],
                        }
                    ]
                }
            ]
        }

        actions = cleanup_region("us-east-1")

        assert len(actions) == 0

    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_cleanup_region_handles_exceptions(self, mock_boto_client):
        """
        GIVEN an EC2 API error during describe_instances
        WHEN cleanup_region is called
        THEN it should handle the error gracefully and return empty list
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_instances.side_effect = ClientError(
            {"Error": {"Code": "RequestLimitExceeded", "Message": "Rate limit"}},
            "DescribeInstances",
        )

        actions = cleanup_region("us-east-1")

        assert actions == []


# ---- Additional unit tests for edge cases and helpers ----
from unittest.mock import Mock, patch, MagicMock


@patch("aws_resource_cleanup.ec2.instances.boto3.client")
def test_unknown_action_returns_false(mock_boto_client):
    from aws_resource_cleanup.models import CleanupAction
    from aws_resource_cleanup.ec2.instances import execute_cleanup_action

    action = CleanupAction(
        instance_id="i-unknown",
        region="us-east-1",
        name="test-instance",
        action="PAUSE",
        reason="Invalid",
        days_overdue=0.0,
    )

    result = execute_cleanup_action(action, "us-east-1")
    assert result is False
    ec2 = mock_boto_client.return_value
    ec2.terminate_instances.assert_not_called()
    ec2.stop_instances.assert_not_called()


@patch("aws_resource_cleanup.ec2.instances.boto3.client")
@patch("aws_resource_cleanup.models.config.OPENSHIFT_CLEANUP_ENABLED", False)
@patch("aws_resource_cleanup.ec2.instances.DRY_RUN", False)
def test_terminate_openshift_cleanup_disabled_returns_true_no_calls(mock_boto_client):
    from aws_resource_cleanup.models import CleanupAction
    from aws_resource_cleanup.ec2.instances import execute_cleanup_action

    action = CleanupAction(
        instance_id="i-openshift-disabled",
        region="us-east-2",
        name="openshift-master",
        action="TERMINATE_OPENSHIFT_CLUSTER",
        reason="TTL expired",
        days_overdue=1.0,
        cluster_name="test-openshift",
    )

    result = execute_cleanup_action(action, "us-east-2")
    assert result is True
    ec2 = mock_boto_client.return_value
    ec2.terminate_instances.assert_not_called()


@patch("aws_resource_cleanup.ec2.instances.destroy_openshift_cluster")
@patch("aws_resource_cleanup.ec2.instances.detect_openshift_infra_id")
@patch("aws_resource_cleanup.ec2.instances.boto3.client")
@patch("aws_resource_cleanup.ec2.instances.DRY_RUN", False)
@patch("aws_resource_cleanup.models.config.OPENSHIFT_CLEANUP_ENABLED", True)
def test_terminate_openshift_infra_missing_still_terminates_instance(
    mock_boto_client, mock_detect_infra, mock_destroy_cluster
):
    from aws_resource_cleanup.models import CleanupAction
    from aws_resource_cleanup.ec2.instances import execute_cleanup_action

    mock_detect_infra.return_value = None
    action = CleanupAction(
        instance_id="i-openshift-novpc",
        region="us-east-2",
        name="openshift-master",
        action="TERMINATE_OPENSHIFT_CLUSTER",
        reason="TTL expired",
        days_overdue=2.0,
        billing_tag="openshift",
        cluster_name="test-openshift",
    )

    result = execute_cleanup_action(action, "us-east-2")
    assert result is True
    mock_detect_infra.assert_called_once_with("test-openshift", "us-east-2")
    mock_destroy_cluster.assert_not_called()
    ec2 = mock_boto_client.return_value
    ec2.terminate_instances.assert_called_once_with(InstanceIds=["i-openshift-novpc"])


@patch("aws_resource_cleanup.ec2.instances.boto3.resource")
def test_cirrus_ci_adds_billing_tag_when_missing(mock_boto_resource):
    from aws_resource_cleanup.ec2.instances import cirrus_ci_add_iit_billing_tag

    mock_ec2_resource = MagicMock()
    mock_instance = MagicMock()
    mock_ec2_resource.Instance.return_value = mock_instance
    mock_boto_resource.return_value = mock_ec2_resource

    instance = {"InstanceId": "i-ccc123", "Placement": {"AvailabilityZone": "us-east-1a"}}
    tags_dict = {"CIRRUS_CI": "true", "Name": "ci-runner"}

    cirrus_ci_add_iit_billing_tag(instance, tags_dict)

    mock_boto_resource.assert_called_once_with("ec2", region_name="us-east-1")
    mock_ec2_resource.Instance.assert_called_once_with("i-ccc123")
    mock_instance.create_tags.assert_called_once()
    assert {"Key": "iit-billing-tag", "Value": "CirrusCI"} in         mock_instance.create_tags.call_args.kwargs["Tags"]


@patch("aws_resource_cleanup.ec2.instances.boto3.resource")
def test_cirrus_ci_noop_if_tag_already_present(mock_boto_resource):
    from aws_resource_cleanup.ec2.instances import cirrus_ci_add_iit_billing_tag

    instance = {"InstanceId": "i-ccc456", "Placement": {"AvailabilityZone": "us-east-1a"}}
    tags_dict = {"CIRRUS_CI": "true", "iit-billing-tag": "CirrusCI"}

    cirrus_ci_add_iit_billing_tag(instance, tags_dict)

    mock_boto_resource.assert_not_called()


@patch("aws_resource_cleanup.handler.boto3.client")
def test_send_notification_publishes_when_topic_set(mock_boto_client):
    from aws_resource_cleanup.handler import send_notification
    from aws_resource_cleanup.models import CleanupAction
    import aws_resource_cleanup.handler as handler_mod

    mock_sns = Mock()
    mock_boto_client.return_value = mock_sns

    actions = [
        CleanupAction(
            instance_id="i-1",
            region="us-east-1",
            name="one",
            action="TERMINATE",
            reason="test",
            days_overdue=1.2,
            billing_tag="x",
        )
    ]

    original_topic = handler_mod.SNS_TOPIC_ARN
    try:
        handler_mod.SNS_TOPIC_ARN = "arn:aws:sns:us-east-1:123456789012:Topic"
        send_notification(actions, "us-east-1")
    finally:
        handler_mod.SNS_TOPIC_ARN = original_topic

    assert mock_sns.publish.called
    kwargs = mock_sns.publish.call_args.kwargs
    assert len(kwargs["Subject"]) <= 100
    assert "Action:" in kwargs["Message"]


@patch("aws_resource_cleanup.handler.boto3.client")
def test_send_notification_skips_when_no_topic(mock_boto_client):
    from aws_resource_cleanup.handler import send_notification
    import aws_resource_cleanup.handler as handler_mod

    actions = []
    original_topic = handler_mod.SNS_TOPIC_ARN
    try:
        handler_mod.SNS_TOPIC_ARN = ""
        send_notification(actions, "us-east-1")
    finally:
        handler_mod.SNS_TOPIC_ARN = original_topic

    mock_boto_client.assert_not_called()
