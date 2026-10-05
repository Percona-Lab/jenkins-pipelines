# Copied from mysql-sandbox ps_notify/cpu_cves.py.
# That file is the source of truth until mysql-sandbox is merged to trunk.
# Re-copy from there when the fetcher or the state JSON shape changes.

"""Fetch Oracle CPU/CSPU pages and diff CVE sets.

No sqlite and no Jenkins. ps-notify's cpu provider and the Jenkins
check-oracle-cpu job both use this module. The Jenkins tree keeps a copy.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import json
import logging
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

log = logging.getLogger("ps_notify")

INDEX = "https://www.oracle.com/security-alerts/"
UA = "Mozilla/5.0 (compatible; ps-notify-cpu/1.0)"
HREF_RE = re.compile(
    r'href="[^"]*?((?:cpu|cspu)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(\d{4}))\.html"',
    re.I,
)
CVE_RE = re.compile(r"CVE-\d{4}-\d{4,}", re.I)
CSAF_RE = re.compile(r'href="([^"]+csaf\.json)"', re.I)
MONTH = {
    "jan": 1,
    "feb": 2,
    "mar": 3,
    "apr": 4,
    "may": 5,
    "jun": 6,
    "jul": 7,
    "aug": 8,
    "sep": 9,
    "oct": 10,
    "nov": 11,
    "dec": 12,
}
MONTH_NAME = {
    1: "January",
    2: "February",
    3: "March",
    4: "April",
    5: "May",
    6: "June",
    7: "July",
    8: "August",
    9: "September",
    10: "October",
    11: "November",
    12: "December",
}


def _fetch(url: str) -> str:
    """GET url, pause one second, and try again up to 3 times."""
    ctx = ssl.create_default_context()
    last: Exception | None = None
    for attempt in range(1, 4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, context=ctx, timeout=120) as resp:
                body = resp.read().decode("utf-8", "replace")
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last = exc
            log.warning(
                "WARNING cpu fetch attempt %s/3 failed url=%s err=%s",
                attempt,
                url,
                type(exc).__name__,
            )
            time.sleep(1)
            continue
        time.sleep(1)
        return body
    assert last is not None
    raise last


def _parse_slug(slug: str) -> tuple[str, int, int]:
    slug = slug.lower()
    if slug.startswith("cspu"):
        kind = "CSPU"
        mon = slug[4:7]
        year = int(slug[7:11])
    else:
        kind = "CPU"
        mon = slug[3:6]
        year = int(slug[6:10])
    return kind, MONTH[mon], year


def strip_modification_history(html: str) -> str:
    """Drop the Modification History section before CVE scanning.

    A CVE removed from the risk matrix can still be named in that history.
    The following risk-matrix headings stay. Other prose matches are kept.
    """
    match = re.search(
        r"<h[1-6][^>]*>\s*Modification History\s*</h[1-6]>",
        html,
        re.I,
    )
    if not match:
        return html
    rest = html[match.end() :]
    nxt = re.search(r"<h[1-6]\b", rest, re.I)
    if not nxt:
        return html[: match.start()]
    return html[: match.start()] + rest[nxt.start() :]


def parse_cves(html: str) -> list[str]:
    html = strip_modification_history(html)
    return sorted({m.group(0).upper() for m in CVE_RE.finditer(html)})


def cve_sha(cves: list[str]) -> str:
    return hashlib.sha256("\n".join(cves).encode()).hexdigest()[:12]


def bug_map_from_csaf(data: dict[str, Any]) -> dict[str, list[str]]:
    """Map Oracle bug id to CVE ids.

    CSAF stores these on each vulnerability as ids[].system_name
    "Oracle Bug ID of ..." and ids[].text the bug number. Entries
    without a bug id are omitted. A non-object document raises
    ValueError so the caller can skip that advisory only.
    """
    if not isinstance(data, dict):
        raise ValueError("CSAF document is not a JSON object")
    vulns = data.get("vulnerabilities")
    if vulns is None:
        vulns = []
    if not isinstance(vulns, list):
        raise ValueError("CSAF vulnerabilities is not a list")
    bugs: dict[str, list[str]] = {}
    for vuln in vulns:
        if not isinstance(vuln, dict):
            continue
        cve = str(vuln.get("cve") or "").upper()
        if not cve.startswith("CVE-"):
            continue
        for item in vuln.get("ids") or []:
            name = str(item.get("system_name") or "")
            if "bug id" not in name.lower():
                continue
            bug = str(item.get("text") or "").strip()
            if not bug.isdigit():
                continue
            bucket = bugs.setdefault(bug, [])
            if cve not in bucket:
                bucket.append(cve)
    for cves in bugs.values():
        cves.sort()
    return bugs


def fetch_bug_map(page_url: str, html: str) -> dict[str, list[str]] | None:
    """Return the bug map, or None when the CSAF file could not be used.

    An advisory page with no CSAF link returns an empty map. That is not
    a failure. None tells the caller to keep the previous bug map.
    """
    match = CSAF_RE.search(html)
    if not match:
        log.warning("cpu no CSAF link url=%s", page_url)
        return {}
    csaf_url = urllib.parse.urljoin(page_url, match.group(1))
    try:
        raw = _fetch(csaf_url)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        log.warning(
            "WARNING cpu CSAF fetch failed url=%s err=%s",
            csaf_url,
            type(exc).__name__,
        )
        return None
    try:
        data = json.loads(raw)
        bug_map = bug_map_from_csaf(data)
    except (json.JSONDecodeError, ValueError, TypeError, AttributeError) as exc:
        log.warning("WARNING cpu CSAF unusable url=%s err=%s", csaf_url, exc)
        return None
    log.info("cpu CSAF url=%s bugs=%d", csaf_url, len(bug_map))
    return bug_map


def bug_map_from_advisories(advisories: dict[str, Any]) -> dict[str, list[str]]:
    """Flatten per-advisory bug maps, including ones kept from older state."""
    fake = [
        {"payload": {"bug_cves": (row or {}).get("bug_cves") or {}}}
        for row in advisories.values()
        if isinstance(row, dict)
    ]
    return merge_bug_maps(fake)


def merge_bug_maps(events: list[dict[str, Any]]) -> dict[str, list[str]]:
    merged: dict[str, list[str]] = {}
    for ev in events:
        for bug, cves in ((ev.get("payload") or {}).get("bug_cves") or {}).items():
            bucket = merged.setdefault(str(bug), [])
            for cve in cves:
                if cve not in bucket:
                    bucket.append(cve)
    for cves in merged.values():
        cves.sort()
    return merged


def format_bodies(old: list[str], new: list[str]) -> tuple[str, str]:
    old_set = set(old)
    new_set = set(new)
    added = sorted(new_set - old_set)
    removed = sorted(old_set - new_set)
    gnome = f"+{len(added)} -{len(removed)} CVEs"
    if not old:
        slack = f"+{len(added)} CVEs"
    else:
        lines = [gnome]
        lines.extend(f"+ {cve}" for cve in added)
        lines.extend(f"- {cve}" for cve in removed)
        slack = "\n".join(lines)
    return gnome, slack


def describe_change(old: list[str], new: list[str]) -> dict[str, Any] | None:
    """Return a diff record, or None when the CVE sets match.

    An empty new list against an empty old list is unchanged. A first
    non-empty list uses the short Slack line from format_bodies.
    """
    if set(old) == set(new):
        return None
    added = sorted(set(new) - set(old))
    removed = sorted(set(old) - set(new))
    gnome, slack = format_bodies(old, new)
    return {
        "sha": cve_sha(new),
        "cves": list(new),
        "added": added,
        "removed": removed,
        "gnome": gnome,
        "slack": slack,
    }


def fetch(count: int) -> list[dict[str, Any]]:
    """Newest advisory first. A page that still fails after retries is omitted."""
    events, _warnings, _ok = collect(count)
    return events


def collect(count: int) -> tuple[list[dict[str, Any]], list[str], bool]:
    """Return events, warning lines, and whether the index was usable.

    A failed advisory page is not an event. The caller keeps that slug's
    previous state. bug_cves is None when the CSAF file could not be used.
    """
    warnings: list[str] = []
    try:
        html = _fetch(INDEX)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        warnings.append(
            f"WARNING cpu index download failed after 3 tries: {type(exc).__name__}"
        )
        return [], warnings, False
    seen: set[str] = set()
    slugs: list[str] = []
    for match in HREF_RE.finditer(html):
        slug = match.group(1).lower()
        if slug in seen:
            continue
        seen.add(slug)
        slugs.append(slug)
    if not slugs:
        warnings.append("WARNING cpu index has no CPU or CSPU advisory links")
        return [], warnings, False
    slugs.sort(key=lambda s: (_parse_slug(s)[2], _parse_slug(s)[1]), reverse=True)
    picked = slugs[: max(0, count)]
    log.info("cpu index slugs=%d picked=%d", len(slugs), len(picked))
    events = []
    for slug in picked:
        kind, month, year = _parse_slug(slug)
        title = f"{kind} {MONTH_NAME[month]} {year}"
        url = f"https://www.oracle.com/security-alerts/{slug}.html"
        try:
            page = _fetch(url)
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            warnings.append(
                "WARNING cpu advisory "
                f"{slug} download failed after 3 tries: {type(exc).__name__}. "
                "Previous state for this advisory is kept."
            )
            continue
        cves = parse_cves(page)
        sha = cve_sha(cves)
        bug_cves: dict[str, list[str]] | None
        if cves:
            bug_cves = fetch_bug_map(url, page)
            if bug_cves is None:
                warnings.append(
                    f"WARNING cpu CSAF for {slug} failed. "
                    "Previous bug map for this advisory is kept."
                )
        else:
            bug_cves = {}
        logged = -1 if bug_cves is None else len(bug_cves)
        log.info("cpu slug=%s cves=%d sha=%s bugs=%s", slug, len(cves), sha, logged)
        events.append(
            {
                "id": f"cpu:{slug}:{sha}",
                "source": "cpu",
                "title": title,
                "url": url,
                "payload": {"slug": slug, "cves": cves, "bug_cves": bug_cves},
            }
        )
    if picked and not events:
        warnings.append(
            "WARNING cpu every advisory download failed. Previous state is kept."
        )
    return events, warnings, True


def apply_state(
    events: list[dict[str, Any]],
    state: dict[str, Any] | None,
    report_seeded: bool = False,
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
            if not isinstance(row, dict):
                continue
            cves = list(row.get("cves") or [])
            raw_bugs = row.get("bug_cves") if isinstance(row.get("bug_cves"), dict) else {}
            advisories[slug] = {
                "sha": row.get("sha") or cve_sha(cves),
                "cves": cves,
                "bug_cves": {str(k): list(v) for k, v in raw_bugs.items()},
            }
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


def load_delivery(path: Path) -> tuple[dict[str, Any], str | None]:
    """Return threads plus pending messages.

    A missing file is an empty delivery state. A corrupt file is a
    warning and is not treated as an empty successful delivery.
    """
    empty: dict[str, Any] = {"threads": {}, "pending": []}
    if not path.is_file() or path.stat().st_size == 0:
        return empty, None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return empty, f"WARNING cpu slack state {path} is not JSON: {exc}"
    if not isinstance(data, dict):
        return empty, f"WARNING cpu slack state {path} is not a JSON object"
    threads = data.get("threads") if isinstance(data.get("threads"), dict) else {}
    pending = data.get("pending") if isinstance(data.get("pending"), list) else []
    return {"threads": threads, "pending": pending}, None


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
    delivery, error = load_delivery(path)
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

    Pending messages come first, including ones whose CVE set is already
    in the fetched baseline. mode none posts only real changes. latest
    also posts the newest advisory when it did not change. all does that
    for every watched advisory. Unchanged posts say "+0 -0 CVEs".
    """
    by_slug = {change["slug"]: change for change in changes}
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
        if slug in by_slug:
            change = by_slug[slug]
            ident = f"{slug}:{change['sha']}"
            if ident in seen:
                continue
            items.append(
                {
                    "id": ident,
                    "slug": slug,
                    "slack": f"{change['title']}\n{change['url']}\n{change['slack']}",
                    "changed": True,
                }
            )
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


