#!/usr/bin/env python3
"""Suggest hetzner-valkey-ALL-NEXT parameters for a full Percona Valkey build.

Reads what repo.percona.com publishes for the target repo (e.g. valkey-92) and
the previous one (valkey-91), on every APT codename/arch and YUM release/arch,
and picks one version-release per component:

  * version: the newest upstream version in the target repo (the previous repo
    is used only for a component the target repo has never had);
  * release: the highest release either repo has for that version, so an
    unchanged component matches the previous repo, and a rebuild never goes
    below the newest build already in the target repo (reprepro cannot
    republish a version-release that is older than the newest one);
  * --bump: one release higher than both, for a fresh build.

It then checks the picks against the bundle's dependency floors (debian/control
and the RPM spec on PACKAGING_BRANCH) and prints the job parameters.

Usage:
  valkey-build-params valkey-92
  valkey-build-params valkey-92 --prev-repo valkey-91 --channel testing --json
"""

from __future__ import annotations

import argparse
import gzip
import http.client
import json
import re
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Callable, Iterable

REPO_BASE = "https://repo.percona.com"
PACKAGING_RAW = "https://raw.githubusercontent.com/percona/valkey-packaging"
PACKAGING_API = "https://api.github.com/repos/percona/valkey-packaging"
USER_AGENT = "valkey-build-params"
TIMEOUT = 60
APT_ARCHES = ("amd64", "arm64")
YUM_ARCHES = ("x86_64", "aarch64")

Build = tuple[str, str]  # (upstream version, release number)
Inventory = dict[str, set[Build]]  # distro id ("deb:noble/amd64") -> builds
Fetch = Callable[[str], bytes]


@dataclass(frozen=True)
class Component:
    key: str
    deb: str
    rpm: str
    ver_param: str
    rel_param: str


# Release order of hetzner-valkey-ALL-NEXT (dependencies first, bundle last).
COMPONENTS = (
    Component("server", "percona-valkey-server", "percona-valkey", "VALKEY_VERSION", "SERVER_RELEASE"),
    Component("json", "percona-valkey-json", "percona-valkey-json", "VALKEY_JSON_VERSION", "JSON_RELEASE"),
    Component("bloom", "percona-valkey-bloom", "percona-valkey-bloom", "VALKEY_BLOOM_VERSION", "BLOOM_RELEASE"),
    Component("search", "percona-valkey-search", "percona-valkey-search", "VALKEY_SEARCH_VERSION", "SEARCH_RELEASE"),
    Component("ldap", "percona-valkey-ldap", "percona-valkey-ldap", "VALKEY_LDAP_VERSION", "LDAP_RELEASE"),
    Component("audit", "percona-valkey-audit", "percona-valkey-audit", "VALKEY_AUDIT_VERSION", "AUDIT_RELEASE"),
    Component("bundle", "percona-valkey-bundle", "percona-valkey-bundle", "VALKEY_BUNDLE_VERSION", "BUNDLE_RELEASE"),
    Component("admin", "percona-valkey-admin-server", "percona-valkey-admin-server", "VALKEY_ADMIN_VERSION", "ADMIN_RELEASE"),
)
BY_DEB = {c.deb: c for c in COMPONENTS}
BY_RPM = {c.rpm: c for c in COMPONENTS}


class NotFound(Exception):
    """The URL does not exist (HTTP 404)."""


@dataclass
class Choice:
    version: str
    release: str
    source: str  # "target" | "previous" | "bump" | "new"
    notes: list[str] = field(default_factory=list)
    missing_on: list[str] = field(default_factory=list)

    @property
    def full(self) -> str:
        return f"{self.version}-{self.release}"


# --- versions (dpkg ordering) -----------------------------------------------

def _order(ch: str) -> int:
    if ch == "~":
        return -1
    if ch.isalpha():
        return ord(ch)
    return ord(ch) + 256


def _compare_part(a: str, b: str) -> int:
    while a or b:
        a_txt = re.match(r"[^\d]*", a).group()
        b_txt = re.match(r"[^\d]*", b).group()
        for i in range(max(len(a_txt), len(b_txt))):
            ca = _order(a_txt[i]) if i < len(a_txt) else 0
            cb = _order(b_txt[i]) if i < len(b_txt) else 0
            if ca != cb:
                return 1 if ca > cb else -1
        a, b = a[len(a_txt):], b[len(b_txt):]
        a_num = re.match(r"\d*", a).group()
        b_num = re.match(r"\d*", b).group()
        if int(a_num or 0) != int(b_num or 0):
            return 1 if int(a_num or 0) > int(b_num or 0) else -1
        a, b = a[len(a_num):], b[len(b_num):]
    return 0


