"""Oracle HTTP fetch, HTML and CSAF parsing, and CVE-set diff."""

from __future__ import annotations

import hashlib
import http.client
import json
import logging
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, NamedTuple

from oracle_cpu.diagnostics import exception_text, issue

log = logging.getLogger("oracle_cpu")

INDEX = "https://www.oracle.com/security-alerts/"
UA = "Mozilla/5.0 (compatible; percona-jenkins-oracle-cpu/1.0)"
# IncompleteRead is an HTTPException, not a URLError or OSError. A truncated
# body must retry like a connection failure, then fall back per advisory.
FETCH_ERRORS = (
    urllib.error.URLError,
    TimeoutError,
    OSError,
    http.client.HTTPException,
)
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

_REDIRECTS = frozenset({301, 302, 303, 307, 308})


def _http_get(url: str) -> tuple[int, str, str]:
    """One GET without following redirects. Returns status, body, Location."""

    class _NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    ctx = ssl.create_default_context()
    opener = urllib.request.build_opener(
        _NoRedirect,
        urllib.request.HTTPSHandler(context=ctx),
    )
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with opener.open(request, timeout=120) as resp:
            status = int(getattr(resp, "status", None) or resp.getcode())
            location = resp.headers.get("Location") or ""
            if status in _REDIRECTS:
                return status, "", location
            return status, resp.read().decode("utf-8", "replace"), ""
    except urllib.error.HTTPError as exc:
        location = ""
        if exc.headers is not None:
            location = exc.headers.get("Location") or ""
        return int(exc.code), "", location


def _follow(url: str) -> tuple[str | None, str]:
    """Follow redirects. The second value is the hop list.

    A URL already seen stops the walk. The count stops a chain of
    distinct URLs that never repeats.
    """
    hops: list[str] = []
    current = url
    seen: set[str] = set()
    for _ in range(50):
        if current in seen:
            hops.append(f"repeat {current}")
            return None, " -> ".join(hops)
        seen.add(current)
        try:
            status, body, location = _http_get(current)
        except FETCH_ERRORS as exc:
            hops.append(f"error {current}: {type(exc).__name__}")
            return None, " -> ".join(hops) + "\n" + exception_text(exc)
        if status in _REDIRECTS and location:
            nxt = urllib.parse.urljoin(current, location)
            hops.append(f"{status} {current} -> {nxt}")
            current = nxt
            continue
        hops.append(f"{status} {current}")
        if 200 <= status < 300:
            return body, " -> ".join(hops)
        return None, " -> ".join(hops)
    hops.append("too many redirects")
    return None, " -> ".join(hops)


def record_download(
    notes: list[dict[str, Any]],
    *,
    area: str,
    slug: str,
    label: str,
    failed: list[str],
    exc: BaseException | None,
) -> None:
    """One failure or recovery note for a finished download.

    Callers still decide whether a body is usable and whether a cache
    stays. exc is set only when every attempt failed.
    """
    if exc is not None:
        detail = failed[-1].splitlines()[0] if failed else type(exc).__name__
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area=area,
                slug=slug,
                attempts=len(failed) or 3,
                message=f"{label} download failed after {len(failed) or 3} attempts. {detail}",
                exception="\n".join(failed) if failed else exception_text(exc),
            )
        )
        return
    if not failed:
        return
    notes.append(
        issue(
            level="info",
            outcome="recovered",
            area=area,
            slug=slug,
            attempts=len(failed) + 1,
            message=f"{label} download failed {len(failed)} time(s), then succeeded.",
            exception="\n".join(failed),
        )
    )


