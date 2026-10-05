"""Jenkins command for the Oracle CPU/CSPU poll.

The job configuration calls this file. Flags belong to the pipeline in
check_oracle_cpu.groovy.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from oracle_cpu.diagnostics import append_note, write_status
from oracle_cpu.pipeline import poll
from oracle_cpu.state import ack_state


def main(argv: list[str] | None = None) -> None:
    if argv is None:
        argv = sys.argv[1:]
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    if argv and argv[0] == "ack":
        parser = argparse.ArgumentParser(prog="cpu_cves.py ack")
        parser.add_argument("state")
        parser.add_argument("--pending", default="")
        parser.add_argument("--thread", nargs=2, metavar=("SLUG", "JSON"))
        args = parser.parse_args(argv[1:])
        thread = None
        slug = ""
        if args.thread:
            slug = args.thread[0]
            thread = json.loads(Path(args.thread[1]).read_text(encoding="utf-8"))
            if not isinstance(thread, dict):
                raise SystemExit("thread JSON must be an object")
        if not args.pending and not slug:
            raise SystemExit("ack needs --pending or --thread")
        ack_state(Path(args.state), args.pending, slug, thread)
        return
    if argv and argv[0] == "note":
        if len(argv) != 3:
            raise SystemExit("usage: cpu_cves.py note REPORT JSON_FILE")
        raw = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise SystemExit("note JSON must be an object")
        append_note(Path(argv[1]), raw)
        return
    if argv and argv[0] == "status":
        if len(argv) != 6:
            raise SystemExit(
                "usage: cpu_cves.py status REPORT STATE STATUS_OUT "
                "DESCRIPTION_OUT RESULT"
            )
        write_status(Path(argv[1]), Path(argv[2]), Path(argv[3]), Path(argv[4]), argv[5])
        return
    parser = argparse.ArgumentParser(description="Diff Oracle CPU/CSPU CVE sets")
    parser.add_argument("--state", required=True, help="cpu-state.json path")
    parser.add_argument("--bugs", required=True, help="bug id to CVE list JSON")
    parser.add_argument("--notify-manifest", required=True, help="Slack post list JSON")
    parser.add_argument("--report", required=True, help="cpu-run.json path")
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument(
        "--ignore-state",
        action="store_true",
        help="Compare CVE sets as empty. Keep bug maps, pending messages, and threads.",
    )
    parser.add_argument(
        "--notify",
        choices=("none", "latest", "all"),
        default="none",
    )
    args = parser.parse_args(argv)
    usable = poll(
        Path(args.state),
        Path(args.bugs),
        Path(args.notify_manifest),
        Path(args.report),
        count=args.count,
        ignore_state=args.ignore_state,
        notify_mode=args.notify,
    )
    if not usable:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
