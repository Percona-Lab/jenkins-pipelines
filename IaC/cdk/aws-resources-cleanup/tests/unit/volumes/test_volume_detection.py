"""Unit tests for EBS volume cleanup detection logic.

Tests the check_unattached_volume() and is_volume_protected() functions.
"""

from __future__ import annotations
import pytest
import datetime

from aws_resource_cleanup.ec2.volumes import check_unattached_volume, is_volume_protected


@pytest.mark.unit
@pytest.mark.volumes
class TestVolumeDetection:
    """Test volume cleanup detection logic."""

    def test_available_volume_with_name_tag_creates_delete_action(
        self, volume_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an available (unattached) volume with Name tag
        WHEN check_unattached_volume is called
        THEN a DELETE_VOLUME action should be returned
        """
        create_time = datetime.datetime.fromtimestamp(
            current_time - 86400,  # 1 day old
            tz=datetime.timezone.utc
        )
        volume = (
            volume_builder
            .with_name("test-volume")
            .with_state("available")
            .with_create_time(create_time)
            .with_billing_tag("test-billing")
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        action = check_unattached_volume(volume, tags_dict, current_time)

        assert action is not None
        assert action.action == "DELETE_VOLUME"
        assert action.volume_id == "vol-test123456"
        assert action.name == "test-volume"
        assert action.billing_tag == "test-billing"
        assert action.resource_type == "volume"
        assert 0.9 < action.days_overdue < 1.1  # ~1 day old

    def test_in_use_volume_returns_none(
        self, volume_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a volume in 'in-use' state (attached)
        WHEN check_unattached_volume is called
        THEN None should be returned (not eligible for cleanup)
        """
        volume = (
            volume_builder
            .with_name("attached-volume")
            .with_state("in-use")
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        action = check_unattached_volume(volume, tags_dict, current_time)

        assert action is None

    def test_volume_without_name_tag_returns_none(
        self, volume_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an available volume without Name tag
        WHEN check_unattached_volume is called
        THEN None should be returned (legacy filter requirement)
        """
        volume = volume_builder.with_state("available").build()
        # Manually remove Name tag if it was added
        volume["Tags"] = [tag for tag in volume.get("Tags", []) if tag["Key"] != "Name"]
        tags_dict = tags_dict_from_instance(volume)

        action = check_unattached_volume(volume, tags_dict, current_time)

        assert action is None


@pytest.mark.unit
@pytest.mark.volumes
class TestVolumeProtection:
    """Test volume protection mechanisms."""

    def test_volume_with_do_not_remove_in_name_is_protected(
        self, volume_builder, tags_dict_from_instance
    ):
        """
        GIVEN a volume with 'do not remove' in Name tag
        WHEN is_volume_protected is called
        THEN True should be returned
        """
        volume = volume_builder.with_name("jenkins-data, do not remove").build()
        tags_dict = tags_dict_from_instance(volume)

        assert is_volume_protected(tags_dict) is True

    def test_volume_with_percona_keep_tag_is_protected(
        self, volume_builder, tags_dict_from_instance
    ):
        """
        GIVEN a volume with PerconaKeep tag
        WHEN is_volume_protected is called
        THEN True should be returned
        """
        volume = (
            volume_builder
            .with_name("prod-volume")
            .with_tag("PerconaKeep", "true")
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        assert is_volume_protected(tags_dict) is True

    def test_volume_with_persistent_billing_tag_is_protected(
        self, volume_builder, tags_dict_from_instance
    ):
        """
        GIVEN a volume with persistent billing tag (e.g., jenkins-*)
        WHEN is_volume_protected is called
        THEN True should be returned
        """
        volume = (
            volume_builder
            .with_name("jenkins-volume")
            .with_billing_tag("jenkins-dev-pmm")
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        assert is_volume_protected(tags_dict) is True

    def test_volume_with_valid_billing_tag_is_protected(
        self, volume_builder, tags_dict_from_instance
    ):
        """
        GIVEN a volume with valid billing tag
        WHEN is_volume_protected is called
        THEN True should be returned
        """
        volume = (
            volume_builder
            .with_name("pmm-volume")
            .with_billing_tag("pmm-staging")
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        assert is_volume_protected(tags_dict) is True

    def test_unprotected_volume_returns_false(
        self, volume_builder, tags_dict_from_instance
    ):
        """
        GIVEN a volume without protection mechanisms
        WHEN is_volume_protected is called
        THEN False should be returned
        """
        volume = (
            volume_builder
            .with_name("unprotected-volume")
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        assert is_volume_protected(tags_dict) is False


@pytest.mark.unit
@pytest.mark.volumes
class TestVolumeDetectionIntegration:
    """Test complete volume detection flow."""

    def test_protected_available_volume_returns_none(
        self, volume_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an available volume that is protected
        WHEN check_unattached_volume is called
        THEN None should be returned (protected from deletion)
        """
        volume = (
            volume_builder
            .with_name("jenkins-data, do not remove")
            .with_state("available")
            .with_billing_tag("jenkins-ps80")
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        action = check_unattached_volume(volume, tags_dict, current_time)

        assert action is None

    def test_volume_age_calculation(
        self, volume_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN a volume created 7 days ago
        WHEN check_unattached_volume is called
        THEN days_overdue should be approximately 7
        """
        create_time = datetime.datetime.fromtimestamp(
            current_time - (7 * 86400),  # 7 days ago
            tz=datetime.timezone.utc
        )
        volume = (
            volume_builder
            .with_name("old-volume")
            .with_state("available")
            .with_create_time(create_time)
            .build()
        )
        tags_dict = tags_dict_from_instance(volume)

        action = check_unattached_volume(volume, tags_dict, current_time)

        assert action is not None
        assert 6.9 < action.days_overdue < 7.1

    def test_volume_cleanup_action_contains_volume_metadata(
        self, volume_builder, current_time, tags_dict_from_instance
    ):
        """
        GIVEN an available volume with size and type metadata
        WHEN check_unattached_volume is called
        THEN the reason should include volume size and type
        """
        volume = (
            volume_builder
            .with_name("large-volume")
            .with_state("available")
            .with_size(500)  # 500GB
            .build()
        )
        volume["VolumeType"] = "io2"  # High-performance volume
        tags_dict = tags_dict_from_instance(volume)

        action = check_unattached_volume(volume, tags_dict, current_time)

        assert action is not None
        assert "500GB" in action.reason
        assert "io2" in action.reason
