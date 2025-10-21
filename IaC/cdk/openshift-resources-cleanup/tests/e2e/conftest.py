"""Fixtures specific to end-to-end tests."""

import pytest


@pytest.fixture(autouse=True)
def _mark_as_e2e(request):
    """Automatically mark all tests in e2e/ as e2e tests."""
    request.node.add_marker(pytest.mark.e2e)