def slack_text(changes: list[dict[str, Any]]) -> str:
    blocks = []
    for change in changes:
        blocks.append(f"{change['title']}\n{change['url']}\n{change['slack']}")
    return "\n\n".join(blocks) + ("\n" if blocks else "")


def load_advisories(path: Path) -> tuple[dict[str, Any] | None, str | None]:
    """Return the slug map, or None when the file is absent.

    A corrupt file is not fatal. The error string is a WARNING line and
    the caller still publishes the bug map from this run's downloads.
    """
    if not path.is_file() or path.stat().st_size == 0:
        return None, None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, f"WARNING cpu baseline {path} is not JSON: {exc}"
    if not isinstance(data, dict):
        return None, f"WARNING cpu baseline {path} is not a JSON object"
    advisories = data.get("advisories", data)
    if not isinstance(advisories, dict):
        return None, f"WARNING cpu baseline {path} has no advisories object"
    return advisories, None


def write_run(
    state_path: Path,
    diff_path: Path,
    slack_path: Path,
    seed_path: Path,
    bugs_path: Path,
    notify_dir: Path,
    slack_state_path: Path,
    *,
    count: int,
    ignore_state: bool = False,
    notify_mode: str = "none",
) -> None:
    events, warnings, index_ok = collect(count)
    degraded = state_path.with_name("cpu-degraded.txt")
    degraded.unlink(missing_ok=True)
    if ignore_state:
        previous, baseline_error = None, None
    else:
        previous, baseline_error = load_advisories(state_path)
    if baseline_error:
        warnings.append(baseline_error)
    for line in warnings:
        log.warning(line)
    if warnings:
        degraded.write_text("\n".join(warnings) + "\n", encoding="utf-8")
    if not index_ok:
        changes, advisories = [], {
            slug: {
                "sha": row.get("sha") or "",
                "cves": list(row.get("cves") or []),
                "bug_cves": dict(row.get("bug_cves") or {}),
            }
            for slug, row in (previous or {}).items()
            if isinstance(row, dict)
        }
    else:
        changes, advisories = apply_state(
            events,
            previous,
            report_seeded=(previous is None and notify_mode == "all"),
        )
    state_path.write_text(
        json.dumps({"advisories": advisories}, indent=2) + "\n",
        encoding="utf-8",
    )
    bugs_path.write_text(
        json.dumps({"bugs": bug_map_from_advisories(advisories)}, indent=2) + "\n",
        encoding="utf-8",
    )
    delivery, delivery_error = load_delivery(slack_state_path)
    if delivery_error:
        warnings.append(delivery_error)
        log.warning(delivery_error)
        degraded.write_text("\n".join(warnings) + "\n", encoding="utf-8")
    delivery["pending"] = merge_pending(delivery["pending"], changes)
    save_delivery(slack_state_path, delivery)
    write_notify_dir(
        notify_dir,
        notification_items(events, changes, notify_mode, delivery["pending"]),
    )
    if previous is None:
        seed_path.write_text("1\n", encoding="utf-8")
    else:
        seed_path.unlink(missing_ok=True)
    if changes:
        diff_path.write_text(
            json.dumps({"changes": changes}, indent=2) + "\n",
            encoding="utf-8",
        )
        slack_path.write_text(slack_text(changes), encoding="utf-8")
    else:
        diff_path.unlink(missing_ok=True)
        slack_path.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> None:
    if argv is None:
        argv = sys.argv[1:]
    if argv and argv[0] == "ack":
        if len(argv) != 3:
            raise SystemExit("usage: cpu_cves.py ack SLACK_STATE PENDING_ID")
        logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
        ack_pending(Path(argv[1]), argv[2])
        return
    parser = argparse.ArgumentParser(description="Diff Oracle CPU/CSPU CVE sets")
    parser.add_argument("--state", required=True, help="cpu-cves.json path")
    parser.add_argument("--diff", required=True, help="diff JSON path")
    parser.add_argument("--slack", required=True, help="short Slack text path")
    parser.add_argument("--seed-marker", required=True, help="written when --state was missing")
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
        Path(args.slack),
        Path(args.seed_marker),
        Path(args.bugs),
        Path(args.notify_dir),
        Path(args.slack_state),
        count=args.count,
        ignore_state=args.ignore_state,
        notify_mode=args.notify,
    )


if __name__ == "__main__":
    main()
