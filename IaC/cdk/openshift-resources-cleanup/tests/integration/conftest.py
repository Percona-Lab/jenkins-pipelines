"""Fixtures specific to integration tests."""

import pytest
from unittest.mock import Mock


@pytest.fixture(autouse=True)
def _mark_as_integration(request):
    """Automatically mark all tests in integration/ as integration tests."""
    request.node.add_marker(pytest.mark.integration)


@pytest.fixture
def mock_ec2_client():
    """Factory for creating mock EC2 clients with common behaviors.
    
    Example:
        ec2 = mock_ec2_client(
            describe_instances_response={
                "Reservations": [{"Instances": [instance_data]}]
            }
        )
    """
    def _create_mock(**kwargs):
        mock = Mock()
        mock.describe_instances.return_value = kwargs.get(
            'describe_instances_response',
            {"Reservations": []}
        )
        mock.terminate_instances.return_value = kwargs.get(
            'terminate_response',
            {}
        )
        mock.stop_instances.return_value = kwargs.get(
            'stop_response',
            {}
        )
        mock.describe_regions.return_value = kwargs.get(
            'describe_regions_response',
            {"Regions": [{"RegionName": "us-east-1"}]}
        )
        return mock
    return _create_mock


@pytest.fixture
def mock_sns_client():
    """Factory for creating mock SNS clients.
    
    Example:
        sns = mock_sns_client(
            publish_response={'MessageId': 'test-id'}
        )
    """
    def _create_mock(**kwargs):
        mock = Mock()
        mock.publish.return_value = kwargs.get(
            'publish_response',
            {'MessageId': 'test-message-id'}
        )
        return mock
    return _create_mock


@pytest.fixture
def mock_cloudformation_client():
    """Factory for creating mock CloudFormation clients.
    
    Example:
        cfn = mock_cloudformation_client()
    """
    def _create_mock(**kwargs):
        mock = Mock()
        mock.delete_stack.return_value = kwargs.get(
            'delete_stack_response',
            {}
        )
        mock.describe_stacks.return_value = kwargs.get(
            'describe_stacks_response',
            {'Stacks': []}
        )
        return mock
    return _create_mock