def _split_full(v: str) -> tuple[int, str, str]:
    epoch, _, rest = v.rpartition(":") if ":" in v else ("0", "", v)
    upstream, _, revision = rest.rpartition("-") if "-" in rest else (rest, "", "")
    return int(epoch or 0), upstream, revision


def compare_versions(a: str, b: str) -> int:
    """dpkg-style comparison of '[epoch:]upstream[-revision]'; returns -1, 0 or 1."""
    ea, ua, ra = _split_full(a)
    eb, ub, rb = _split_full(b)
    if ea != eb:
        return 1 if ea > eb else -1
    return _compare_part(ua, ub) or _compare_part(ra, rb)


def _newest(values: Iterable[str]) -> str | None:
    best = None
    for v in values:
        if best is None or compare_versions(v, best) > 0:
            best = v
    return best


def split_deb_version(raw: str) -> Build:
    """'1:9.2.0-3.noble' -> ('9.2.0', '3')."""
    _, upstream, revision = _split_full(raw)
    return upstream, revision.split(".", 1)[0]


def split_rpm_release(rel: str) -> str:
    """'3.el9' -> '3'."""
    return rel.split(".", 1)[0]


# --- index parsing -----------------------------------------------------------

def parse_index_links(html: str) -> list[str]:
    """Subdirectory names from an HTTP directory listing."""
    names = re.findall(r'href="([^"/?#]+)/"', html)
    return [n for n in names if n not in ("..", ".")]


def yum_release_dirs(names: Iterable[str]) -> list[str]:
    """Keep '9', '10', '2023'; skip the '9Server' / '9.0' aliases."""
    return [n for n in names if n.isdigit()]


def yum_dist_label(release: str) -> str:
    """'9' -> 'el9', '2023' -> 'amzn2023', '43' -> 'fc43'."""
    n = int(release)
    if n >= 2000:
        return f"amzn{n}"
    return f"el{n}" if n <= 15 else f"fc{n}"


def parse_packages_index(text: str) -> dict[str, set[Build]]:
    """APT Packages file -> {deb name: {(version, release)}} for known components."""
    found: dict[str, set[Build]] = {}
    for stanza in text.split("\n\n"):
        name = re.search(r"^Package:\s*(\S+)", stanza, re.M)
        version = re.search(r"^Version:\s*(\S+)", stanza, re.M)
        if name and version and name.group(1) in BY_DEB:
            found.setdefault(name.group(1), set()).add(split_deb_version(version.group(1)))
    return found


_RPM_PACKAGE = re.compile(r"<package\b[^>]*>(.*?)</package>", re.S)
_RPM_NAME = re.compile(r"<name>([^<]+)</name>")
_RPM_VERSION = re.compile(r'<version\b[^>]*\bver="([^"]+)"[^>]*\brel="([^"]+)"')


def parse_primary_xml(text: str) -> dict[str, set[Build]]:
    """YUM primary.xml -> {deb name: {(version, release)}} for known components.

    Parsed with regular expressions on purpose: no XML entity expansion on
    downloaded content.
    """
    found: dict[str, set[Build]] = {}
    for body in _RPM_PACKAGE.findall(text):
        name = _RPM_NAME.search(body)
        version = _RPM_VERSION.search(body)
        if name and version and name.group(1) in BY_RPM:
            deb = BY_RPM[name.group(1)].deb
            found.setdefault(deb, set()).add((version.group(1), split_rpm_release(version.group(2))))
    return found


# --- fetching ----------------------------------------------------------------

def http_fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:  # noqa: S310 (fixed https hosts)
            return resp.read()
    except urllib.error.HTTPError as err:
        if err.code == 404:
            raise NotFound(url) from err
        raise


def _try(fetch: Fetch, url: str) -> bytes | None:
    try:
        return fetch(url)
    except NotFound:
        return None


