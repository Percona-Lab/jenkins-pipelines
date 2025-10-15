"""EC2 instance management and cleanup policies."""

from .instances import (
    cirrus_ci_add_iit_billing_tag,
    is_protected,
    execute_cleanup_action,
)
from .policies import (
    check_ttl_expiration,
    check_stop_after_days,
    check_long_stopped,
    check_untagged,
)

__all__ = [
    "cirrus_ci_add_iit_billing_tag",
    "is_protected",
    "execute_cleanup_action",
    "check_ttl_expiration",
    "check_stop_after_days",
    "check_long_stopped",
    "check_untagged",
]
