"""Unit tests for resource protection detection logic.

Tests the is_protected() function and protection rules without AWS mocking.
"""

from __future__ import annotations
import pytest

from aws_resource_cleanup.ec2.instances import is_protected


@pytest.mark.unit
@pytest.mark.policies
class TestBasicProtection:
    """Test basic protection detection."""
    
    def test_instance_with_persistent_billing_tag_is_protected(
        self, make_instance, tags_dict_from_instance
    ):
        """
        GIVEN an instance with a persistent billing tag
        WHEN is_protected is called
        THEN True should be returned (instance is protected)
        """
        instance = make_instance(
            name="protected",
            billing_tag="jenkins-dev-pmm"
        )
        tags_dict = tags_dict_from_instance(instance)

        is_protected_flag, reason = is_protected(tags_dict, "i-test123")
        assert is_protected_flag is True
        assert "jenkins-dev-pmm" in reason

    def test_instance_with_valid_billing_tag_is_protected(
        self, make_instance, tags_dict_from_instance
    ):
        """
        GIVEN an instance with valid non-persistent billing tag
        WHEN is_protected is called
        THEN True should be returned (protected unless TTL overrides)
        """
        instance = make_instance(billing_tag="pmm-staging")
        tags_dict = tags_dict_from_instance(instance)

        is_protected_flag, reason = is_protected(tags_dict, "i-test123")
        assert is_protected_flag is True
        assert "pmm-staging" in reason

    def test_untagged_instance_is_not_protected(
        self, make_instance, tags_dict_from_instance
    ):
        """
        GIVEN an instance without any billing tag
        WHEN is_protected is called
        THEN False should be returned
        """
        instance = make_instance(name="untagged")
        tags_dict = tags_dict_from_instance(instance)

        is_protected_flag, reason = is_protected(tags_dict, "i-test123")
        assert is_protected_flag is False
        assert reason == ""

    def test_instance_with_invalid_billing_tag_not_protected(
        self, make_instance, tags_dict_from_instance
    ):
        """
        GIVEN an instance with expired timestamp billing tag
        WHEN is_protected is called
        THEN False should be returned
        """
        # Expired timestamp (past date)
        expired_timestamp = "1000000"  # Very old timestamp
        instance = make_instance(billing_tag=expired_timestamp)
        tags_dict = tags_dict_from_instance(instance)

        # Note: is_protected uses has_valid_billing_tag which checks timestamps
        # An expired timestamp should not protect the instance
        is_protected_flag, reason = is_protected(tags_dict, "i-test123")
        assert is_protected_flag is False


@pytest.mark.unit
@pytest.mark.policies
class TestPersistentTags:
    """Test all persistent billing tags are properly protected."""
    
    @pytest.mark.parametrize(
        "persistent_tag",
        [
            "jenkins-cloud",
            "jenkins-fb",
            "jenkins-pg",
            "jenkins-ps3",
            "jenkins-ps57",
            "jenkins-ps80",
            "jenkins-psmdb",
            "jenkins-pxb",
            "jenkins-pxc",
            "jenkins-rel",
            "pmm-dev",
        ],
    )
    def test_all_persistent_tags_are_protected(
        self, make_instance, tags_dict_from_instance, persistent_tag
    ):
        """
        GIVEN an instance with any persistent billing tag
        WHEN is_protected is called
        THEN True should be returned
        """
        instance = make_instance(billing_tag=persistent_tag)
        tags_dict = tags_dict_from_instance(instance)

        is_protected_flag, reason = is_protected(tags_dict, "i-test123")
        assert is_protected_flag is True
        assert persistent_tag in reason


@pytest.mark.unit
@pytest.mark.policies
class TestProtectionOverrides:
    """Test TTL and stop-after-days override protection."""
    
    def test_instance_with_billing_tag_and_ttl_is_not_protected(
        self, make_instance, tags_dict_from_instance
    ):
        """
        GIVEN an instance with billing tag but also TTL tags
        WHEN is_protected is called
        THEN False should be returned (TTL takes precedence)
        """
        instance = make_instance(
            billing_tag="pmm-staging",
            ttl_expired=True,
            ttl_hours=1
        )
        tags_dict = tags_dict_from_instance(instance)

        is_protected_flag, reason = is_protected(tags_dict, "i-test123")
        assert is_protected_flag is False

    def test_instance_with_billing_tag_and_stop_policy_not_protected(
        self, make_instance, tags_dict_from_instance
    ):
        """
        GIVEN an instance with billing tag but also stop-after-days
        WHEN is_protected is called
        THEN False should be returned (stop policy takes precedence)
        """
        instance = make_instance(
            billing_tag="pmm-staging",
            stop_after_days=7
        )
        tags_dict = tags_dict_from_instance(instance)

        is_protected_flag, reason = is_protected(tags_dict, "i-test123")
        assert is_protected_flag is False