def _apt_index(fetch: Fetch, base: str, channel: str, codename: str, arch: str):
    data = _try(fetch, f"{base}/apt/dists/{codename}/{channel}/binary-{arch}/Packages")
    return f"deb:{codename}/{arch}", parse_packages_index(data.decode("utf-8", "replace")) if data else {}


def _yum_index(fetch: Fetch, base: str, channel: str, release: str, arch: str):
    dist = f"rpm:{yum_dist_label(release)}/{arch}"
    root = f"{base}/yum/{channel}/{release}/RPMS/{arch}"
    repomd = _try(fetch, f"{root}/repodata/repomd.xml")
    if not repomd:
        return dist, {}
    href = re.search(r'href="([^"]*primary\.xml\.gz)"', repomd.decode("utf-8", "replace"))
    data = _try(fetch, f"{root}/{href.group(1)}") if href else None
    return dist, parse_primary_xml(gzip.decompress(data).decode("utf-8", "replace")) if data else {}


def collect_inventory(repo: str, channel: str, fetch: Fetch = http_fetch) -> dict[str, Inventory]:
    """{component key: {distro id: {(version, release)}}} for one repo and channel."""
    base = f"{REPO_BASE}/{repo}"
    codenames = parse_index_links((_try(fetch, f"{base}/apt/dists/") or b"").decode())
    releases = yum_release_dirs(parse_index_links((_try(fetch, f"{base}/yum/{channel}/") or b"").decode()))
    jobs = [(_apt_index, base, channel, c, a) for c in codenames for a in APT_ARCHES]
    jobs += [(_yum_index, base, channel, r, a) for r in releases for a in YUM_ARCHES]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda j: j[0](fetch, *j[1:]), jobs))
    inventory: dict[str, Inventory] = {}
    for dist, packages in results:
        for deb, builds in packages.items():
            inventory.setdefault(BY_DEB[deb].key, {})[dist] = set(builds)
    return inventory


# --- choosing ----------------------------------------------------------------

def _all_builds(inv: Inventory) -> set[Build]:
    return set().union(*inv.values()) if inv else set()


def pick_build(target: Inventory, previous: Inventory, bump: bool,
               all_dists: Iterable[str] = (), version: str | None = None) -> Choice | None:
    """all_dists: every distro id the target repo publishes anything on, so a
    distro without this component at all is reported in missing_on too.

    version: build exactly this upstream version (the bundle follows the
    server); release 1 when neither repo has it yet."""
    in_target, in_prev = _all_builds(target), _all_builds(previous)
    if version is None:
        if not in_target and not in_prev:
            return None
        version = _newest(v for v, _ in (in_target or in_prev))
    release = _newest(r for v, r in in_target | in_prev if v == version)
    if release is None:
        return Choice(version, "1", "new")
    target_release = _newest(r for v, r in in_target if v == version)
    notes: list[str] = []

    prev_newest = _newest(f"{v}-{r}" for v, r in in_prev)
    if in_target and prev_newest and compare_versions(prev_newest.rsplit("-", 1)[0], version) > 0:
        notes.append(f"previous repo has a newer version {prev_newest}")

    if bump:
        if not release.isdigit():
            raise ValueError(f"cannot --bump non-numeric release {version}-{release}")
        return Choice(version, str(int(release) + 1), "bump", notes)
    if target_release == release:
        dists = set(target) | set(all_dists)
        missing = sorted(d for d in dists if (version, release) not in target.get(d, set()))
        return Choice(version, release, "target", notes, missing)
    if target_release:
        notes.append(f"target repo has only {version}-{target_release}")
    return Choice(version, release, "previous", notes)


def previous_repo(repo: str) -> str | None:
    """'valkey-92' -> 'valkey-91'; None when it cannot be derived (valkey-90)."""
    m = re.fullmatch(r"valkey-(\d)(\d)", repo)
    if not m or m.group(2) == "0":
        return None
    return f"valkey-{m.group(1)}{int(m.group(2)) - 1}"


# --- bundle floors -------------------------------------------------------------

def _first_floors(pairs: Iterable[tuple[str, str]], by_name: dict[str, Component]) -> dict[str, str]:
    """First floor per component (Depends/Requires come before Recommends)."""
    floors: dict[str, str] = {}
    for name, version in pairs:
        if name in by_name:
            floors.setdefault(by_name[name].key, version.rstrip(","))
    return floors