def _fetch(url: str, failed: list[str]) -> str:
    """GET url, pause one second, and try again up to 3 times.

    failed collects one line per attempt, including the redirect target
    that returned the error. A later success leaves those lines for an
    informational note. The raised error is the last attempt.
    """
    failed.clear()
    last = "no attempt"
    for attempt in range(1, 4):
        body, detail = _follow(url)
        if body is not None:
            time.sleep(1)
            return body
        last = detail
        failed.append(detail)
        log.info("cpu fetch attempt %s/3 failed url=%s detail=%s", attempt, url, detail)
        time.sleep(1)
    raise urllib.error.URLError(last)


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
    # A JSON object without a vulnerabilities list is not a CSAF document.
    # Treating it as an empty map would erase the cached bug ids.
    if "vulnerabilities" not in data or not isinstance(data.get("vulnerabilities"), list):
        raise ValueError("CSAF document has no vulnerabilities list")
    vulns = data["vulnerabilities"]
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


def fetch_bug_map(
    page_url: str,
    html: str,
    notes: list[dict[str, Any]],
    slug: str,
) -> dict[str, list[str]] | None:
    """Return the bug map, or None when the CSAF file could not be used.

    None tells the caller to keep the previous bug map. A missing link
    and a document that is not CSAF are both unavailable, not an empty map.
    Recovered download attempts are informational. The exhausted failure
    is a warning.
    """
    match = CSAF_RE.search(html)
    if not match:
        log.warning("WARNING cpu no CSAF link url=%s", page_url)
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area="csaf",
                slug=slug,
                message=f"{slug} page has no CSAF link.",
            )
        )
        return None
    csaf_url = urllib.parse.urljoin(page_url, match.group(1))
    failed: list[str] = []
    try:
        raw = _fetch(csaf_url, failed)
    except FETCH_ERRORS as exc:
        log.warning("WARNING cpu CSAF fetch failed url=%s", csaf_url)
        record_download(
            notes,
            area="csaf",
            slug=slug,
            label=f"{slug} CSAF",
            failed=failed,
            exc=exc,
        )
        return None
    record_download(notes, area="csaf", slug=slug, label=f"{slug} CSAF", failed=failed, exc=None)
    try:
        data = json.loads(raw)
        bug_map = bug_map_from_csaf(data)
    except (json.JSONDecodeError, ValueError, TypeError, AttributeError) as exc:
        log.warning("WARNING cpu CSAF unusable url=%s err=%s", csaf_url, exc)
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area="csaf",
                slug=slug,
                attempts=1,
                message=f"{slug} CSAF document is unusable: {exc}.",
                exception=exception_text(exc),
            )
        )
        return None
    log.info("cpu CSAF url=%s bugs=%d", csaf_url, len(bug_map))
    return bug_map


def merge_cve_lists(maps: list[dict[str, list[str]]]) -> dict[str, list[str]]:
    """Union bug-id to CVE-list maps. CVE ids in each list stay sorted."""
    merged: dict[str, list[str]] = {}
    for bug_map in maps:
        for bug, cves in bug_map.items():
            bucket = merged.setdefault(str(bug), [])
            for cve in cves:
                if cve not in bucket:
                    bucket.append(cve)
    for cves in merged.values():
        cves.sort()
    return merged


def bug_map_from_advisories(advisories: dict[str, Any]) -> dict[str, list[str]]:
    """Flatten per-advisory bug maps, including ones kept from older state."""
    maps: list[dict[str, list[str]]] = []
    for row in advisories.values():
        if not isinstance(row, dict):
            continue
        bugs = row.get("bug_cves") or {}
        if isinstance(bugs, dict):
            maps.append(bugs)
    return merge_cve_lists(maps)


def _cve_delta(old: list[str], new: list[str]) -> tuple[list[str], list[str]]:
    old_set = set(old)
    new_set = set(new)
    return sorted(new_set - old_set), sorted(old_set - new_set)


def format_slack(
    old: list[str],
    new: list[str],
    added: list[str] | None = None,
    removed: list[str] | None = None,
) -> str:
    """Slack text for one CVE-set change. A first list is only the count."""
    if added is None or removed is None:
        added, removed = _cve_delta(old, new)
    if not old:
        return f"+{len(added)} CVEs"
    lines = [f"+{len(added)} -{len(removed)} CVEs"]
    lines.extend(f"+ {cve}" for cve in added)
    lines.extend(f"- {cve}" for cve in removed)
    return "\n".join(lines)


