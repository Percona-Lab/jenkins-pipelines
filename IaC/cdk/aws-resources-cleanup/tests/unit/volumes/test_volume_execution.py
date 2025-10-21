"""Unit tests for EBS volume deletion execution logic.

Tests the delete_volume() function with mocked AWS calls.
"""

from __future__ import annotations
import pytest
from unittest.mock import MagicMock, patch
from botocore.exceptions import ClientError

from aws_resource_cleanup.ec2.volumes import delete_volume
from aws_resource_cleanup.models import CleanupAction


@pytest.fixture
def volume_action():
    """Fixture for a volume cleanup action."""
    return CleanupAction(
        instance_id="",
        region="us-east-2",
        name="test-volume",
        action="DELETE_VOLUME",
        reason="Unattached volume (10GB gp3, created 2025-01-01, 5.2 days old)",
        days_overdue=5.2,
        billing_tag="test-billing",
        resource_type="volume",
        volume_id="vol-test123456"
    )


@pytest.mark.unit
@pytest.mark.volumes
class TestVolumeExecution:
    """Test volume deletion execution."""

    @patch("aws_resource_cleanup.ec2.volumes.DRY_RUN", False)
    @patch("aws_resource_cleanup.ec2.volumes.boto3")
    def test_successful_volume_deletion(self, mock_boto3, volume_action):
        """
        GIVEN a valid volume deletion action
        WHEN delete_volume is called in LIVE mode
        THEN the volume should be deleted successfully
        """
        # Mock EC2 client
        mock_ec2 = MagicMock()
        mock_boto3.client.return_value = mock_ec2

        # Mock describe_volumes response
        mock_ec2.describe_volumes.return_value = {
            "Volumes": [{
                "VolumeId": "vol-test123456",
                "State": "available",
                "Tags": [{"Key": "Name", "Value": "test-volume"}]
            }]
        }

        result = delete_volume(volume_action, "us-east-2")

        assert result is True
        mock_ec2.delete_volume.assert_called_once_with(VolumeId="vol-test123456")

    @patch("aws_resource_cleanup.ec2.volumes.DRY_RUN", True)
    @patch("aws_resource_cleanup.ec2.volumes.boto3")
    def test_dry_run_mode_logs_without_deleting(self, mock_boto3, volume_action):
        """
        GIVEN a volume deletion action
        WHEN delete_volume is called in DRY_RUN mode
        THEN no actual deletion should occur
        """
        mock_ec2 = MagicMock()
        mock_boto3.client.return_value = mock_ec2

        result = delete_volume(volume_action, "us-east-2")

        assert result is True
        mock_ec2.delete_volume.assert_not_called()

    @patch("aws_resource_cleanup.ec2.volumes.DRY_RUN", False)
    @patch("aws_resource_cleanup.ec2.volumes.boto3")
    def test_volume_in_use_skips_deletion(self, mock_boto3, volume_action):
        """
        GIVEN a volume that is now in-use
        WHEN delete_volume is called
        THEN deletion should be skipped with warning
        """
        mock_ec2 = MagicMock()
        mock_boto3.client.return_value = mock_ec2

        # Mock volume changed to in-use state
        mock_ec2.describe_volumes.return_value = {
            "Volumes": [{
                "VolumeId": "vol-test123456",
                "State": "in-use",  # Changed state
                "Tags": [{"Key": "Name", "Value": "test-volume"}]
            }]
        }

        result = delete_volume(volume_action, "us-east-2")

        assert result is False
        mock_ec2.delete_volume.assert_not_called()

    @patch("aws_resource_cleanup.ec2.volumes.DRY_RUN", False)
    @patch("aws_resource_cleanup.ec2.volumes.boto3")
    def test_volume_not_found_returns_false(self, mock_boto3, volume_action):
        """
        GIVEN a volume that no longer exists
        WHEN delete_volume is called
        THEN False should be returned (already deleted)
        """
        mock_ec2 = MagicMock()
        mock_boto3.client.return_value = mock_ec2

        # Mock volume not found
        mock_ec2.describe_volumes.return_value = {"Volumes": []}

        result = delete_volume(volume_action, "us-east-2")

        assert result is False
        mock_ec2.delete_volume.assert_not_called()

    @patch("aws_resource_cleanup.ec2.volumes.DRY_RUN", False)
    @patch("aws_resource_cleanup.ec2.volumes.boto3")
    def test_volume_deletion_handles_client_error(self, mock_boto3, volume_action):
        """
        GIVEN a volume deletion that fails with ClientError
        WHEN delete_volume is called
        THEN False should be returned and error logged
        """
        mock_ec2 = MagicMock()
        mock_boto3.client.return_value = mock_ec2

        # Mock successful describe but failed delete
        mock_ec2.describe_volumes.return_value = {
            "Volumes": [{
                "VolumeId": "vol-test123456",
                "State": "available",
                "Tags": [{"Key": "Name", "Value": "test-volume"}]
            }]
        }

        # Mock delete failure
        error_response = {"Error": {"Code": "VolumeInUse", "Message": "Volume is in use"}}
        mock_ec2.delete_volume.side_effect = ClientError(error_response, "DeleteVolume")

        result = delete_volume(volume_action, "us-east-2")

        assert result is False

    @patch("aws_resource_cleanup.ec2.volumes.DRY_RUN", False)
    @patch("aws_resource_cleanup.ec2.volumes.boto3")
    def test_volume_protection_check_before_deletion(self, mock_boto3, volume_action):
        """
        GIVEN a volume that became protected after action was created
        WHEN delete_volume is called
        THEN deletion should be skipped
        """
        mock_ec2 = MagicMock()
        mock_boto3.client.return_value = mock_ec2

        # Mock volume now has protection
        mock_ec2.describe_volumes.return_value = {
            "Volumes": [{
                "VolumeId": "vol-test123456",
                "State": "available",
                "Tags": [
                    {"Key": "Name", "Value": "test-volume, do not remove"},  # Protected
                    {"Key": "iit-billing-tag", "Value": "test-billing"}
                ]
            }]
        }

        result = delete_volume(volume_action, "us-east-2")

        assert result is False
        mock_ec2.delete_volume.assert_not_called()

    def test_delete_volume_without_volume_id_returns_false(self):
        """
        GIVEN an action without volume_id
        WHEN delete_volume is called
        THEN False should be returned immediately
        """
        action_without_id = CleanupAction(
            instance_id="",
            region="us-east-2",
            name="test",
            action="DELETE_VOLUME",
            reason="test",
            days_overdue=1.0,
            resource_type="volume",
            volume_id=None  # Missing volume_id
        )

        result = delete_volume(action_without_id, "us-east-2")

        assert result is False