def parse_deb_floors(control: str) -> dict[str, str]:
    pairs = re.findall(r"(percona-valkey-[a-z-]+)\s*\(>=\s*([^)\s]+)\)", control)
    return _first_floors(pairs, BY_DEB)


def parse_rpm_floors(spec: str) -> dict[str, str]:
    pairs = []
    for line in re.findall(r"^(?:Requires|Recommends):(.*)$", spec, re.M):
        for item in line.split(","):
            m = re.match(r"\s*(percona-valkey[a-z-]*)(?:%\{\?_isa\})?\s*>=\s*(\S+)", item)
            if m:
                pairs.append(m.groups())
    return _first_floors(pairs, BY_RPM)


def check_floors(picks: dict[str, Choice], floors: dict[str, str], label: str) -> list[str]:
    problems = []
    for key, floor in floors.items():
        if not floor[:1].isdigit():
            continue  # ${source:Version} and the like: not a fixed floor
        choice = picks.get(key)
        if choice is None:
            problems.append(f"{key}: nothing to build, but the bundle needs >= {floor} ({label})")
        elif compare_versions(choice.full, floor) < 0:
            problems.append(f"{key} {choice.full} is below the bundle floor >= {floor} ({label})")
    return problems


def bundle_floor_problems(picks: dict[str, Choice], branch: str, fetch: Fetch) -> list[str]:
    problems = []
    sources = (
        ("bundle/debian/control", parse_deb_floors),
        ("bundle/rpm/percona-valkey-bundle.spec", parse_rpm_floors),
    )
    for path, parse in sources:
        data = _try(fetch, f"{PACKAGING_RAW}/{branch}/{path}")
        if data is None:
            problems.append(f"cannot read {path} on valkey-packaging branch {branch}: floors not checked")
            continue
        problems += check_floors(picks, parse(data.decode("utf-8", "replace")), f"{branch}:{path}")
    return problems


# --- packaging branch ----------------------------------------------------------

def suggest_packaging_branch(server_version: str, fetch: Fetch) -> tuple[str, list[str]]:
    """Of '<major>.<minor>' and '<version>', the existing branch with the newest commit."""
    minor = ".".join(server_version.split(".")[:2])
    dated = []
    for branch in dict.fromkeys((minor, server_version)):
        data = _try(fetch, f"{PACKAGING_API}/branches/{branch}")
        if data:
            dated.append((json.loads(data)["commit"]["commit"]["committer"]["date"], branch))
    if not dated:
        return server_version, [f"no valkey-packaging branch {minor} or {server_version}: check PACKAGING_BRANCH"]
    dated.sort(reverse=True)
    notes = [f"valkey-packaging branch {b} last changed {d}" for d, b in dated]
    return dated[0][1], notes


# --- output ----------------------------------------------------------------------

def job_params(repo: str, channel: str, picks: dict[str, Choice], branch: str) -> dict[str, str]:
    params = {"VALKEY_REPO": repo, "CHANNEL": channel}
    for c in COMPONENTS:
        if c.key in picks:
            params[c.ver_param] = picks[c.key].version
            if c.key == "server":
                params["GIT_BRANCH"] = picks[c.key].version
            params[c.rel_param] = picks[c.key].release
        if c.key == "server":
            params["PACKAGING_BRANCH"] = branch
    return params


def render_params(repo: str, channel: str, picks: dict[str, Choice], branch: str) -> str:
    params = job_params(repo, channel, picks, branch)
    lines = [f"hetzner-valkey-ALL-NEXT: full build for {repo}/{channel}", ""]
    lines += [f"{k} = {v}" for k, v in params.items()]
    lines += ["", "Keep the job defaults for PKG_*, IMG_*, RUN_TESTS, ALLOW_REPUBLISH and AUDIT_GIT_BRANCH."]
    return "\n".join(lines)


def describe(c: Component, choice: Choice | None, repo: str, prev: str | None) -> list[str]:
    if choice is None:
        return [f"  {c.key:<7} not published in {repo} or {prev}: set it by hand"]
    what = {
        "target": f"republish: newest build in {repo}",
        "previous": f"new in {repo}: matches {prev}",
        "bump": "new release, higher than both repos",
        "new": "first build of this version (follows the server)",
    }[choice.source]
    lines = [f"  {c.key:<7} {choice.full:<12} {what}"]
    lines += [f"          note: {n.replace('previous repo', prev or 'previous repo').replace('target repo', repo)}"
              for n in choice.notes]
    if choice.missing_on:
        lines.append(f"          not yet on: {', '.join(choice.missing_on)}")
    return lines


