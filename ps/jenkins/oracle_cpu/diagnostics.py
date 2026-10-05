"""Issue records, status text, and diagnostic files."""

from __future__ import annotations

import json
import logging
import traceback
import urllib.error
from pathlib import Path
from typing import Any

log = logging.getLogger("ps_notify")

def exception_text(exc: BaseException) -> str:
    """Exception chain and stack traces.

    traceback walks __cause__ and __context__. URLError.reason is a
    separate object, so it is printed first when it is itself an exception.
    """
    parts: list[str] = []
    reason = getattr(exc, "reason", None)
    if isinstance(exc, urllib.error.URLError) and isinstance(reason, BaseException):
        parts.append(
            "URLError.reason:\n"
            + "".join(
                traceback.format_exception(
                    type(reason), reason, reason.__traceback__, chain=True
                )
            )
        )
    elif isinstance(exc, urllib.error.URLError) and reason is not None:
        parts.append(f"URLError.reason: {reason}\n")
    parts.append(
        "".join(
            traceback.format_exception(type(exc), exc, exc.__traceback__, chain=True)
        )
    )
    return "\n".join(parts).rstrip() + "\n"


def issue(
    *,
    level: str,
    outcome: str,
    area: str,
    message: str,
    slug: str = "",
    attempts: int = 0,
    fallback: str = "",
    impact: str = "",
    exception: str = "",
) -> dict[str, Any]:
    return {
        "level": level,
        "outcome": outcome,
        "area": area,
        "slug": slug,
        "attempts": attempts,
        "message": message,
        "fallback": fallback,
        "impact": impact,
        "exception": exception,
    }

def warning_lines(notes: list[dict[str, Any]]) -> list[str]:
    """Console and degraded-file lines, taken only from structured notes."""
    lines: list[str] = []
    seen: set[str] = set()
    for note in notes:
        if note.get("level") not in ("warning", "error"):
            continue
        text = str(note.get("message") or "").strip()
        if not text or text in seen:
            continue
        seen.add(text)
        if text.startswith("WARNING "):
            lines.append(text)
        else:
            lines.append("WARNING cpu " + text)
    return lines


def publish_warnings(notes: list[dict[str, Any]], degraded: Path) -> None:
    """Write cpu-degraded.txt. A failure must not hide the bug map."""
    lines = warning_lines(notes)
    for line in lines:
        log.warning(line)
    try:
        if lines:
            degraded.write_text("\n".join(lines) + "\n", encoding="utf-8")
        else:
            degraded.unlink(missing_ok=True)
    except OSError as exc:
        log.warning("WARNING cpu degraded file was not written: %s", exc)


def write_diagnostics(
    events_path: Path,
    run_path: Path,
    notes: list[dict[str, Any]],
    run: dict[str, Any],
) -> None:
    """Write summary inputs. A failure here must not hide the bug map."""
    try:
        events_path.write_text(
            "".join(json.dumps(note) + "\n" for note in notes),
            encoding="utf-8",
        )
        run_path.write_text(json.dumps(run, indent=2) + "\n", encoding="utf-8")
    except OSError as exc:
        log.warning("WARNING cpu status files were not written: %s", exc)


def load_events(path: Path) -> list[dict[str, Any]]:
    if not path.is_file() or path.stat().st_size == 0:
        return []
    found: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as exc:
            found.append(
                issue(
                    level="error",
                    outcome="failed",
                    area="status",
                    message=f"cpu events line is not JSON: {exc}",
                    exception=line,
                )
            )
            continue
        if isinstance(item, dict):
            found.append(item)
    return found


def pending_count(path: Path) -> int | None:
    """How many Slack messages are still waiting. None when the file is unreadable."""
    if not path.is_file() or path.stat().st_size == 0:
        return 0
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    pending = data.get("pending")
    if pending is None:
        return 0
    if not isinstance(pending, list):
        return None
    return len(pending)


