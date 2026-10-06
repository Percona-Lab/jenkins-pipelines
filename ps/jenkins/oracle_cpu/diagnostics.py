"""Issue records, status text, and diagnostic files."""

from __future__ import annotations

import json
import logging
import traceback
import urllib.error
from pathlib import Path
from typing import Any

log = logging.getLogger("oracle_cpu")

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


def atomic_write(path: Path, text: str) -> None:
    """Replace path with text. Readers see the old file or the new file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def save_report(path: Path, notes: list[dict[str, Any]], run: dict[str, Any]) -> None:
    """Write the build report. A failure here must not hide the bug map."""
    payload = dict(run)
    payload["notes"] = list(notes)
    try:
        atomic_write(path, json.dumps(payload, indent=2) + "\n")
    except OSError as exc:
        log.warning("WARNING cpu report was not written: %s", exc)


def load_report(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Return the report and extra notes. A bad file does not raise."""
    extra: list[dict[str, Any]] = []
    if not path.is_file() or path.stat().st_size == 0:
        extra.append(
            issue(
                level="warning",
                outcome="failed",
                area="status",
                message="Run record missing. Mapping may already be archived.",
                impact="status counts for this poll are incomplete",
            )
        )
        return {}, extra
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        extra.append(
            issue(
                level="error",
                outcome="failed",
                area="status",
                message=f"cpu run record is not JSON: {exc}",
                exception=exception_text(exc),
            )
        )
        return {}, extra
    if not isinstance(data, dict):
        extra.append(
            issue(
                level="error",
                outcome="failed",
                area="status",
                message="cpu run record is not a JSON object.",
            )
        )
        return {}, extra
    notes = data.get("notes") if isinstance(data.get("notes"), list) else []
    return data, [note for note in notes if isinstance(note, dict)] + extra


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
    notes: list[dict[str, Any]],
    pending_left: int | None,
) -> tuple[str, str]:
    """Return the build description and the full status text.

    The description is the header plus warning headlines. Exception
    chains and recovered retries stay in the full text.
    """
    lines = [f"Status: {result}"]
    archived = bool(run.get("archived")) or any(
        note.get("area") == "archive" and note.get("outcome") == "published"
        for note in notes
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
        for note in notes
        if note.get("area") == "slack" and note.get("outcome") == "delivered"
    )
    pending_text = "unknown" if pending_left is None else str(pending_left)
    lines.append(f"Slack notifications: {delivered} delivered, {pending_text} pending")
    problems = [
        note
        for note in notes
        if note.get("level") in ("warning", "error")
    ]
    headlines = []
    for note in problems:
        text = str(note.get("message") or "").strip()
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
    for note in problems:
        full.append(_format_issue(note))
    info = [note for note in notes if note.get("level") == "info"]
    if info:
        full.append("")
        full.append("Info:")
        for note in info:
            full.append(_format_issue(note))
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
    report_path: Path,
    state_path: Path,
    status_path: Path,
    description_path: Path,
    result: str,
) -> None:
    run, notes = load_report(report_path)
    description, status = render_status(result, run, notes, pending_count(state_path))
    description_path.write_text(description_html(description), encoding="utf-8")
    status_path.write_text(status, encoding="utf-8")


def append_note(report_path: Path, raw: dict[str, Any]) -> None:
    """Add one Groovy outcome to the report. Missing report still records it."""
    run, notes = load_report(report_path)
    # load_report's own warning is about a missing file, not a poll fact.
    notes = [
        note
        for note in notes
        if not (
            note.get("area") == "status"
            and str(note.get("message") or "").startswith("Run record missing")
        )
    ]
    if raw.get("area") == "archive" and raw.get("outcome") == "published":
        run["archived"] = True
    notes.append(
        issue(
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
    )
    save_report(report_path, notes, run)

