"""Integration tests for handler orchestration and action execution.

Tests focus on the critical execution paths with AWS mocking:
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


@pytest.mark.integration
@pytest.mark.aws
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


@pytest.mark.integration
@pytest.mark.aws
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
                            "Tags": [{"Key": "Name", "Value": "long-stopped-instance"}],
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


@pytest.mark.integration
@pytest.mark.aws
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


@pytest.mark.integration
@pytest.mark.aws
@pytest.mark.openshift
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


@pytest.mark.integration
@pytest.mark.aws
@pytest.mark.openshift
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


@pytest.mark.integration
@pytest.mark.aws
@patch("aws_resource_cleanup.ec2.instances.boto3.resource")
def test_cirrus_ci_adds_billing_tag_when_missing(mock_boto_resource):
    from aws_resource_cleanup.ec2.instances import cirrus_ci_add_iit_billing_tag

    mock_ec2_resource = MagicMock()
    mock_instance = MagicMock()
    mock_ec2_resource.Instance.return_value = mock_instance
    mock_boto_resource.return_value = mock_ec2_resource

    instance = {
        "InstanceId": "i-ccc123",
        "Placement": {"AvailabilityZone": "us-east-1a"},
    }
    tags_dict = {"CIRRUS_CI": "true", "Name": "ci-runner"}

    cirrus_ci_add_iit_billing_tag(instance, tags_dict)

    mock_boto_resource.assert_called_once_with("ec2", region_name="us-east-1")
    mock_ec2_resource.Instance.assert_called_once_with("i-ccc123")
    mock_instance.create_tags.assert_called_once()
    assert {
        "Key": "iit-billing-tag",
        "Value": "CirrusCI",
    } in mock_instance.create_tags.call_args.kwargs["Tags"]


@pytest.mark.integration
@pytest.mark.aws
@patch("aws_resource_cleanup.ec2.instances.boto3.resource")
def test_cirrus_ci_noop_if_tag_already_present(mock_boto_resource):
    from aws_resource_cleanup.ec2.instances import cirrus_ci_add_iit_billing_tag

    instance = {
        "InstanceId": "i-ccc456",
        "Placement": {"AvailabilityZone": "us-east-1a"},
    }
    tags_dict = {"CIRRUS_CI": "true", "iit-billing-tag": "CirrusCI"}

    cirrus_ci_add_iit_billing_tag(instance, tags_dict)

    mock_boto_resource.assert_not_called()


@pytest.mark.integration
@pytest.mark.aws
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


@pytest.mark.integration
@pytest.mark.aws
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


# ===== Policy Priority Integration Tests =====


@pytest.mark.integration
@pytest.mark.aws
@pytest.mark.policies
class TestPolicyPriorityInOrchestration:
    """Test that cleanup_region respects policy priority order."""

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_ttl_policy_takes_priority_over_untagged(
        self, mock_boto_client, mock_send_notification, mock_execute
    ):
        """
        GIVEN instance with expired TTL AND no billing tag (matches both TTL and untagged)
        WHEN cleanup_region is called
        THEN TTL policy should be applied (not untagged)
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        now = datetime.datetime.now(datetime.timezone.utc)
        two_hours_ago = now - datetime.timedelta(hours=2)

        # Instance with expired TTL (created 2 hours ago, TTL 1 hour) and no billing tag
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-ttl-untagged",
                            "State": {"Name": "running"},
                            "LaunchTime": two_hours_ago,
                            "Tags": [
                                {"Key": "Name", "Value": "test-instance"},
                                {
                                    "Key": "creation-time",
                                    "Value": str(int((two_hours_ago).timestamp())),
                                },
                                {"Key": "delete-cluster-after-hours", "Value": "1"},
                                # No iit-billing-tag
                            ],
                        }
                    ]
                }
            ]
        }

        mock_execute.return_value = True

        actions = cleanup_region("us-east-1")

        assert len(actions) == 1
        action = actions[0]

        # Should use TTL policy, not untagged
        assert "TTL" in action.reason or "expired" in action.reason.lower()
        assert "untagged" not in action.reason.lower()

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_stop_after_days_takes_priority_over_long_stopped(
        self, mock_boto_client, mock_send_notification, mock_execute
    ):
        """
        GIVEN running instance with stop-after-days expired
        WHEN cleanup_region is called
        THEN STOP action should be created (not long-stopped which doesn't apply)
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        now = datetime.datetime.now(datetime.timezone.utc)
        eight_days_ago = now - datetime.timedelta(days=8)

        # Running instance for 8 days with stop-after-days=7
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-stop-after",
                            "State": {"Name": "running"},
                            "LaunchTime": eight_days_ago,
                            "Tags": [
                                {"Key": "Name", "Value": "pmm-staging"},
                                {"Key": "iit-billing-tag", "Value": "pmm-staging"},
                                {"Key": "stop-after-days", "Value": "7"},
                            ],
                        }
                    ]
                }
            ]
        }

        mock_execute.return_value = True

        actions = cleanup_region("us-east-1")

        assert len(actions) == 1
        action = actions[0]

        # Should be STOP action (not TERMINATE from long-stopped)
        assert action.action == "STOP"
        assert "stop" in action.reason.lower()

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_long_stopped_takes_priority_over_untagged(
        self, mock_boto_client, mock_send_notification, mock_execute
    ):
        """
        GIVEN stopped instance for 35 days without billing tag
        WHEN cleanup_region is called
        THEN long-stopped policy should be applied (not untagged)
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        now = datetime.datetime.now(datetime.timezone.utc)
        thirty_five_days_ago = now - datetime.timedelta(days=35)

        # Stopped for 35 days, no billing tag
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-long-stopped",
                            "State": {"Name": "stopped"},
                            "LaunchTime": thirty_five_days_ago,
                            "Tags": [
                                {"Key": "Name", "Value": "old-stopped"},
                                # No billing tag
                            ],
                        }
                    ]
                }
            ]
        }

        mock_execute.return_value = True

        actions = cleanup_region("us-east-1")

        assert len(actions) == 1
        action = actions[0]

        # Should use long-stopped policy
        assert "stopped" in action.reason.lower()
        assert "30 days" in action.reason or "long" in action.reason.lower()

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_multiple_instances_with_different_policies(
        self, mock_boto_client, mock_send_notification, mock_execute
    ):
        """
        GIVEN multiple instances matching different policies
        WHEN cleanup_region is called
        THEN each instance should get correct policy based on priority
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        now = datetime.datetime.now(datetime.timezone.utc)

        # Three instances with different policy matches
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        # Instance 1: TTL expired
                        {
                            "InstanceId": "i-ttl",
                            "State": {"Name": "running"},
                            "LaunchTime": now - datetime.timedelta(hours=2),
                            "Tags": [
                                {"Key": "Name", "Value": "ttl-instance"},
                                {
                                    "Key": "creation-time",
                                    "Value": str(
                                        int(
                                            (
                                                now - datetime.timedelta(hours=2)
                                            ).timestamp()
                                        )
                                    ),
                                },
                                {"Key": "delete-cluster-after-hours", "Value": "1"},
                                {"Key": "iit-billing-tag", "Value": "test"},
                            ],
                        },
                        # Instance 2: Long stopped (no billing tag, stopped for 35 days)
                        {
                            "InstanceId": "i-stopped",
                            "State": {"Name": "stopped"},
                            "LaunchTime": now - datetime.timedelta(days=35),
                            "Tags": [
                                {"Key": "Name", "Value": "long-stopped"},
                                # No billing tag - will trigger long-stopped policy
                            ],
                        },
                        # Instance 3: Untagged
                        {
                            "InstanceId": "i-untagged",
                            "State": {"Name": "running"},
                            "LaunchTime": now - datetime.timedelta(hours=2),
                            "Tags": [{"Key": "Name", "Value": "untagged"}],
                        },
                    ]
                }
            ]
        }

        mock_execute.return_value = True

        actions = cleanup_region("us-east-1")

        assert len(actions) == 3

        # Verify each action has correct policy applied
        actions_by_id = {action.instance_id: action for action in actions}

        # TTL instance should have TTL reason
        ttl_action = actions_by_id["i-ttl"]
        assert "TTL" in ttl_action.reason or "expired" in ttl_action.reason.lower()

        # Stopped instance should have long-stopped reason
        stopped_action = actions_by_id["i-stopped"]
        assert "stopped" in stopped_action.reason.lower()

        # Untagged instance should have missing billing tag reason
        untagged_action = actions_by_id["i-untagged"]
        assert (
            "missing" in untagged_action.reason.lower()
            or "billing tag" in untagged_action.reason.lower()
        )

    @patch("aws_resource_cleanup.handler.execute_cleanup_action")
    @patch("aws_resource_cleanup.handler.send_notification")
    @patch("aws_resource_cleanup.handler.boto3.client")
    def test_reordered_policies_would_fail_this_test(
        self, mock_boto_client, mock_send_notification, mock_execute
    ):
        """
        GIVEN instance matching both TTL and untagged policies
        WHEN cleanup_region is called
        THEN if policies were reordered, this test would catch it

        This test documents that policy order matters and must be maintained.
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2

        now = datetime.datetime.now(datetime.timezone.utc)
        two_hours_ago = now - datetime.timedelta(hours=2)

        # Instance with expired TTL and no billing tag
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-dual-match",
                            "State": {"Name": "running"},
                            "LaunchTime": two_hours_ago,
                            "Tags": [
                                {"Key": "Name", "Value": "test"},
                                {
                                    "Key": "creation-time",
                                    "Value": str(int(two_hours_ago.timestamp())),
                                },
                                {"Key": "delete-cluster-after-hours", "Value": "1"},
                                # No billing tag - matches untagged policy too
                            ],
                        }
                    ]
                }
            ]
        }

        mock_execute.return_value = True

        actions = cleanup_region("us-east-1")

        # The action MUST be from TTL policy (first in priority)
        # If someone reorders the policies in handler.py (lines 96-100),
        # this test will fail, alerting them to the priority requirement
        assert len(actions) == 1
        action = actions[0]

        # Explicit check: must NOT be untagged reason
        assert "untagged" not in action.reason.lower(), (
            "TTL policy should take priority over untagged policy. "
            "If this fails, check that check_ttl_expiration is called "
            "before check_untagged in handler.py cleanup_region()"
        )
