"""Saved advisory cache, Slack threads, and pending messages.

One cpu-state.json holds all three. CVE baseline and pending messages
are replaced together.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from oracle_cpu.core import cve_sha
from oracle_cpu.diagnostics import atomic_write, exception_text, issue


def stored_advisory(row: Any) -> dict[str, Any] | None:
    """Return a usable advisory row, or None when the saved value is the wrong type."""
    if not isinstance(row, dict):
        return None
    raw_cves = row.get("cves", [])
    if not isinstance(raw_cves, list) or not all(isinstance(item, str) for item in raw_cves):
        return None
    sha = row.get("sha") or ""
    if not isinstance(sha, str):
        return None
    raw_bugs = row.get("bug_cves", {})
    if raw_bugs is None:
        raw_bugs = {}
    if not isinstance(raw_bugs, dict):
        return None
    bugs: dict[str, list[str]] = {}
    for key, cves in raw_bugs.items():
        if not isinstance(cves, list) or not all(isinstance(item, str) for item in cves):
            return None
        bugs[str(key)] = list(cves)
    raw_components = row.get("cve_components") or {}
    components: dict[str, str] = {}
    if isinstance(raw_components, dict):
        for cve, name in raw_components.items():
            if isinstance(cve, str) and isinstance(name, str) and cve.startswith("CVE-"):
                components[cve] = name
    return {
        "sha": sha or cve_sha(list(raw_cves)),
        "cves": list(raw_cves),
        "bug_cves": bugs,
        "cve_components": components,
        "title": str(row.get("title") or ""),
        "url": str(row.get("url") or ""),
    }


def persistent_signature(state: dict[str, Any]) -> str:
    """Canonical text of the checkpoint.

    Poll counters and the build report are not part of this. A mapping
    change, a thread id, or a pending message is.
    """
    payload = {
        "advisories": state.get("advisories") if isinstance(state.get("advisories"), dict) else {},
        "threads": state.get("threads") if isinstance(state.get("threads"), dict) else {},
        "pending": state.get("pending") if isinstance(state.get("pending"), list) else [],
    }
    return json.dumps(payload, sort_keys=True, separators=(",", ":"))


def empty_state() -> dict[str, Any]:
    return {"threads": {}, "pending": [], "advisories": {}}


def _advisories_from(data: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    raw = data.get("advisories", data)
    if not isinstance(raw, dict):
        return {}, ["baseline has no advisories object"]
    kept: dict[str, Any] = {}
    bad: list[str] = []
    for slug, row in raw.items():
        if slug in ("parser", "threads", "pending", "advisories"):
            continue
        stored = stored_advisory(row)
        if stored is None:
            bad.append(str(slug))
            continue
        kept[str(slug)] = stored
    return kept, bad


def _threads_from(data: dict[str, Any]) -> dict[str, Any]:
    raw = data.get("threads")
    if not isinstance(raw, dict):
        return {}
    threads: dict[str, Any] = {}
    for slug, row in raw.items():
        if not isinstance(row, dict):
            continue
        thread_id = str(row.get("threadId") or "")
        if not thread_id:
            continue
        threads[str(slug)] = {
            "channelId": str(row.get("channelId") or ""),
            "ts": str(row.get("ts") or ""),
            "threadId": thread_id,
        }
    return threads


def _pending_from(data: dict[str, Any]) -> list[dict[str, str]]:
    raw = data.get("pending")
    if not isinstance(raw, list):
        return []
    return merge_pending(raw, [])


def _read_json(path: Path) -> tuple[dict[str, Any] | None, str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, exception_text(exc)
    except OSError as exc:
        return None, exception_text(exc)
    if not isinstance(data, dict):
        return None, ""
    return data, ""


def load_state(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Load cpu-state.json. A missing file is an empty checkpoint.

    A corrupt file is a warning and is not treated as an empty success.
    """
    notes: list[dict[str, Any]] = []
    if path.is_file() and path.stat().st_size:
        data, exc_text = _read_json(path)
        if data is None:
            notes.append(
                issue(
                    level="warning",
                    outcome="failed",
                    area="baseline",
                    message=f"baseline {path.name} is not JSON.",
                    fallback="this run treats the baseline as missing",
                    impact=(
                        "the newest advisory can be notified again; "
                        "pending messages in the unreadable file are not kept"
                    ),
                    exception=exc_text,
                )
            )
            return empty_state(), notes
        advisories, bad = _advisories_from(data)
        if bad:
            notes.append(
                issue(
                    level="warning",
                    outcome="failed",
                    area="baseline",
                    message=f"malformed advisory rows skipped: {', '.join(bad)}.",
                    fallback="malformed rows were skipped",
                    impact="the other advisories stay the baseline",
                )
            )
        state = {
            "threads": _threads_from(data),
            "pending": _pending_from(data),
            "advisories": advisories,
        }
        return state, notes
    return empty_state(), notes


def save_state(path: Path, state: dict[str, Any]) -> None:
    atomic_write(path, json.dumps(state, indent=2) + "\n")


def change_pending(change: dict[str, Any], seq: int) -> dict[str, str]:
    """One undelivered Slack message.

    The id names the transition and a sequence number. An id of only the
    new CVE set collides when that set is added, removed, and added again
    while the first message is still pending.
    """
    slug = str(change["slug"])
    sha = str(change["sha"])
    old_sha = str(change.get("old_sha") or "none")
    return {
        "id": f"{slug}:{old_sha}-{sha}-{seq}",
        "slug": slug,
        "sha": sha,
        "slack": f"{change['title']}\n{change['url']}\n{change['slack']}",
    }


def merge_pending(
    existing: list[Any],
    changes: list[dict[str, Any]],
) -> list[dict[str, str]]:
    """Keep undelivered messages, then append changes not already pending."""
    out: list[dict[str, str]] = []
    seen: set[str] = set()
    next_seq = 1
    for item in existing:
        if not isinstance(item, dict):
            continue
        ident = str(item.get("id") or "")
        slug = str(item.get("slug") or "")
        text = str(item.get("slack") or "")
        if not ident or not slug or not text or ident in seen:
            continue
        seen.add(ident)
        tail = ident.rsplit("-", 1)[-1]
        if "-" in ident and tail.isdigit():
            next_seq = max(next_seq, int(tail) + 1)
        out.append(
            {
                "id": ident,
                "slug": slug,
                "sha": str(item.get("sha") or ""),
                "slack": text,
            }
        )
    for change in changes:
        item = change_pending(change, next_seq)
        next_seq += 1
        if item["id"] in seen:
            continue
        seen.add(item["id"])
        out.append(item)
    return out


def ack_state(
    path: Path,
    pending_id: str = "",
    slug: str = "",
    thread: dict[str, Any] | None = None,
) -> None:
    """Drop one pending id and store a thread, in one replacement."""
    state, notes = load_state(path)
    unreadable = any("is not JSON" in str(note.get("message") or "") for note in notes)
    if unreadable:
        raise SystemExit(f"cpu state {path} is unreadable; ack skipped")
    if pending_id:
        state["pending"] = [
            item
            for item in state["pending"]
            if isinstance(item, dict) and item.get("id") != pending_id
        ]
    if slug and thread:
        thread_id = str(thread.get("threadId") or "")
        if thread_id:
            threads = state.get("threads") if isinstance(state.get("threads"), dict) else {}
            threads[slug] = {
                "channelId": str(thread.get("channelId") or ""),
                "ts": str(thread.get("ts") or ""),
                "threadId": thread_id,
            }
            state["threads"] = threads
    save_state(path, state)
