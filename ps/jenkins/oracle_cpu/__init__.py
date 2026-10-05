"""Oracle CPU/CSPU fetch, diff, and Jenkins state.

core fetches and parses. pipeline owns the saved baseline and the pending
Slack queue. diagnostics turns notes into the status text.
"""

from oracle_cpu.core import (
    PARSER_VERSION,
    bug_map_from_csaf,
    cve_sha,
    describe_change,
    fetch,
    format_bodies,
    parse_cves,
)
from oracle_cpu.diagnostics import (
    append_event,
    exception_text,
    render_status,
    write_status,
)
from oracle_cpu.pipeline import (
    ack_pending,
    apply_state,
    merge_pending,
    notification_items,
    save_delivery,
    stored_advisory,
    write_notify_dir,
    write_run,
)

__all__ = [
    "PARSER_VERSION",
    "ack_pending",
    "append_event",
    "apply_state",
    "bug_map_from_csaf",
    "cve_sha",
    "describe_change",
    "exception_text",
    "fetch",
    "format_bodies",
    "merge_pending",
    "notification_items",
    "parse_cves",
    "render_status",
    "save_delivery",
    "stored_advisory",
    "write_notify_dir",
    "write_run",
    "write_status",
]
