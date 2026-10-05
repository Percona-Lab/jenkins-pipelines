"""Saved advisory baseline, pending Slack queue, and Jenkins outputs."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from oracle_cpu.core import (
    PARSER_VERSION,
    bug_map_from_advisories,
    collect,
    cve_sha,
    describe_change,
)
from oracle_cpu.diagnostics import exception_text, issue, publish_warnings, write_diagnostics

log = logging.getLogger("ps_notify")

def stored_advisory(row: Any) -> dict[str, Any] | None:
    """Return a usable advisory row, or None when the saved value is the wrong type.

    A string or number in cves used to raise or be treated as a CVE list and
    abort the bug-map write.
    """
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
    return {
        "sha": sha or cve_sha(list(raw_cves)),
        "cves": list(raw_cves),
        "bug_cves": bugs,
    }


def apply_state(
    events: list[dict[str, Any]],
    state: dict[str, Any] | None,
    report_seeded: bool = False,
    migrate: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Diff events against saved advisories.

    events must be newest-first, as fetch() returns them.
    state None means the S3 object is missing. Every advisory except the
    newest is stored and not reported, unless report_seeded is set.
    The newest is reported when its CVE set is non-empty. An empty page
    is not stored.

    state is a slug to {"sha", "cves"} map, not the on-disk wrapper.
    """
    seeding = state is None
    advisories: dict[str, Any] = {}
    if state is not None:
        for slug, row in state.items():
            stored = stored_advisory(row)
            if stored is None:
                log.warning("WARNING cpu baseline row %s is malformed and was skipped", slug)
                continue
            advisories[slug] = stored
    newest = None
    if events:
        newest = (events[0].get("payload") or {}).get("slug")
    changes: list[dict[str, Any]] = []
    for ev in events:
        payload = ev.get("payload") or {}
        slug = payload.get("slug")
        if not slug:
            continue
        new = list(payload.get("cves") or [])
        if not new:
            log.info("cpu skip empty slug=%s", slug)
            continue
        fresh_bugs = payload.get("bug_cves")
        if fresh_bugs is None:
            bug_cves = dict((advisories.get(slug) or {}).get("bug_cves") or {})
        else:
            bug_cves = fresh_bugs
        if seeding and slug != newest and not report_seeded:
            advisories[slug] = {
                "sha": cve_sha(new),
                "cves": new,
                "bug_cves": bug_cves,
            }
            log.info("cpu seed slug=%s cves=%d", slug, len(new))
            continue
        if migrate and state is not None and slug in state:
            advisories[slug] = {
                "sha": cve_sha(new),
                "cves": new,
                "bug_cves": bug_cves,
            }
            log.info("cpu parser migrate slug=%s cves=%d", slug, len(new))
            continue
        old = list((advisories.get(slug) or {}).get("cves") or [])
        change = describe_change(old, new)
        if change is None:
            kept = advisories.get(slug) or {}
            advisories[slug] = {
                "sha": kept.get("sha") or cve_sha(new),
                "cves": new,
                "bug_cves": bug_cves,
            }
            log.info("cpu unchanged slug=%s cves=%d", slug, len(new))
            continue
        change["slug"] = slug
        change["title"] = ev.get("title") or slug
        change["url"] = ev.get("url") or ""
        change["old_sha"] = cve_sha(old)
        changes.append(change)
        advisories[slug] = {
            "sha": change["sha"],
            "cves": list(new),
            "bug_cves": bug_cves,
        }
        log.info(
            "cpu diff slug=%s +%d -%d",
            slug,
            len(change["added"]),
            len(change["removed"]),
        )
    return changes, advisories


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
    """Keep undelivered messages, then append changes not already pending.

    The fetched CVE baseline can move forward while a message is still
    pending. A later poll must not drop that message just because the
    baseline already contains the new CVE set.
    """
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


def load_delivery(path: Path) -> tuple[dict[str, Any], str | None, str]:
    """Return threads plus pending messages, a warning line, and exception text.

    A missing file is an empty delivery state. A corrupt file is a
    warning and is not treated as an empty successful delivery.
    """
    empty: dict[str, Any] = {"threads": {}, "pending": []}
    if not path.is_file() or path.stat().st_size == 0:
        return empty, None, ""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return empty, f"WARNING cpu slack state {path} is not JSON: {exc}", exception_text(exc)
    if not isinstance(data, dict):
        return empty, f"WARNING cpu slack state {path} is not a JSON object", ""
    threads = data.get("threads") if isinstance(data.get("threads"), dict) else {}
    pending = data.get("pending") if isinstance(data.get("pending"), list) else []
    return {"threads": threads, "pending": pending}, None, ""


