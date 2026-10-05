# Copied from mysql-sandbox ps_notify/cpu_cves.py.
# That file is the source of truth until mysql-sandbox is merged to trunk.
# Re-copy from there when the fetcher or the state JSON shape changes.

"""Fetch Oracle CPU/CSPU pages and diff CVE sets.

No sqlite and no Jenkins. ps-notify's cpu provider and the Jenkins
check-oracle-cpu job both use this module. The Jenkins tree keeps a copy.
The implementation is the oracle_cpu package next to this file.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from oracle_cpu import (
    ack_pending,
    append_event,
    apply_state,
    bug_map_from_csaf,
    cve_sha,
    describe_change,
    exception_text,
    fetch,
    format_bodies,
    merge_pending,
    notification_items,
    parse_cves,
    render_status,
    save_delivery,
    stored_advisory,
    write_notify_dir,
    write_run,
    write_status,
)

__all__ = [
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


def main(argv: list[str] | None = None) -> None:
    if argv is None:
        argv = sys.argv[1:]
    if argv and argv[0] == "ack":
        if len(argv) != 3:
            raise SystemExit("usage: cpu_cves.py ack SLACK_STATE PENDING_ID")
        logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
        ack_pending(Path(argv[1]), argv[2])
        return
    if argv and argv[0] == "event":
        append_event(argv)
        return
    if argv and argv[0] == "status":
        if len(argv) != 7:
            raise SystemExit(
                "usage: cpu_cves.py status EVENTS RUN SLACK_STATE "
                "STATUS_OUT DESCRIPTION_OUT RESULT"
            )
        write_status(
            Path(argv[1]),
            Path(argv[2]),
            Path(argv[3]),
            Path(argv[4]),
            Path(argv[5]),
            argv[6],
        )
        return
    parser = argparse.ArgumentParser(description="Diff Oracle CPU/CSPU CVE sets")
    parser.add_argument("--state", required=True, help="cpu-cves.json path")
    parser.add_argument("--diff", required=True, help="diff JSON path")
    parser.add_argument(
        "--bugs",
        required=True,
        help="bug id to CVE list JSON, one object for every fetched advisory",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=10,
        help="How many newest CPU/CSPU pages to read. Jenkins uses 10.",
    )
    parser.add_argument(
        "--ignore-state",
        action="store_true",
        help="Do not read the saved advisory state",
    )
    parser.add_argument(
        "--notify",
        choices=("none", "latest", "all"),
        default="none",
        help="Also post unchanged advisories: newest only, or every one",
    )
    parser.add_argument(
        "--notify-dir",
        required=True,
        help="Directory of per-advisory Slack texts",
    )
    parser.add_argument(
        "--slack-state",
        required=True,
        help="cpu-slack.json: thread ids and messages not yet confirmed",
    )
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    write_run(
        Path(args.state),
        Path(args.diff),
        Path(args.bugs),
        Path(args.notify_dir),
        Path(args.slack_state),
        count=args.count,
        ignore_state=args.ignore_state,
        notify_mode=args.notify,
    )


if __name__ == "__main__":
    main()
