"""Unit tests for OpenShift-specific cluster detection (vs EKS, vanilla K8s)."""

from __future__ import annotations
import pytest
from unittest.mock import Mock, patch

from openshift_resource_cleanup.handler import is_openshift_instance, extract_cluster_name_from_infra_id


class TestOpenShiftDetection:
    """Test that we only detect OpenShift clusters, not EKS or other K8s."""

    def test_detects_rosa_cluster_via_red_hat_clustertype_tag(self):
        """GIVEN an instance with red-hat-clustertype: rosa tag
        WHEN is_openshift_instance is called
        THEN it should detect as OpenShift and return infra_id
        """
        instance = {
            "InstanceId": "i-rosa123",
            "Tags": [
                {"Key": "Name", "Value": "rosa-cluster-abc123-worker-0"},
                {"Key": "red-hat-clustertype", "Value": "rosa"},
                {"Key": "kubernetes.io/cluster/rosa-cluster-abc123", "Value": "owned"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "us-east-1")

        assert is_openshift is True
        assert infra_id == "rosa-cluster-abc123"

    def test_detects_openshift_via_red_hat_managed_tag(self):
        """GIVEN an instance with red-hat-managed: true tag
        WHEN is_openshift_instance is called
        THEN it should detect as OpenShift
        """
        instance = {
            "InstanceId": "i-openshift456",
            "Tags": [
                {"Key": "Name", "Value": "my-openshift-def456-infra-0"},
                {"Key": "red-hat-managed", "Value": "true"},
                {"Key": "kubernetes.io/cluster/my-openshift-def456", "Value": "owned"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "us-east-2")

        assert is_openshift is True
        assert infra_id == "my-openshift-def456"

    def test_detects_openshift_via_cluster_api_tag(self):
        """GIVEN an instance with sigs.k8s.io/cluster-api-provider-aws tag
        WHEN is_openshift_instance is called
        THEN it should detect as OpenShift
        """
        instance = {
            "InstanceId": "i-clusterapi789",
            "Tags": [
                {"Key": "Name", "Value": "ocp-cluster-ghi789-worker-1"},
                {
                    "Key": "sigs.k8s.io/cluster-api-provider-aws/cluster/ocp-cluster-ghi789",
                    "Value": "owned",
                },
                {"Key": "kubernetes.io/cluster/ocp-cluster-ghi789", "Value": "owned"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "eu-west-1")

        assert is_openshift is True
        assert infra_id == "ocp-cluster-ghi789"

    @patch("openshift_resource_cleanup.openshift.detection.detect_openshift_infra_id")
    def test_detects_openshift_via_master_name_pattern_fallback(
        self, mock_detect_infra
    ):
        """GIVEN an instance with -master- in name (old detection method)
        WHEN is_openshift_instance is called
        THEN it should verify with detect_openshift_infra_id and detect if valid
        """
        mock_detect_infra.return_value = "legacy-cluster-jkl012"

        instance = {
            "InstanceId": "i-legacy123",
            "Tags": [
                {"Key": "Name", "Value": "legacy-cluster-jkl012-master-0"},
                {"Key": "kubernetes.io/cluster/legacy-cluster-jkl012", "Value": "owned"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "us-west-2")

        assert is_openshift is True
        assert infra_id == "legacy-cluster-jkl012"
        mock_detect_infra.assert_called_once_with("legacy-cluster", "us-west-2")

    def test_does_not_detect_eks_cluster(self):
        """GIVEN an EKS instance (no Red Hat tags, has eks:cluster-name tag)
        WHEN is_openshift_instance is called
        THEN it should NOT detect as OpenShift
        """
        instance = {
            "InstanceId": "i-eks999",
            "Tags": [
                {"Key": "Name", "Value": "eks-worker-node-1"},
                {"Key": "eks:cluster-name", "Value": "my-eks-cluster"},
                {"Key": "kubernetes.io/cluster/my-eks-cluster", "Value": "owned"},
                {"Key": "eks:nodegroup-name", "Value": "ng-1"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "us-east-1")

        assert is_openshift is False
        assert infra_id is None

    def test_does_not_detect_vanilla_kubernetes(self):
        """GIVEN a vanilla K8s instance (generic kubernetes.io tag only)
        WHEN is_openshift_instance is called
        THEN it should NOT detect as OpenShift
        """
        instance = {
            "InstanceId": "i-k8s888",
            "Tags": [
                {"Key": "Name", "Value": "k8s-worker-01"},
                {"Key": "kubernetes.io/cluster/my-k8s-cluster", "Value": "owned"},
                {"Key": "KubernetesCluster", "Value": "my-k8s-cluster"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "eu-central-1")

        assert is_openshift is False
        assert infra_id is None

    def test_does_not_detect_non_kubernetes_instance(self):
        """GIVEN a regular EC2 instance (no K8s tags at all)
        WHEN is_openshift_instance is called
        THEN it should NOT detect as OpenShift
        """
        instance = {
            "InstanceId": "i-regular777",
            "Tags": [
                {"Key": "Name", "Value": "web-server-01"},
                {"Key": "Environment", "Value": "production"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "us-west-1")

        assert is_openshift is False
        assert infra_id is None

    def test_detects_from_worker_node_not_just_master(self):
        """GIVEN an OpenShift worker node (not master)
        WHEN is_openshift_instance is called
        THEN it should still detect the cluster via Red Hat tags
        """
        instance = {
            "InstanceId": "i-worker555",
            "Tags": [
                {"Key": "Name", "Value": "prod-cluster-xyz999-worker-2"},
                {"Key": "red-hat-clustertype", "Value": "rosa"},
                {"Key": "kubernetes.io/cluster/prod-cluster-xyz999", "Value": "owned"},
                {"Key": "sigs.k8s.io/cluster-api-provider-aws/role", "Value": "worker"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "eu-west-2")

        assert is_openshift is True
        assert infra_id == "prod-cluster-xyz999"

    def test_detects_from_infra_node(self):
        """GIVEN an OpenShift infra node
        WHEN is_openshift_instance is called
        THEN it should detect the cluster via Red Hat tags
        """
        instance = {
            "InstanceId": "i-infra444",
            "Tags": [
                {"Key": "Name", "Value": "prod-cluster-xyz999-infra-0"},
                {"Key": "red-hat-managed", "Value": "true"},
                {"Key": "kubernetes.io/cluster/prod-cluster-xyz999", "Value": "owned"},
            ],
        }

        is_openshift, infra_id = is_openshift_instance(instance, "eu-west-2")

        assert is_openshift is True
        assert infra_id == "prod-cluster-xyz999"


class TestExtractClusterName:
    """Test cluster name extraction from infra ID."""

    def test_extracts_cluster_name_from_infra_id(self):
        """GIVEN an infra ID like 'jvp-rosa1-qmdkk'
        WHEN extract_cluster_name_from_infra_id is called
        THEN it should return 'jvp-rosa1' (without random suffix)
        """
        result = extract_cluster_name_from_infra_id("jvp-rosa1-qmdkk")
        assert result == "jvp-rosa1"

    def test_extracts_cluster_name_from_longer_infra_id(self):
        """GIVEN a longer infra ID with multiple dashes
        WHEN extract_cluster_name_from_infra_id is called
        THEN it should return everything except the last segment
        """
        result = extract_cluster_name_from_infra_id("my-production-cluster-abc123")
        assert result == "my-production-cluster"

    def test_returns_unchanged_if_single_segment(self):
        """GIVEN an infra ID with no dashes
        WHEN extract_cluster_name_from_infra_id is called
        THEN it should return the ID unchanged
        """
        result = extract_cluster_name_from_infra_id("simplecluster")
        assert result == "simplecluster"

    def test_returns_unchanged_if_only_two_segments(self):
        """GIVEN an infra ID with only two segments
        WHEN extract_cluster_name_from_infra_id is called
        THEN it should return the first segment only
        """
        result = extract_cluster_name_from_infra_id("cluster-id")
        assert result == "cluster"
