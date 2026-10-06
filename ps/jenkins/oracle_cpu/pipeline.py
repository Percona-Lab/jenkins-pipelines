"""One Oracle poll: fetch, diff, and write the durable files."""

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
from oracle_cpu.diagnostics import exception_text, issue, save_report, warning_lines
from oracle_cpu.state import (
    atomic_write,
    load_state,
    merge_pending,
    persistent_signature,
    save_state,
    stored_advisory,
    _row_parser,
)

log = logging.getLogger("oracle_cpu")


def _fresh_row(
    cves: list[str],
    bug_cves: dict[str, list[str]],
    sha: str | None = None,
    title: str = "",
    url: str = "",
) -> dict[str, Any]:
    copied = list(cves)
    return {
        "sha": sha or cve_sha(copied),
        "cves": copied,
        "bug_cves": bug_cves,
        "parser": PARSER_VERSION,
        "title": title,
        "url": url,
    }


def _store(
    changes: list[dict[str, Any]],
    rows: dict[str, Any],
    slug: str,
    old: list[str],
    new: list[str],
    bug_cves: dict[str, list[str]],
    title: str,
    url: str,
    *,
    report: bool = True,
    prefix: str = "",
) -> dict[str, Any] | None:
    """Save one advisory. Queue a Slack change when report finds a difference."""
    change = describe_change(old, new) if report else None
    if change is None:
        rows[slug] = _fresh_row(new, bug_cves, title=title, url=url)
        return None
    if prefix:
        change["slack"] = prefix + change["slack"]
    change["slug"] = slug
    change["title"] = title
    change["url"] = url
    change["old_sha"] = cve_sha(old)
    changes.append(change)
    rows[slug] = _fresh_row(new, bug_cves, change["sha"], title, url)
    return change


def apply_state(
    fresh: list[dict[str, Any]],
    previous: dict[str, Any] | None,
    *,
    report_seeded: bool = False,
    ignore_cves: bool = False,
    saved_parser: int = PARSER_VERSION,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Diff fresh advisories against saved rows.

    fresh is newest-first. previous None means there is no CVE baseline.
    Every advisory except the newest is stored and not reported, unless
    report_seeded is set. ignore_cves keeps saved rows, including bug
    maps, and compares CVE sets as empty. Only the newest advisory is
    reported, unless report_seeded is set.
    """
    rows: dict[str, Any] = {}
    if previous is not None:
        for slug, row in previous.items():
            stored = stored_advisory(row, saved_parser)
            if stored is None:
                log.warning("WARNING cpu baseline row %s is malformed and was skipped", slug)
                continue
            rows[slug] = stored
    newest = fresh[0]["slug"] if fresh else None
    changes: list[dict[str, Any]] = []
    for item in fresh:
        slug = str(item.get("slug") or "")
        new = list(item.get("cves") or [])
        if not slug or not new:
            log.info("cpu skip empty slug=%s", slug)
            continue
        fresh_bugs = item.get("bug_cves")
        if fresh_bugs is None:
            bug_cves = dict((rows.get(slug) or {}).get("bug_cves") or {})
        else:
            bug_cves = fresh_bugs
        title = str(item.get("title") or (rows.get(slug) or {}).get("title") or slug)
        url = str(item.get("url") or (rows.get(slug) or {}).get("url") or "")
        if previous is None and slug != newest and not report_seeded:
            _store(changes, rows, slug, [], new, bug_cves, title, url, report=False)
            log.info("cpu seed slug=%s cves=%d", slug, len(new))
            continue
        old_parser = _row_parser(rows.get(slug) or {}, PARSER_VERSION)
        migrate_slug = (
            not ignore_cves
            and previous is not None
            and slug in previous
            and old_parser != PARSER_VERSION
        )
        old = list((rows.get(slug) or {}).get("cves") or [])
        prefix = ""
        report = True
        if migrate_slug:
            excluded = item.get("parser_excluded")
            if isinstance(excluded, list):
                # Only ids the new parser is defined to drop stay quiet.
                old = [cve for cve in old if cve not in set(excluded)]
            else:
                # No exclusion list. Report the whole difference so a real
                # Oracle removal is not stored and then forgotten.
                prefix = "Parser upgrade.\n"
        elif ignore_cves:
            old = []
            report = bool(report_seeded or slug == newest)
        change = _store(
            changes,
            rows,
            slug,
            old,
            new,
            bug_cves,
            title,
            url,
            report=report,
            prefix=prefix,
        )
        if change is None:
            log.info("cpu unchanged slug=%s cves=%d", slug, len(new))
        else:
            log.info(
                "cpu diff slug=%s +%d -%d",
                slug,
                len(change["added"]),
                len(change["removed"]),
            )
    return changes, rows


def notification_items(
    fresh: list[dict[str, Any]],
    mode: str,
    pending: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Slack posts from the pending queue, plus optional unchanged posts.

    Real CVE changes come only from pending. mode none posts only those.
    latest also posts the newest advisory when it has no pending message.
    all does that for every watched advisory.
    """
    pending_slugs = {
        str(entry.get("slug") or "")
        for entry in (pending or [])
        if isinstance(entry, dict)
    }
    newest = None
    for item in fresh:
        if item.get("slug") and item.get("cves"):
            newest = item["slug"]
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
                "pending_id": ident,
                "slug": slug,
                "text": text,
                "changed": True,
            }
        )
    for item in fresh:
        slug = item.get("slug")
        if not slug or not item.get("cves") or slug in pending_slugs:
            continue
        forced = mode == "all" or (mode == "latest" and slug == newest)
        if not forced:
            continue
        title = item.get("title") or slug
        url = item.get("url") or ""
        items.append(
            {
                "pending_id": "",
                "slug": slug,
                "text": f"{title}\n{url}\n+0 -0 CVEs",
                "changed": False,
            }
        )
    return items


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


