"""Integration tests for cleanup_region TTL validation logic.

These tests verify that the Lambda handler correctly evaluates cluster TTLs
before marking clusters for deletion. They should FAIL initially, proving
they catch the bug where all clusters are marked for deletion regardless of TTL.
"""

from __future__ import annotations
import datetime
import pytest
from unittest.mock import Mock, patch, MagicMock
from freezegun import freeze_time

from openshift_resource_cleanup.handler import cleanup_region
from openshift_resource_cleanup.models import CleanupAction


@pytest.mark.integration
@pytest.mark.openshift
class TestCleanupRegionTTLValidation:
    """Test TTL validation in cleanup_region function."""

    @freeze_time("2025-01-15 12:00:00")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    @patch("openshift_resource_cleanup.openshift.detection.detect_openshift_infra_id")
    @patch("openshift_resource_cleanup.handler.execute_cleanup_action")
    @patch("openshift_resource_cleanup.handler.DRY_RUN", True)
    def test_cluster_with_non_expired_ttl_should_not_delete(
        self, mock_execute, mock_detect_infra, mock_boto_client
    ):
        """
        GIVEN an OpenShift cluster with non-expired TTL
        WHEN cleanup_region is called
        THEN NO cleanup action should be created
        """
        # Cluster created at 2025-01-15 05:00:00 (7 hours ago)
        # TTL: 14 hours (delete-cluster-after-hours=14)
        # Time remaining: 7 hours - should NOT delete
        creation_time = "1736917200"  # Unix timestamp for 2025-01-15 05:00:00 UTC
        ttl_hours = "14"

        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-0123456789abcdef0",
                            "State": {"Name": "running"},
                            "Tags": [
                                {"Key": "Name", "Value": "jvp-rosa1-abc12-master-0"},
                                {"Key": "iit-billing-tag", "Value": "pmm"},
                                {"Key": "owner", "Value": "john.doe@percona.com"},
                                {"Key": "creation-time", "Value": creation_time},
                                {"Key": "delete-cluster-after-hours", "Value": ttl_hours},
                                {"Key": "red-hat-clustertype", "Value": "rosa"},
                                {
                                    "Key": "kubernetes.io/cluster/jvp-rosa1-abc12",
                                    "Value": "owned",
                                },
                            ],
                        }
                    ]
                }
            ]
        }

        # Mock OpenShift detection
        mock_detect_infra.return_value = "jvp-rosa1-abc12"

        actions = cleanup_region("us-east-1", "test-exec-123")

        # ❌ This test should FAIL initially because current code marks ALL clusters for deletion
        # After fix, this should PASS with actions == []
        assert len(actions) == 0, (
            f"Expected NO actions for non-expired TTL cluster, but got {len(actions)} actions. "
            f"Cluster created at {creation_time}, TTL is {ttl_hours} hours, "
            f"only 7 hours have passed. Should have 7 hours remaining."
        )

    @freeze_time("2025-01-15 12:00:00")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    @patch("openshift_resource_cleanup.openshift.detection.detect_openshift_infra_id")
    @patch("openshift_resource_cleanup.handler.execute_cleanup_action")
    @patch("openshift_resource_cleanup.handler.DRY_RUN", True)
    def test_cluster_with_expired_ttl_should_delete(
        self, mock_execute, mock_detect_infra, mock_boto_client
    ):
        """
        GIVEN an OpenShift cluster with expired TTL
        WHEN cleanup_region is called
        THEN a TERMINATE_OPENSHIFT_CLUSTER action should be created
        """
        # Cluster created at 2025-01-14 20:00:00 (16 hours ago)
        # TTL: 14 hours (delete-cluster-after-hours=14)
        # Time expired: 2 hours ago - SHOULD delete
        creation_time = "1736884800"  # Unix timestamp for 2025-01-14 20:00:00 UTC
        ttl_hours = "14"

        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-expired123456789",
                            "State": {"Name": "running"},
                            "Tags": [
                                {"Key": "Name", "Value": "expired-cluster-xyz45-master-0"},
                                {"Key": "iit-billing-tag", "Value": "pmm"},
                                {"Key": "owner", "Value": "jane.smith@percona.com"},
                                {"Key": "creation-time", "Value": creation_time},
                                {"Key": "delete-cluster-after-hours", "Value": ttl_hours},
                                {"Key": "red-hat-clustertype", "Value": "rosa"},
                                {
                                    "Key": "kubernetes.io/cluster/expired-cluster-xyz45",
                                    "Value": "owned",
                                },
                            ],
                        }
                    ]
                }
            ]
        }

        mock_detect_infra.return_value = "expired-cluster-xyz45"

        actions = cleanup_region("us-east-1", "test-exec-123")

        # ✅ This test should PASS even with current buggy code (it deletes everything)
        # After fix, this should still PASS
        assert len(actions) == 1
        assert actions[0].action == "TERMINATE_OPENSHIFT_CLUSTER"
        assert actions[0].cluster_name == "expired-cluster"
        assert actions[0].days_overdue > 0, (
            f"Expected days_overdue > 0 for expired cluster, but got {actions[0].days_overdue}"
        )

    @freeze_time("2025-01-15 12:00:00")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    @patch("openshift_resource_cleanup.openshift.detection.detect_openshift_infra_id")
    @patch("openshift_resource_cleanup.handler.execute_cleanup_action")
    @patch("openshift_resource_cleanup.handler.DRY_RUN", True)
    def test_cluster_without_ttl_tags_should_delete(
        self, mock_execute, mock_detect_infra, mock_boto_client
    ):
        """
        GIVEN an OpenShift cluster without TTL tags (unmanaged/forgotten)
        WHEN cleanup_region is called
        THEN a TERMINATE action should be created (cleanup old infrastructure)
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-no-ttl-tags-123",
                            "State": {"Name": "running"},
                            "Tags": [
                                {"Key": "Name", "Value": "no-ttl-cluster-def67-master-0"},
                                {"Key": "iit-billing-tag", "Value": "pmm"},
                                {"Key": "owner", "Value": "bob.jones@percona.com"},
                                # NO creation-time or delete-cluster-after-hours tags
                                {"Key": "red-hat-clustertype", "Value": "rosa"},
                                {
                                    "Key": "kubernetes.io/cluster/no-ttl-cluster-def67",
                                    "Value": "owned",
                                },
                            ],
                        }
                    ]
                }
            ]
        }

        mock_detect_infra.return_value = "no-ttl-cluster-def67"

        actions = cleanup_region("us-east-1", "test-exec-123")

        # ✅ This test should PASS even with current buggy code (it deletes everything)
        # After fix, this should still PASS (delete unmanaged clusters)
        assert len(actions) == 1, (
            f"Expected 1 action for cluster without TTL tags (unmanaged infrastructure), "
            f"but got {len(actions)} actions"
        )
        assert actions[0].action == "TERMINATE_OPENSHIFT_CLUSTER"
        assert actions[0].cluster_name == "no-ttl-cluster"

    @freeze_time("2025-01-15 12:00:00")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    @patch("openshift_resource_cleanup.openshift.detection.detect_openshift_infra_id")
    @patch("openshift_resource_cleanup.handler.execute_cleanup_action")
    @patch("openshift_resource_cleanup.handler.DRY_RUN", True)
    def test_cluster_with_malformed_ttl_should_not_delete(
        self, mock_execute, mock_detect_infra, mock_boto_client
    ):
        """
        GIVEN an OpenShift cluster with malformed TTL tags
        WHEN cleanup_region is called
        THEN NO cleanup action should be created (fail safe)
        """
        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-malformed-ttl-456",
                            "State": {"Name": "running"},
                            "Tags": [
                                {"Key": "Name", "Value": "malformed-ttl-ghi89-master-0"},
                                {"Key": "iit-billing-tag", "Value": "pmm"},
                                {"Key": "creation-time", "Value": "invalid-date"},
                                {"Key": "delete-cluster-after-hours", "Value": "not-a-number"},
                                {"Key": "red-hat-clustertype", "Value": "rosa"},
                                {
                                    "Key": "kubernetes.io/cluster/malformed-ttl-ghi89",
                                    "Value": "owned",
                                },
                            ],
                        }
                    ]
                }
            ]
        }

        mock_detect_infra.return_value = "malformed-ttl-ghi89"

        actions = cleanup_region("us-east-1", "test-exec-123")

        # ❌ This test should FAIL initially
        # After fix, this should PASS with actions == [] (fail-safe behavior)
        assert len(actions) == 0, (
            f"Expected NO actions for cluster with malformed TTL tags (fail-safe), "
            f"but got {len(actions)} actions"
        )

    @freeze_time("2025-01-15 12:00:00")
    @patch("openshift_resource_cleanup.handler.boto3.client")
    @patch("openshift_resource_cleanup.openshift.detection.detect_openshift_infra_id")
    @patch("openshift_resource_cleanup.handler.execute_cleanup_action")
    @patch("openshift_resource_cleanup.handler.DRY_RUN", True)
    def test_cluster_exactly_at_ttl_expiry_should_delete(
        self, mock_execute, mock_detect_infra, mock_boto_client
    ):
        """
        GIVEN an OpenShift cluster exactly at TTL expiry time
        WHEN cleanup_region is called
        THEN a TERMINATE action should be created (boundary case)
        """
        # Cluster created at 2025-01-14 22:00:00 (14 hours ago)
        # TTL: 14 hours
        # Time expired: exactly 0 hours - SHOULD delete
        creation_time = "1736892000"  # Unix timestamp for 2025-01-14 22:00:00 UTC
        ttl_hours = "14"

        mock_ec2 = Mock()
        mock_boto_client.return_value = mock_ec2
        mock_ec2.describe_instances.return_value = {
            "Reservations": [
                {
                    "Instances": [
                        {
                            "InstanceId": "i-boundary-case-789",
                            "State": {"Name": "running"},
                            "Tags": [
                                {"Key": "Name", "Value": "boundary-jkl01-master-0"},
                                {"Key": "iit-billing-tag", "Value": "pmm"},
                                {"Key": "creation-time", "Value": creation_time},
                                {"Key": "delete-cluster-after-hours", "Value": ttl_hours},
                                {"Key": "red-hat-clustertype", "Value": "rosa"},
                                {
                                    "Key": "kubernetes.io/cluster/boundary-jkl01",
                                    "Value": "owned",
                                },
                            ],
                        }
                    ]
                }
            ]
        }

        mock_detect_infra.return_value = "boundary-jkl01"

        actions = cleanup_region("us-east-1", "test-exec-123")

        # After fix, should create deletion action at exact expiry
        assert len(actions) == 1
        assert actions[0].action == "TERMINATE_OPENSHIFT_CLUSTER"
        assert actions[0].days_overdue >= 0