def render_json(repo: str, channel: str, picks: dict[str, Choice], branch: str, warnings: list[str]) -> str:
    return json.dumps({
        "params": job_params(repo, channel, picks, branch),
        "components": {k: {"version": c.version, "release": c.release, "source": c.source,
                           "notes": c.notes, "missing_on": c.missing_on} for k, c in picks.items()},
        "warnings": warnings,
    }, indent=2)


# --- main --------------------------------------------------------------------------

NETWORK_ERRORS = (OSError, http.client.HTTPException, EOFError)  # URLError, timeouts, bad gzip


def gather(args: argparse.Namespace, prev: str | None, fetch: Fetch, warnings: list[str]):
    """Read both repos, pick every component, check the bundle floors."""
    target_inv = collect_inventory(args.repo, args.channel, fetch)
    prev_inv = collect_inventory(prev, args.channel, fetch) if prev else {}
    for name, found in ((args.repo, target_inv), (prev, prev_inv)):
        if name and not found:
            warnings.append(f"{name}/{args.channel}: nothing published (check the repo name and channel)")
    all_dists = sorted(set().union(*(set(i) for i in target_inv.values())))
    picks: dict[str, Choice] = {}
    for c in COMPONENTS:
        # The bundle is versioned with the server: never take another version from a repo.
        pinned = picks["server"].version if c.key == "bundle" and "server" in picks else None
        choice = pick_build(target_inv.get(c.key, {}), prev_inv.get(c.key, {}), args.bump,
                            all_dists, version=pinned)
        if choice is None:
            warnings.append(f"{c.key}: not published in {args.repo} or {prev}, set it by hand")
        else:
            picks[c.key] = choice

    if args.packaging_branch:
        branch, branch_notes = args.packaging_branch, []
    elif "server" in picks:
        branch, branch_notes = suggest_packaging_branch(picks["server"].version, fetch)
    else:
        branch, branch_notes = "?", ["no server build found: set PACKAGING_BRANCH by hand"]
    floor_problems = bundle_floor_problems(picks, branch, fetch) if branch != "?" else []
    warnings += floor_problems
    return picks, branch, branch_notes, floor_problems


def main(argv: list[str] | None = None, fetch: Fetch = http_fetch) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("repo", help="target repo, e.g. valkey-92")
    ap.add_argument("--prev-repo", help="previous repo (default: derived, valkey-92 -> valkey-91)")
    ap.add_argument("--channel", default="testing", choices=("testing", "experimental"))
    ap.add_argument("--bump", action="store_true", help="one release above both repos for every component")
    ap.add_argument("--packaging-branch", help="valkey-packaging branch (default: newest of <x.y> and <x.y.z>)")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    args = ap.parse_args(argv)

    if not re.fullmatch(r"valkey-\d+", args.repo):
        ap.error(f"unexpected repo name {args.repo!r}, expected valkey-NN")
    prev = args.prev_repo or previous_repo(args.repo)
    warnings: list[str] = []
    if prev is None:
        warnings.append(f"no previous repo for {args.repo}: pass --prev-repo to compare release numbers")

    try:
        picks, branch, branch_notes, floor_problems = gather(args, prev, fetch, warnings)
    except NETWORK_ERRORS as err:
        print(f"error: cannot read repo data: {err}", file=sys.stderr)
        return 2
    except ValueError as err:
        print(f"error: {err}", file=sys.stderr)
        return 2

    if args.json:
        print(render_json(args.repo, args.channel, picks, branch, warnings + branch_notes))
    else:
        out = [render_params(args.repo, args.channel, picks, branch), "", "Per component:"]
        for c in COMPONENTS:
            out += describe(c, picks.get(c.key), args.repo, prev)
        if branch_notes:
            out += ["", "Packaging branch:"] + [f"  {n}" for n in branch_notes]
        out += ["", "Warnings:"] + ([f"  {w}" for w in warnings] or ["  none (bundle floors OK)"])
        print("\n".join(out))
    return 1 if floor_problems or len(picks) < len(COMPONENTS) else 0


if __name__ == "__main__":
    sys.exit(main())
