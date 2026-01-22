"""Unit tests for billing tag validation logic.

Tests the has_valid_billing_tag() function.
"""

from __future__ import annotations
import datetime
import pytest

from aws_resource_cleanup.utils import has_valid_billing_tag


@pytest.mark.unit
class TestBillingTagValidation:
    """Test billing tag validation rules."""
    
    def test_valid_category_billing_tag_accepted(self):
        """
        GIVEN a category-based billing tag (e.g., "pmm-staging")
        WHEN has_valid_billing_tag is called
        THEN True should be returned
        """
        tags_dict = {"iit-billing-tag": "pmm-staging"}
        assert has_valid_billing_tag(tags_dict) is True

    def test_future_timestamp_billing_tag_accepted(self):
        """
        GIVEN a future Unix timestamp billing tag
        WHEN has_valid_billing_tag is called
        THEN True should be returned
        """
        # Future timestamp (year 2030)
        future_timestamp = str(int(datetime.datetime(2030, 1, 1).timestamp()))
        tags_dict = {"iit-billing-tag": future_timestamp}
        assert has_valid_billing_tag(tags_dict) is True

    def test_expired_timestamp_billing_tag_rejected(self):
        """
        GIVEN an expired Unix timestamp billing tag
        WHEN has_valid_billing_tag is called
        THEN False should be returned
        """
        # Past timestamp (year 2020)
        past_timestamp = str(int(datetime.datetime(2020, 1, 1).timestamp()))
        tags_dict = {"iit-billing-tag": past_timestamp}
        assert has_valid_billing_tag(tags_dict) is False

    def test_missing_billing_tag_rejected(self):
        """
        GIVEN tags without iit-billing-tag
        WHEN has_valid_billing_tag is called
        THEN False should be returned
        """
        tags_dict = {"Name": "test-instance"}
        assert has_valid_billing_tag(tags_dict) is False

    def test_empty_billing_tag_rejected(self):
        """
        GIVEN an empty billing tag value
        WHEN has_valid_billing_tag is called
        THEN False should be returned
        """
        tags_dict = {"iit-billing-tag": ""}
        assert has_valid_billing_tag(tags_dict) is False


@pytest.mark.unit
@pytest.mark.parametrize(
    "billing_tag",
    [
        "pmm-staging",
        "CirrusCI",
        "eks",
        "openshift",
        "test-team",
        "custom-123",
    ],
)
class TestVariousCategoryTags:
    """Test various category-based billing tags."""
    
    def test_various_category_tags_accepted(self, billing_tag):
        """
        GIVEN various category-based billing tags
        WHEN has_valid_billing_tag is called
        THEN True should be returned for all
        """
        tags_dict = {"iit-billing-tag": billing_tag}
        assert has_valid_billing_tag(tags_dict) is True