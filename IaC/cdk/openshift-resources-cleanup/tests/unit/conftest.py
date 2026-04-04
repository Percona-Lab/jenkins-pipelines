"""Fixtures specific to unit tests."""

import pytest


@pytest.fixture(autouse=True)
def _mark_as_unit(request):
    """Automatically mark all tests in unit/ as unit tests."""
    request.node.add_marker(pytest.mark.unit)