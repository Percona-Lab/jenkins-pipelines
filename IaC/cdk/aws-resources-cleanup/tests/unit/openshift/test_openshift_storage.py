"""Unit tests for OpenShift S3 state storage cleanup."""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch
from botocore.exceptions import ClientError

from aws_resource_cleanup.openshift.storage import cleanup_s3_state


@pytest.mark.unit
@pytest.mark.openshift
class TestCleanupS3State:
    """Test S3 state bucket cleanup for OpenShift clusters."""

    @patch("aws_resource_cleanup.openshift.storage.boto3.client")
    @patch("aws_resource_cleanup.openshift.storage.DRY_RUN", False)
    def test_deletes_s3_objects_live_mode(self, mock_boto_client):
        """
        GIVEN S3 objects exist for OpenShift cluster
        WHEN cleanup_s3_state is called in live mode
        THEN all cluster objects should be deleted
        """
        mock_s3 = Mock()
        mock_sts = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "s3":
                return mock_s3
            elif service_name == "sts":
                return mock_sts
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_sts.get_caller_identity.return_value = {"Account": "123456789012"}

        mock_s3.list_objects_v2.return_value = {
            "Contents": [
                {"Key": "test-cluster/terraform.tfstate"},
                {"Key": "test-cluster/metadata.json"},
            ]
        }

        cleanup_s3_state("test-cluster", "us-east-1")

        expected_bucket = "openshift-clusters-123456789012-us-east-1"
        mock_s3.list_objects_v2.assert_called_once_with(
            Bucket=expected_bucket, Prefix="test-cluster/"
        )

        assert mock_s3.delete_object.call_count == 2
        mock_s3.delete_object.assert_any_call(
            Bucket=expected_bucket, Key="test-cluster/terraform.tfstate"
        )
        mock_s3.delete_object.assert_any_call(
            Bucket=expected_bucket, Key="test-cluster/metadata.json"
        )

    @patch("aws_resource_cleanup.openshift.storage.boto3.client")
    @patch("aws_resource_cleanup.openshift.storage.DRY_RUN", True)
    def test_skips_deletion_in_dry_run_mode(self, mock_boto_client):
        """
        GIVEN S3 objects exist for cluster
        WHEN cleanup_s3_state is called in DRY_RUN mode
        THEN no deletions should occur
        """
        mock_s3 = Mock()
        mock_sts = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "s3":
                return mock_s3
            elif service_name == "sts":
                return mock_sts
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_sts.get_caller_identity.return_value = {"Account": "123456789012"}
        mock_s3.list_objects_v2.return_value = {
            "Contents": [{"Key": "test-cluster/terraform.tfstate"}]
        }

        cleanup_s3_state("test-cluster", "us-east-1")

        mock_s3.delete_object.assert_not_called()

    @patch("aws_resource_cleanup.openshift.storage.boto3.client")
    @patch("aws_resource_cleanup.openshift.storage.DRY_RUN", False)
    def test_handles_no_contents_in_bucket(self, mock_boto_client):
        """
        GIVEN no S3 objects exist for cluster
        WHEN cleanup_s3_state is called
        THEN function should complete without errors
        """
        mock_s3 = Mock()
        mock_sts = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "s3":
                return mock_s3
            elif service_name == "sts":
                return mock_sts
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_sts.get_caller_identity.return_value = {"Account": "123456789012"}
        mock_s3.list_objects_v2.return_value = {}  # No Contents key

        cleanup_s3_state("test-cluster", "us-east-1")

        mock_s3.delete_object.assert_not_called()

    @patch("aws_resource_cleanup.openshift.storage.boto3.client")
    @patch("aws_resource_cleanup.openshift.storage.DRY_RUN", False)
    def test_handles_missing_bucket_gracefully(self, mock_boto_client):
        """
        GIVEN S3 bucket does not exist
        WHEN cleanup_s3_state is called
        THEN NoSuchBucket error should be handled gracefully
        """
        mock_s3 = Mock()
        mock_sts = Mock()

        def client_factory(service_name, **kwargs):
            if service_name == "s3":
                return mock_s3
            elif service_name == "sts":
                return mock_sts
            return Mock()

        mock_boto_client.side_effect = client_factory

        mock_sts.get_caller_identity.return_value = {"Account": "123456789012"}
        mock_s3.list_objects_v2.side_effect = ClientError(
            {"Error": {"Code": "NoSuchBucket"}}, "ListObjectsV2"
        )

        # Should not raise exception
        cleanup_s3_state("test-cluster", "us-east-1")

        mock_s3.delete_object.assert_not_called()