def save_delivery(path: Path, delivery: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(
            {
                "threads": delivery.get("threads") or {},
                "pending": delivery.get("pending") or [],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def ack_pending(path: Path, pending_id: str) -> None:
    """Drop one pending message after Slack has confirmed that send."""
    delivery, error, _exc_text = load_delivery(path)
    if error:
        raise SystemExit(error)
    delivery["pending"] = [
        item
        for item in delivery["pending"]
        if isinstance(item, dict) and item.get("id") != pending_id
    ]
    save_delivery(path, delivery)


def notification_items(
    events: list[dict[str, Any]],
    changes: list[dict[str, Any]],
    mode: str,
    pending: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Slack posts.

    Real CVE changes come only from pending, which already includes this
    run's changes under their transition ids. Synthesizing slug:sha here
    posts the same text a second time. mode none posts only those pending
    messages. latest also posts the newest advisory when it did not change.
    all does that for every watched advisory. Unchanged posts say "+0 -0 CVEs".
    """
    pending_slugs = {
        str(entry.get("slug") or "")
        for entry in (pending or [])
        if isinstance(entry, dict)
    }
    newest = None
    for ev in events:
        payload = ev.get("payload") or {}
        if payload.get("slug") and payload.get("cves"):
            newest = payload["slug"]
            break
    items: list[dict[str, Any]] = []
    seen: set[str] = set()
    for entry in pending or []:
        if not isinstance(entry, dict):
            continue
        ident = str(entry.get("id") or "")
        slug = str(entry.get("slug") or "")
        text = str(entry.get("slack") or "")
        if not ident or not slug or not text or ident in seen:
            continue
        seen.add(ident)
        items.append(
            {
                "id": ident,
                "slug": slug,
                "slack": text,
                "changed": True,
            }
        )
    for ev in events:
        payload = ev.get("payload") or {}
        slug = payload.get("slug")
        if not slug or not payload.get("cves"):
            continue
        if slug in pending_slugs:
            continue
        forced = mode == "all" or (mode == "latest" and slug == newest)
        if not forced:
            continue
        title = ev.get("title") or slug
        url = ev.get("url") or ""
        items.append(
            {
                "slug": slug,
                "slack": f"{title}\n{url}\n+0 -0 CVEs",
                "changed": False,
            }
        )
    return items


def write_notify_dir(path: Path, items: list[dict[str, Any]]) -> None:
    if path.exists():
        for child in path.iterdir():
            child.unlink()
    path.mkdir(parents=True, exist_ok=True)
    order = []
    changed = []
    for item in items:
        # Pending ids are "slug:sha". The file name uses "--" so the
        # pipeline can recover both parts.
        key = str(item.get("id") or item["slug"]).replace(":", "--")
        order.append(key)
        (path / f"{key}.txt").write_text(item["slack"].rstrip() + "\n", encoding="utf-8")
        if item["changed"]:
            changed.append(key)
    (path / "order.txt").write_text("\n".join(order) + ("\n" if order else ""), encoding="utf-8")
    (path / "changed.txt").write_text(
        "\n".join(changed) + ("\n" if changed else ""),
        encoding="utf-8",
    )


def _saved_parser(data: dict[str, Any]) -> int:
    value = data.get("parser", 0)
    if isinstance(value, bool) or not isinstance(value, int):
        return 0
    return value


def load_advisories(path: Path) -> tuple[dict[str, Any] | None, str | None, str, int]:
    """Return the slug map, or None when the file is absent.

    A corrupt file is not fatal. The error string is a WARNING line and
    the caller still publishes the bug map from this run's downloads.
    The third value is the exception chain when parsing raised.
    The fourth value is the parser version stored with the baseline.
    A file written before parser versions existed is version 0.
    """
    if not path.is_file() or path.stat().st_size == 0:
        return None, None, "", PARSER_VERSION
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, f"WARNING cpu baseline {path} is not JSON: {exc}", exception_text(exc), 0
    if not isinstance(data, dict):
        return None, f"WARNING cpu baseline {path} is not a JSON object", "", 0
    saved_parser = _saved_parser(data)
    advisories = data.get("advisories", data)
    if not isinstance(advisories, dict):
        return None, f"WARNING cpu baseline {path} has no advisories object", "", saved_parser
    kept: dict[str, Any] = {}
    bad: list[str] = []
    for slug, row in advisories.items():
        stored = stored_advisory(row)
        if stored is None:
            bad.append(str(slug))
            continue
        kept[slug] = stored
    if not bad:
        return kept, None, "", saved_parser
    warning = (
        f"WARNING cpu baseline {path} skipped malformed advisories: {', '.join(bad)}"
    )
    if not kept:
        return None, warning, "", saved_parser
    return kept, warning, "", saved_parser


def _write_run_body(
    state_path: Path,
    diff_path: Path,
    bugs_path: Path,
    notify_dir: Path,
    slack_state_path: Path,
    *,
    count: int,
    ignore_state: bool = False,
    notify_mode: str = "none",
    notes: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    collected = collect(count, notes)
    events, index_ok, notes, stats = collected
    degraded = state_path.with_name("cpu-degraded.txt")
    degraded.unlink(missing_ok=True)
    if ignore_state:
        previous, baseline_error, baseline_exc, saved_parser = None, None, "", PARSER_VERSION
    else:
        previous, baseline_error, baseline_exc, saved_parser = load_advisories(state_path)
    migrating = index_ok and previous is not None and saved_parser != PARSER_VERSION
    if migrating:
        notes.append(
            issue(
                level="info",
                outcome="migrated",
                area="parser",
                message=(
                    f"Parser version {saved_parser} -> {PARSER_VERSION}. "
                    "Existing CVE sets were realigned. "
                    "No Slack diff was sent for that realignment."
                ),
                fallback="baseline updated to the current parser",
                impact=(
                    "a CVE that only the previous parser reported is not "
                    "posted as an Oracle edit"
                ),
            )
        )
    if baseline_error:
        detail = baseline_error[12:] if baseline_error.startswith("WARNING cpu ") else baseline_error
        if previous is None:
            fallback = "this run treats the baseline as missing"
            impact = (
                "the newest advisory can be notified again; "
                "the bug map from this run is still written"
            )
        else:
            fallback = "malformed rows were skipped"
            impact = (
                "the other advisories stay the baseline; "
                "the bug map from this run is still written"
            )
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area="baseline",
                message=detail,
                fallback=fallback,
                impact=impact,
                exception=baseline_exc,
            )
        )
    if not index_ok:
        # No fresh pages. apply_state copies the normalized baseline and
        # reports no CVE diff.
        changes, advisories = apply_state([], previous)
    else:
        changes, advisories = apply_state(
            events,
            previous,
            report_seeded=(previous is None and notify_mode == "all"),
            migrate=migrating,
        )
    state_path.write_text(
        json.dumps(
            {
                "parser": PARSER_VERSION if index_ok else saved_parser,
                "advisories": advisories,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    bug_map = bug_map_from_advisories(advisories)
    bugs_path.write_text(
        json.dumps({"bugs": bug_map}, indent=2) + "\n",
        encoding="utf-8",
    )
    publish_marker = state_path.with_name("cpu-publish")
    try:
        if migrating or baseline_changed(previous, advisories):
            publish_marker.write_text("1\n", encoding="utf-8")
        else:
            publish_marker.unlink(missing_ok=True)
    except OSError as exc:
        log.warning("WARNING cpu publish marker was not written: %s", exc)
    delivery, delivery_error, delivery_exc = load_delivery(slack_state_path)
    if delivery_error:
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area="slack-state",
                message=(
                    delivery_error[12:]
                    if delivery_error.startswith("WARNING cpu ")
                    else delivery_error
                ),
                fallback="threads and pending notifications in the unreadable file are not kept",
                impact=(
                    "pending Slack notifications are lost and cannot be rebuilt "
                    "from the CVE baseline; a new thread may be started for an "
                    "advisory that already had one"
                ),
                exception=delivery_exc,
            )
        )
    delivery["pending"] = merge_pending(delivery["pending"], changes)
    save_delivery(slack_state_path, delivery)
    try:
        write_notify_dir(
            notify_dir,
            notification_items(events, changes, notify_mode, delivery["pending"]),
        )
        if changes:
            diff_path.write_text(
                json.dumps({"changes": changes}, indent=2) + "\n",
                encoding="utf-8",
            )
        else:
            diff_path.unlink(missing_ok=True)
    except OSError as exc:
        # State and the pending queue are already saved together. A failure
        # here must not stop Jenkins from archiving the bug map.
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area="notify",
                message=f"Notification files were not written: {exc}",
                fallback="bug map, advisory state, and pending queue stay paired",
                impact="this poll may not post Slack",
                exception=exception_text(exc),
            )
        )
    added = sum(len(change.get("added") or []) for change in changes)
    removed = sum(len(change.get("removed") or []) for change in changes)
    kept = reconcile_fallbacks(notes, previous)
    publish_warnings(notes, degraded)
    run = {
        "picked": stats.get("picked", 0),
        "refreshed": stats.get("refreshed", 0),
        "page_cached": kept["page_cached"],
        "page_unavailable": kept["page_unavailable"],
        "csaf_cached": kept["csaf_cached"],
        "baseline_present": bool(previous),
        "index_ok": bool(index_ok),
        "degraded": any(note.get("level") in ("warning", "error") for note in notes),
        "bug_map_generated": True,
        "bug_map_bugs": len(bug_map),
        "cve_added": added,
        "cve_removed": removed,
    }
    return run


def write_run(
    state_path: Path,
    diff_path: Path,
    bugs_path: Path,
    notify_dir: Path,
    slack_state_path: Path,
    *,
    count: int,
    ignore_state: bool = False,
    notify_mode: str = "none",
) -> None:
    """Publish the bug map even when the poll raises.

    Notes gathered before the failure, plus the exception itself, are
    written in finally. That write cannot replace the original error.
    """
    notes: list[dict[str, Any]] = []
    run: dict[str, Any] = {}
    try:
        run = _write_run_body(
            state_path,
            diff_path,
            bugs_path,
            notify_dir,
            slack_state_path,
            count=count,
            ignore_state=ignore_state,
            notify_mode=notify_mode,
            notes=notes,
        )
    except BaseException as exc:
        notes.append(
            issue(
                level="error",
                outcome="failed",
                area="poll",
                message=f"Poll failed: {exc}",
                exception=exception_text(exc),
            )
        )
        raise
    finally:
        write_diagnostics(
            state_path.with_name("cpu-events.jsonl"),
            state_path.with_name("cpu-run.json"),
            notes,
            run,
        )

def baseline_changed(
    previous: dict[str, Any] | None,
    advisories: dict[str, Any],
) -> bool:
    """True when saved CVE sets or bug maps differ from this run.

    A bug-map-only change has no Slack diff. The new files still have to
    be archived or the next poll copies the older baseline.
    """
    if previous is None:
        return bool(advisories)

    def signature(rows: dict[str, Any]) -> dict[str, tuple[Any, ...]]:
        signed: dict[str, tuple[Any, ...]] = {}
        for slug, row in rows.items():
            if not isinstance(row, dict):
                continue
            raw_bugs = row.get("bug_cves") if isinstance(row.get("bug_cves"), dict) else {}
            bugs = tuple(
                sorted(
                    (str(key), tuple(values))
                    for key, values in raw_bugs.items()
                    if isinstance(values, list)
                )
            )
            signed[str(slug)] = (tuple(row.get("cves") or []), bugs)
        return signed

    return signature(previous) != signature(advisories)


def _row_has(previous: dict[str, Any] | None, slug: str, key: str) -> bool:
    row = (previous or {}).get(slug)
    return isinstance(row, dict) and bool(row.get(key))


def reconcile_fallbacks(
    notes: list[dict[str, Any]],
    previous: dict[str, Any] | None,
) -> dict[str, int]:
    """Set fallback and impact from the saved baseline, and return the counts."""
    page_cached = 0
    page_unavailable = 0
    csaf_cached = 0
    for note in notes:
        if note.get("outcome") != "failed":
            continue
        area = note.get("area")
        slug = str(note.get("slug") or "")
        if area == "page" and slug:
            if _row_has(previous, slug, "cves"):
                page_cached += 1
                note["fallback"] = "previous CVE list and bug map kept"
                note["impact"] = "this advisory is not refreshed"
            else:
                page_unavailable += 1
                note["fallback"] = "no previous advisory state"
                note["impact"] = "this advisory is absent from the baseline"
        elif area == "csaf" and slug:
            if _row_has(previous, slug, "bug_cves"):
                csaf_cached += 1
                note["fallback"] = "previous bug map kept"
                note["impact"] = "bug-to-CVE entries from the previous poll stay in the map"
            else:
                note["fallback"] = "no previous bug map"
                note["impact"] = "this advisory contributes no bug-to-CVE entries"
        elif area == "page" and not slug:
            if previous:
                note["fallback"] = "previous advisory state kept"
                note["impact"] = "no advisory was refreshed"
            else:
                note["fallback"] = "no previous advisory state"
                note["impact"] = "this poll has no advisory baseline"
        elif area == "index":
            if previous:
                note["fallback"] = "previous advisory state kept"
                note["impact"] = "this poll does not replace the saved advisories"
            else:
                note["fallback"] = "no previous advisory state"
                note["impact"] = "this poll has no advisory baseline"
    return {
        "page_cached": page_cached,
        "page_unavailable": page_unavailable,
        "csaf_cached": csaf_cached,
    }