def poll(
    state_path: Path,
    bugs_path: Path,
    manifest_path: Path,
    report_path: Path,
    *,
    count: int,
    ignore_state: bool = False,
    notify_mode: str = "none",
) -> bool:
    """Write the mapping when any advisory row is available.

    Returns False when there is nothing to publish. The report is written
    as steps finish, including after a later failure.
    """
    notes: list[dict[str, Any]] = []
    run: dict[str, Any] = {
        "picked": 0,
        "refreshed": 0,
        "page_cached": 0,
        "page_unavailable": 0,
        "csaf_cached": 0,
        "baseline_present": False,
        "index_ok": False,
        "degraded": False,
        "usable": False,
        "bug_map_generated": False,
        "bug_map_bugs": 0,
        "cve_added": 0,
        "cve_removed": 0,
        "archived": False,
        "state_changed": None,
        "checkpoint_saved": False,
    }

    def flush() -> None:
        failed = any(note.get("level") in ("warning", "error") for note in notes)
        run["degraded"] = bool(failed or not run.get("index_ok"))
        save_report(report_path, notes, run)

    try:
        collected = collect(count, notes)
        fresh, index_ok, notes, stats = collected
        run["picked"] = stats.get("picked", 0)
        run["refreshed"] = stats.get("refreshed", 0)
        run["index_ok"] = bool(index_ok)
        flush()
        state, load_notes, saved_parser = load_state(state_path)
        notes.extend(load_notes)
        comparable = not any(
            "is not JSON" in str(note.get("message") or "") for note in load_notes
        )
        before_sig = persistent_signature(state) if comparable else None
        previous = state["advisories"] if state["advisories"] else None
        run["baseline_present"] = previous is not None
        if not index_ok:
            changes, advisories = apply_state([], previous, saved_parser=saved_parser)
        else:
            changes, advisories = apply_state(
                fresh,
                previous,
                report_seeded=(previous is None or ignore_state) and notify_mode == "all",
                ignore_cves=bool(ignore_state and previous is not None),
                saved_parser=saved_parser,
            )
        migrated = [
            slug
            for slug, row in advisories.items()
            if previous is not None
            and isinstance(previous.get(slug), dict)
            and _row_parser(previous[slug], saved_parser) != PARSER_VERSION
            and _row_parser(row, PARSER_VERSION) == PARSER_VERSION
        ]
        if migrated:
            notes.append(
                issue(
                    level="info",
                    outcome="migrated",
                    area="parser",
                    message=(
                    f"Parser version -> {PARSER_VERSION} for {', '.join(migrated)}. "
                    "CVEs on the parser exclusion list were not posted. "
                    "Any other difference was."
                    ),
                    fallback="refreshed advisories updated to the current parser",
                    impact=(
                        "a CVE that only the previous parser reported is not "
                        "posted as an Oracle edit"
                    ),
                )
            )
        if not advisories:
            notes.append(
                issue(
                    level="error",
                    outcome="failed",
                    area="poll",
                    message="No advisory data to publish.",
                    fallback="previous artifacts stay the copy source",
                    impact="this build has no bug map",
                )
            )
            return False
        kept = reconcile_fallbacks(notes, previous)
        run["page_cached"] = kept["page_cached"]
        run["page_unavailable"] = kept["page_unavailable"]
        run["csaf_cached"] = kept["csaf_cached"]
        state["advisories"] = advisories
        state["pending"] = merge_pending(state.get("pending") or [], changes)
        try:
            save_state(state_path, state)
        except OSError as exc:
            notes.append(
                issue(
                    level="warning",
                    outcome="failed",
                    area="baseline",
                    message=f"Checkpoint was not written: {exc}",
                    fallback="the bug map is still written",
                    impact="pending messages from this poll are not sent",
                    exception=exception_text(exc),
                )
            )
        else:
            run["checkpoint_saved"] = True
            if before_sig is None:
                run["state_changed"] = None
            else:
                run["state_changed"] = persistent_signature(state) != before_sig
        bug_map = bug_map_from_advisories(advisories)
        try:
            atomic_write(
                bugs_path,
                json.dumps({"bugs": bug_map}, indent=2) + "\n",
            )
        except OSError as exc:
            notes.append(
                issue(
                    level="error",
                    outcome="failed",
                    area="poll",
                    message=f"Bug map was not written: {exc}",
                    fallback="previous artifacts stay the copy source",
                    impact="this build has no bug map",
                    exception=exception_text(exc),
                )
            )
            return False
        run["usable"] = True
        run["bug_map_generated"] = True
        run["bug_map_bugs"] = len(bug_map)
        run["cve_added"] = sum(len(change.get("added") or []) for change in changes)
        run["cve_removed"] = sum(len(change.get("removed") or []) for change in changes)
        flush()
        if not run["checkpoint_saved"]:
            return True
        try:
            items = notification_items(fresh, notify_mode, state["pending"])
            atomic_write(
                manifest_path,
                json.dumps({"items": items}, indent=2) + "\n",
            )
        except OSError as exc:
            notes.append(
                issue(
                    level="warning",
                    outcome="failed",
                    area="notify",
                    message=f"Notification manifest was not written: {exc}",
                    fallback="bug map and pending queue stay paired",
                    impact="this poll may not post Slack",
                    exception=exception_text(exc),
                )
            )
        return True
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
        for line in warning_lines(notes):
            log.warning(line)
        flush()
