"""Unit tests for tag format conversion utilities.

Tests the convert_tags_to_dict() function.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.utils import convert_tags_to_dict


@pytest.mark.unit
class TestTagConversion:
    """Test AWS tag list to dictionary conversion."""
    
    def test_convert_tags_list_to_dict(self):
        """
        GIVEN AWS tags list format
        WHEN convert_tags_to_dict is called
        THEN dictionary should be returned
        """
        tags_list = [
            {"Key": "Name", "Value": "test-instance"},
            {"Key": "iit-billing-tag", "Value": "pmm-staging"},
            {"Key": "owner", "Value": "test-user"},
        ]
        
        tags_dict = convert_tags_to_dict(tags_list)
        
        assert tags_dict == {
            "Name": "test-instance",
            "iit-billing-tag": "pmm-staging",
            "owner": "test-user",
        }

    def test_convert_empty_tags_list(self):
        """
        GIVEN empty tags list
        WHEN convert_tags_to_dict is called
        THEN empty dictionary should be returned
        """
        tags_dict = convert_tags_to_dict([])
        assert tags_dict == {}

    def test_convert_none_tags(self):
        """
        GIVEN None as tags
        WHEN convert_tags_to_dict is called
        THEN empty dictionary should be returned
        """
        tags_dict = convert_tags_to_dict(None)
        assert tags_dict == {}