def describe_change(old: list[str], new: list[str]) -> dict[str, Any] | None:
    """Return a diff record, or None when the CVE sets match."""
    added, removed = _cve_delta(old, new)
    if not added and not removed:
        return None
    return {
        "sha": cve_sha(new),
        "cves": list(new),
        "added": added,
        "removed": removed,
        "slack": format_slack(old, new, added, removed),
    }


class Collection(NamedTuple):
    """One Oracle poll. notes is the same list the caller passed in.

    advisories are flat records: slug, title, url, cves, bug_cves.
    bug_cves is None when CSAF could not be used, and {} when the
    document is valid and has no bug ids.
    """

    advisories: list[dict[str, Any]]
    index_ok: bool
    notes: list[dict[str, Any]]
    stats: dict[str, Any]


def collect(
    count: int,
    notes: list[dict[str, Any]] | None = None,
) -> Collection:
    """Return events, index status, notes, and fetch counts.

    A failed advisory page is omitted. The caller keeps that slug's
    previous state. bug_cves is None when the CSAF file could not be used.
    notes is filled as advisories are handled, so a later failure can
    still flush what was already recorded.
    """
    if notes is None:
        notes = []
    stats: dict[str, Any] = {
        "picked": 0,
        "refreshed": 0,
    }
    failed: list[str] = []
    try:
        html = _fetch(INDEX, failed)
    except FETCH_ERRORS as exc:
        record_download(notes, area="index", slug="", label="Index", failed=failed, exc=exc)
        return Collection([], False, notes, stats)
    record_download(notes, area="index", slug="", label="Index", failed=failed, exc=None)
    seen: set[str] = set()
    slugs: list[str] = []
    for match in HREF_RE.finditer(html):
        slug = match.group(1).lower()
        if slug in seen:
            continue
        seen.add(slug)
        slugs.append(slug)
    if not slugs:
        message = "Index has no CPU or CSPU advisory links."
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area="index",
                message=message,
            )
        )
        return Collection([], False, notes, stats)
    slugs.sort(key=lambda s: (_parse_slug(s)[2], _parse_slug(s)[1]), reverse=True)
    picked = slugs[: max(0, count)]
    stats["picked"] = len(picked)
    log.info("cpu index slugs=%d picked=%d", len(slugs), len(picked))
    advisories = []
    for slug in picked:
        kind, month, year = _parse_slug(slug)
        title = f"{kind} {MONTH_NAME[month]} {year}"
        url = f"https://www.oracle.com/security-alerts/{slug}.html"
        failed = []
        try:
            page = _fetch(url, failed)
        except FETCH_ERRORS as exc:
            record_download(notes, area="page", slug=slug, label=slug, failed=failed, exc=exc)
            continue
        record_download(notes, area="page", slug=slug, label=slug, failed=failed, exc=None)
        cves = parse_cves(page)
        if not cves:
            message = f"{slug} page has no CVE ids."
            notes.append(
                issue(
                    level="warning",
                    outcome="failed",
                    area="page",
                    slug=slug,
                    message=message,
                )
            )
            continue
        stats["refreshed"] += 1
        sha = cve_sha(cves)
        bug_cves = fetch_bug_map(url, page, notes, slug)
        logged = -1 if bug_cves is None else len(bug_cves)
        log.info("cpu slug=%s cves=%d sha=%s bugs=%s", slug, len(cves), sha, logged)
        advisories.append(
            {
                "slug": slug,
                "title": title,
                "url": url,
                "cves": cves,
                "bug_cves": bug_cves,
            }
        )
    if picked and not advisories:
        message = "Every advisory download failed."
        notes.append(
            issue(
                level="warning",
                outcome="failed",
                area="page",
                message=message,
            )
        )
    return Collection(advisories, True, notes, stats)

