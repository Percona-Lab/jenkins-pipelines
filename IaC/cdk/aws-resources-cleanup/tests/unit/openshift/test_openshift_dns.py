"""Unit tests for OpenShift Route53 DNS cleanup."""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch

from aws_resource_cleanup.openshift.dns import cleanup_route53_records


@pytest.mark.unit
@pytest.mark.openshift
class TestCleanupRoute53Records:
    """Test Route53 DNS record cleanup for OpenShift clusters."""

    @patch("aws_resource_cleanup.openshift.dns.boto3.client")
    @patch("aws_resource_cleanup.openshift.dns.OPENSHIFT_BASE_DOMAIN", "cd.percona.com")
    @patch("aws_resource_cleanup.openshift.dns.DRY_RUN", False)
    def test_deletes_cluster_dns_records_live_mode(self, mock_boto_client):
        """
        GIVEN OpenShift cluster DNS records exist in Route53
        WHEN cleanup_route53_records is called in live mode
        THEN matching DNS records should be deleted
        """
        mock_route53 = Mock()
        mock_boto_client.return_value = mock_route53

        mock_route53.list_hosted_zones.return_value = {
            "HostedZones": [{"Id": "/hostedzone/Z123456", "Name": "cd.percona.com."}]
        }

        mock_route53.list_resource_record_sets.return_value = {
            "ResourceRecordSets": [
                {
                    "Name": "api.test-cluster.cd.percona.com.",
                    "Type": "A",
                    "TTL": 300,
                    "ResourceRecords": [{"Value": "1.2.3.4"}],
                },
                {
                    "Name": "*.apps.test-cluster.cd.percona.com.",
                    "Type": "A",
                    "TTL": 300,
                    "ResourceRecords": [{"Value": "5.6.7.8"}],
                },
                {
                    "Name": "other.cd.percona.com.",
                    "Type": "A",
                    "TTL": 300,
                    "ResourceRecords": [{"Value": "9.10.11.12"}],
                },
            ]
        }

        cleanup_route53_records("test-cluster", "us-east-1")

        mock_route53.list_hosted_zones.assert_called_once()
        mock_route53.list_resource_record_sets.assert_called_once_with(
            HostedZoneId="Z123456"
        )

        # Should delete 2 records (api and apps) but not the other one
        call_args = mock_route53.change_resource_record_sets.call_args
        assert call_args is not None
        changes = call_args.kwargs["ChangeBatch"]["Changes"]
        assert len(changes) == 2
        assert all(change["Action"] == "DELETE" for change in changes)

    @patch("aws_resource_cleanup.openshift.dns.boto3.client")
    @patch("aws_resource_cleanup.openshift.dns.OPENSHIFT_BASE_DOMAIN", "cd.percona.com")
    @patch("aws_resource_cleanup.openshift.dns.DRY_RUN", True)
    def test_skips_deletion_in_dry_run_mode(self, mock_boto_client):
        """
        GIVEN OpenShift cluster DNS records exist
        WHEN cleanup_route53_records is called in DRY_RUN mode
        THEN no changes should be made
        """
        mock_route53 = Mock()
        mock_boto_client.return_value = mock_route53

        mock_route53.list_hosted_zones.return_value = {
            "HostedZones": [{"Id": "/hostedzone/Z123", "Name": "cd.percona.com."}]
        }

        mock_route53.list_resource_record_sets.return_value = {
            "ResourceRecordSets": [
                {
                    "Name": "api.test-cluster.cd.percona.com.",
                    "Type": "A",
                    "TTL": 300,
                    "ResourceRecords": [{"Value": "1.2.3.4"}],
                }
            ]
        }

        cleanup_route53_records("test-cluster", "us-east-1")

        mock_route53.change_resource_record_sets.assert_not_called()

    @patch("aws_resource_cleanup.openshift.dns.boto3.client")
    @patch("aws_resource_cleanup.openshift.dns.OPENSHIFT_BASE_DOMAIN", "cd.percona.com")
    def test_handles_missing_hosted_zone(self, mock_boto_client):
        """
        GIVEN hosted zone does not exist
        WHEN cleanup_route53_records is called
        THEN function should return without error
        """
        mock_route53 = Mock()
        mock_boto_client.return_value = mock_route53

        mock_route53.list_hosted_zones.return_value = {
            "HostedZones": [{"Id": "/hostedzone/Z999", "Name": "other-domain.com."}]
        }

        cleanup_route53_records("test-cluster", "us-east-1")

        mock_route53.list_resource_record_sets.assert_not_called()

    @patch("aws_resource_cleanup.openshift.dns.boto3.client")
    @patch("aws_resource_cleanup.openshift.dns.OPENSHIFT_BASE_DOMAIN", "cd.percona.com")
    @patch("aws_resource_cleanup.openshift.dns.DRY_RUN", False)
    def test_handles_no_matching_records(self, mock_boto_client):
        """
        GIVEN no DNS records match the cluster name
        WHEN cleanup_route53_records is called
        THEN no changes should be made
        """
        mock_route53 = Mock()
        mock_boto_client.return_value = mock_route53

        mock_route53.list_hosted_zones.return_value = {
            "HostedZones": [{"Id": "/hostedzone/Z123", "Name": "cd.percona.com."}]
        }

        mock_route53.list_resource_record_sets.return_value = {
            "ResourceRecordSets": [
                {
                    "Name": "other.cd.percona.com.",
                    "Type": "A",
                    "TTL": 300,
                    "ResourceRecords": [{"Value": "1.2.3.4"}],
                }
            ]
        }

        cleanup_route53_records("test-cluster", "us-east-1")

        mock_route53.change_resource_record_sets.assert_not_called()