def render_status(
    result: str,
    run: dict[str, Any],
    events: list[dict[str, Any]],
    pending_left: int | None,
) -> tuple[str, str]:
    """Return the build description and the full status text.

    The description is the header plus warning headlines. Exception
    chains and recovered retries stay in the full text.
    """
    lines = [f"Status: {result}"]
    archived = any(
        event.get("area") == "archive" and event.get("outcome") == "published"
        for event in events
    )
    if not run:
        lines.append("Oracle advisories: run record missing")
        if archived:
            lines.append("Bug-to-CVE mapping: published (bug count unknown)")
        else:
            lines.append("Bug-to-CVE mapping: unknown")
    elif not run.get("index_ok") and not run.get("picked"):
        if run.get("baseline_present"):
            lines.append("Oracle advisories: index unusable, previous state kept")
        else:
            lines.append("Oracle advisories: index unusable, no previous state")
    else:
        refreshed = int(run.get("refreshed") or 0)
        cached = int(run.get("page_cached") or 0)
        unavailable = int(run.get("page_unavailable") or 0)
        parts = [f"{refreshed} refreshed"]
        if cached:
            parts.append(f"{cached} using cached data")
        if unavailable:
            parts.append(f"{unavailable} unavailable")
        lines.append("Oracle advisories: " + ", ".join(parts))
    if run:
        generated = bool(run.get("bug_map_generated") or run.get("bug_map_published"))
        if generated:
            # bug_map_published on an older run file only meant the JSON was written.
            state = "published" if archived else "generated"
            suffix = "" if archived else ", not archived"
            mapping = (
                f"Bug-to-CVE mapping: {state} ({int(run.get('bug_map_bugs') or 0)} bugs){suffix}"
            )
            if run.get("csaf_cached"):
                mapping += "; cached entries preserved"
            lines.append(mapping)
        elif "bug_map_generated" in run or "bug_map_published" in run:
            lines.append("Bug-to-CVE mapping: not generated")
        lines.append(
            f"CVE changes: +{int(run.get('cve_added') or 0)}, -{int(run.get('cve_removed') or 0)}"
        )
    delivered = sum(
        1
        for event in events
        if event.get("area") == "slack" and event.get("outcome") == "delivered"
    )
    pending_text = "unknown" if pending_left is None else str(pending_left)
    lines.append(f"Slack notifications: {delivered} delivered, {pending_text} pending")
    problems = [
        event
        for event in events
        if event.get("level") in ("warning", "error")
    ]
    headlines = []
    for event in problems:
        text = str(event.get("message") or "").strip()
        if len(text) > 400:
            text = text[:400] + "..."
        if text:
            headlines.append(f"- {text}")
    description_lines = list(lines)
    if headlines:
        description_lines.append("")
        description_lines.append("Warnings:")
        description_lines.extend(headlines)
    full = list(lines)
    full.append("")
    full.append(
        "Warnings below are the Oracle poll and Slack delivery. "
        "Other Jenkins log lines are not listed."
    )
    full.append("Warnings:" if problems else "Warnings: none")
    for event in problems:
        full.append(_format_issue(event))
    info = [event for event in events if event.get("level") == "info"]
    if info:
        full.append("")
        full.append("Info:")
        for event in info:
            full.append(_format_issue(event))
    description = "\n".join(description_lines).rstrip() + "\n"
    status = "\n".join(full).rstrip() + "\n"
    return description, status


def _format_issue(event: dict[str, Any]) -> str:
    rows = [f"- {str(event.get('message') or event.get('level') or 'issue').strip()}"]
    slug = str(event.get("slug") or "")
    if slug:
        rows.append(f"  Advisory: {slug}")
    attempts = event.get("attempts") or 0
    if attempts:
        rows.append(f"  Attempts: {attempts}")
    fallback = str(event.get("fallback") or "")
    if fallback:
        rows.append(f"  Fallback: {fallback}")
    impact = str(event.get("impact") or "")
    if impact:
        rows.append(f"  Impact: {impact}")
    exception = str(event.get("exception") or "").rstrip()
    if exception:
        rows.append("  Exception:")
        rows.extend(f"  {row}" for row in exception.splitlines())
    return "\n".join(rows)


def description_html(text: str) -> str:
    """Jenkins build description collapses newlines inside a plain div.

    Escape the text, then keep the breaks with br. cpu-status.txt stays
    plain text.
    """
    escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return escaped.replace("\n", "<br>\n")


def write_status(
    events_path: Path,
    run_path: Path,
    slack_state_path: Path,
    status_path: Path,
    description_path: Path,
    result: str,
) -> None:
    events = load_events(events_path)
    if run_path.is_file() and run_path.stat().st_size:
        try:
            run = json.loads(run_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            run = {}
            events.append(
                issue(
                    level="error",
                    outcome="failed",
                    area="status",
                    message=f"cpu run record is not JSON: {exc}",
                    exception=exception_text(exc),
                )
            )
        if not isinstance(run, dict):
            run = {}
    else:
        run = {}
        events.append(
            issue(
                level="warning",
                outcome="failed",
                area="status",
                message="Run record missing. Mapping may already be archived.",
                impact="status counts for this poll are incomplete",
            )
        )
    description, status = render_status(result, run, events, pending_count(slack_state_path))
    description_path.write_text(description_html(description), encoding="utf-8")
    status_path.write_text(status, encoding="utf-8")


def append_event(argv: list[str]) -> None:
    """Append one Groovy-recorded issue from a single JSON object."""
    if len(argv) != 3:
        raise SystemExit("usage: cpu_cves.py event EVENTS JSON_FILE")
    raw = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise SystemExit("event JSON must be an object")
    event = issue(
        level=str(raw.get("level") or "info"),
        outcome=str(raw.get("outcome") or ""),
        area=str(raw.get("area") or ""),
        slug=str(raw.get("slug") or ""),
        attempts=int(raw.get("attempts") or 0),
        message=str(raw.get("message") or ""),
        fallback=str(raw.get("fallback") or ""),
        impact=str(raw.get("impact") or ""),
        exception=str(raw.get("exception") or ""),
    )
    with Path(argv[1]).open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event) + "\n")

