"""Unit tests for cluster name extraction logic.

Tests the extract_cluster_name() function.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.utils import extract_cluster_name


@pytest.mark.unit
@pytest.mark.aws
class TestClusterNameExtraction:
    """Test cluster name extraction from various tag formats."""
    
    def test_extract_kubernetes_cluster_name_from_tag(self):
        """
        GIVEN tags with kubernetes.io/cluster/<name> tag
        WHEN extract_cluster_name is called
        THEN cluster name should be extracted
        """
        tags_dict = {"kubernetes.io/cluster/test-eks-cluster": "owned"}
        
        cluster_name = extract_cluster_name(tags_dict)
        
        assert cluster_name == "test-eks-cluster"

    def test_extract_eks_cluster_name_from_aws_tag(self):
        """
        GIVEN tags with aws:eks:cluster-name tag
        WHEN extract_cluster_name is called
        THEN cluster name should be extracted
        """
        tags_dict = {"aws:eks:cluster-name": "my-eks-cluster"}
        
        cluster_name = extract_cluster_name(tags_dict)
        
        assert cluster_name == "my-eks-cluster"

    def test_extract_openshift_cluster_name(self):
        """
        GIVEN tags with OpenShift kubernetes tag
        WHEN extract_cluster_name is called
        THEN infra ID should be extracted as cluster name
        """
        tags_dict = {"kubernetes.io/cluster/openshift-infra-abc123": "owned"}
        
        cluster_name = extract_cluster_name(tags_dict)
        
        assert cluster_name == "openshift-infra-abc123"

    def test_no_cluster_name_returns_none(self):
        """
        GIVEN tags without cluster identifiers
        WHEN extract_cluster_name is called
        THEN None should be returned
        """
        tags_dict = {"Name": "standalone-instance", "iit-billing-tag": "test"}
        
        cluster_name = extract_cluster_name(tags_dict)
        
        assert cluster_name is None

    def test_kubernetes_tag_takes_precedence_over_aws_tag(self):
        """
        GIVEN tags with both kubernetes.io and aws:eks tags
        WHEN extract_cluster_name is called
        THEN kubernetes.io cluster name should be returned
        """
        tags_dict = {
            "kubernetes.io/cluster/k8s-cluster": "owned",
            "aws:eks:cluster-name": "eks-cluster",
        }
        
        cluster_name = extract_cluster_name(tags_dict)
        
        # kubernetes.io tag is checked first in the function
        assert cluster_name == "k8s-cluster"