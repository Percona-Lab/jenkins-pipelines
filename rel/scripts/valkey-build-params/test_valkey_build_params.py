"""Tests for valkey_build_params.py (no network: fetchers are faked)."""

import gzip
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import valkey_build_params as vbp  # noqa: E402


# --- version handling -------------------------------------------------------

@pytest.mark.parametrize(
    "a,b,expected",
    [
        ("1.0.10", "1.0.9", 1),
        ("9.2.0", "9.2.0", 0),
        ("9.2.0~rc1", "9.2.0", -1),
        ("1.2.1", "1.2.0", 1),
        ("1.0.0-5", "1.0.0-4", 1),
        ("1.0.0-4", "1.0.0", 1),
        ("9.2.0", "9.2.0-3", -1),
        ("1:9.1.0", "9.2.0", 1),
    ],
)
def test_compare_versions(a, b, expected):
    assert vbp.compare_versions(a, b) == expected


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("1:9.2.0-3.noble", ("9.2.0", "3")),
        ("1.3.0-2.bookworm", ("1.3.0", "2")),
        ("1.0.0-5", ("1.0.0", "5")),
    ],
)
def test_split_deb_version(raw, expected):
    assert vbp.split_deb_version(raw) == expected


def test_split_rpm_release():
    assert vbp.split_rpm_release("3.el9") == "3"
    assert vbp.split_rpm_release("12.amzn2023") == "12"
    assert vbp.split_rpm_release("4") == "4"


# --- index parsing ----------------------------------------------------------

PACKAGES = """Package: percona-valkey-server
Version: 1:9.2.0-3.noble
Architecture: amd64

Package: percona-valkey-server
Version: 1:9.2.0-2.noble
Architecture: amd64

Package: percona-valkey-server-dbgsym
Version: 1:9.2.0-3.noble

Package: percona-valkey-admin-server
Version: 1.3.0-1.noble
"""


def test_parse_packages_index_keeps_only_known_packages():
    got = vbp.parse_packages_index(PACKAGES)
    assert got == {
        "percona-valkey-server": {("9.2.0", "2"), ("9.2.0", "3")},
        "percona-valkey-admin-server": {("1.3.0", "1")},
    }


PRIMARY = """<?xml version="1.0" encoding="UTF-8"?>
<metadata xmlns="http://linux.duke.edu/metadata/common" packages="3">
<package type="rpm"><name>percona-valkey</name><arch>x86_64</arch>
  <version epoch="0" ver="9.2.0" rel="3.el9"/></package>
<package type="rpm"><name>percona-valkey-debuginfo</name><arch>x86_64</arch>
  <version epoch="0" ver="9.2.0" rel="3.el9"/></package>
<package type="rpm"><name>percona-valkey-audit</name><arch>x86_64</arch>
  <version epoch="0" ver="1.0.0" rel="5.el9"/></package>
</metadata>
"""


def test_parse_primary_xml_maps_rpm_names_to_deb_names():
    got = vbp.parse_primary_xml(PRIMARY)
    assert got == {
        "percona-valkey-server": {("9.2.0", "3")},
        "percona-valkey-audit": {("1.0.0", "5")},
    }


def test_parse_index_links_keeps_subdirectories():
    html = '<a href="../">..</a><a href="noble/">noble/</a><a href="9/">9/</a><a href="x.txt">x</a>'
    assert vbp.parse_index_links(html) == ["noble", "9"]


def test_yum_release_dirs_skips_aliases():
    assert vbp.yum_release_dirs(["10", "10Server", "2023", "8", "8.0", "9", "9Server"]) == ["10", "2023", "8", "9"]


# --- choosing a version-release ---------------------------------------------

def inv(**dists):
    """inv(noble={("9.2.0","3")}) -> {"deb:noble/amd64": {...}}"""
    return {"deb:%s/amd64" % d: set(builds) for d, builds in dists.items()}


def test_pick_takes_newest_target_build():
    choice = vbp.pick_build(inv(noble={("9.2.0", "2"), ("9.2.0", "3")}), {}, bump=False)
    assert (choice.version, choice.release) == ("9.2.0", "3")
    assert choice.source == "target"


def test_pick_matches_higher_previous_release_for_same_version():
    # the admin case: valkey-92 has 1.3.0-1, valkey-91 already has 1.3.0-2
    choice = vbp.pick_build(inv(noble={("1.3.0", "1")}), inv(noble={("1.3.0", "2")}), bump=False)
    assert (choice.version, choice.release) == ("1.3.0", "2")
    assert choice.source == "previous"


def test_pick_ignores_older_versions_in_previous_repo():
    choice = vbp.pick_build(inv(noble={("9.2.0", "3")}), inv(noble={("9.1.3", "9")}), bump=False)
    assert (choice.version, choice.release) == ("9.2.0", "3")


def test_pick_notes_newer_version_in_previous_repo():
    choice = vbp.pick_build(inv(noble={("1.2.1", "2")}), inv(noble={("1.2.2", "1")}), bump=False)
    assert (choice.version, choice.release) == ("1.2.1", "2")
    assert any("1.2.2-1" in n for n in choice.notes)


def test_pick_falls_back_to_previous_repo_when_target_is_empty():
    choice = vbp.pick_build({}, inv(noble={("1.0.3", "2")}), bump=False)
    assert (choice.version, choice.release) == ("1.0.3", "2")
    assert choice.source == "previous"


def test_pick_returns_none_when_nothing_is_published():
    assert vbp.pick_build({}, {}, bump=False) is None


def test_pick_bump_goes_past_both_repos():
    choice = vbp.pick_build(inv(noble={("1.3.0", "1")}), inv(noble={("1.3.0", "2")}), bump=True)
    assert (choice.version, choice.release) == ("1.3.0", "3")
    assert choice.source == "bump"


def test_pick_release_numbers_compare_numerically():
    choice = vbp.pick_build(inv(noble={("1.0.0", "9"), ("1.0.0", "10")}), {}, bump=False)
    assert choice.release == "10"


def test_pick_reports_distros_missing_the_chosen_build():
    target = inv(noble={("9.2.0", "3")}, jammy={("9.2.0", "2")})
    choice = vbp.pick_build(target, {}, bump=False)
    assert choice.missing_on == ["deb:jammy/amd64"]


# --- bundle floors ----------------------------------------------------------

CONTROL = """Package: percona-valkey-bundle
Depends: ${misc:Depends},
 percona-valkey-server (>= 9.2.0),
 percona-valkey-audit (>= 1.0.0-4),
 percona-valkey-admin-server (>= 1.2.0),
"""

SPEC = """Requires:       percona-valkey%{?_isa} >= 9.2.0
Requires:       percona-valkey-audit%{?_isa} >= 1.0.0-4
"""


def test_parse_deb_floors():
    assert vbp.parse_deb_floors(CONTROL) == {
        "server": "9.2.0",
        "audit": "1.0.0-4",
        "admin": "1.2.0",
    }


def test_parse_rpm_floors():
    assert vbp.parse_rpm_floors(SPEC) == {"server": "9.2.0", "audit": "1.0.0-4"}


def test_check_floors_flags_builds_below_the_floor():
    picks = {"audit": vbp.Choice("1.0.0", "1", "target"), "server": vbp.Choice("9.2.0", "3", "target")}
    problems = vbp.check_floors(picks, {"audit": "1.0.0-4", "server": "9.2.0"}, "debian/control")
    assert problems == ["audit 1.0.0-1 is below the bundle floor >= 1.0.0-4 (debian/control)"]


def test_check_floors_flags_missing_component():
    problems = vbp.check_floors({}, {"server": "9.2.0"}, "rpm spec")
    assert problems == ["server: nothing to build, but the bundle needs >= 9.2.0 (rpm spec)"]


# --- repo naming ------------------------------------------------------------

@pytest.mark.parametrize("repo,prev", [("valkey-92", "valkey-91"), ("valkey-81", "valkey-80"), ("valkey-90", None), ("other", None)])
def test_previous_repo(repo, prev):
    assert vbp.previous_repo(repo) == prev


# --- end to end with a fake fetcher -----------------------------------------

def fake_site(files):
    def fetch(url):
        if url not in files:
            raise vbp.NotFound(url)
        return files[url]
    return fetch


def gz(text):
    return gzip.compress(text.encode())


def test_collect_inventory_reads_apt_and_yum():
    base = "https://repo.percona.com/valkey-92"
    files = {
        base + "/apt/dists/": b'<a href="noble/">',
        base + "/apt/dists/noble/testing/binary-amd64/Packages": PACKAGES.encode(),
        base + "/yum/testing/": b'<a href="9/"><a href="9Server/">',
        base + "/yum/testing/9/RPMS/x86_64/repodata/repomd.xml":
            b'<location href="repodata/abc-primary.xml.gz"/><location href="repodata/abc-other.xml.gz"/>',
        base + "/yum/testing/9/RPMS/x86_64/repodata/abc-primary.xml.gz": gz(PRIMARY),
    }
    got = vbp.collect_inventory("valkey-92", "testing", fake_site(files))
    assert got["server"] == {
        "deb:noble/amd64": {("9.2.0", "2"), ("9.2.0", "3")},
        "rpm:el9/x86_64": {("9.2.0", "3")},
    }
    assert got["audit"] == {"rpm:el9/x86_64": {("1.0.0", "5")}}


def test_render_params_lists_every_job_parameter():
    picks = {c.key: vbp.Choice("1.0.0", "1", "target") for c in vbp.COMPONENTS}
    picks["server"] = vbp.Choice("9.2.0", "3", "target")
    out = vbp.render_params("valkey-92", "testing", picks, "9.2")
    for name in ("VALKEY_REPO = valkey-92", "CHANNEL = testing", "VALKEY_VERSION = 9.2.0",
                 "GIT_BRANCH = 9.2.0", "PACKAGING_BRANCH = 9.2", "SERVER_RELEASE = 3",
                 "VALKEY_ADMIN_VERSION = 1.0.0", "ADMIN_RELEASE = 1", "BUNDLE_RELEASE = 1"):
        assert name in out


def test_report_json_round_trips():
    picks = {"server": vbp.Choice("9.2.0", "3", "target")}
    data = json.loads(vbp.render_json("valkey-92", "testing", picks, "9.2", ["w"]))
    assert data["params"]["VALKEY_VERSION"] == "9.2.0"
    assert data["warnings"] == ["w"]


# --- review follow-ups --------------------------------------------------------

def test_check_floors_skips_substitution_variables():
    picks = {"server": vbp.Choice("9.2.0", "3", "target")}
    assert vbp.check_floors(picks, {"server": "${source:Version}"}, "x") == []


def test_parse_deb_floors_keeps_first_occurrence_and_strips_commas():
    control = "Depends: percona-valkey-server (>= 9.2.0)\nRecommends: percona-valkey-server (>= 9.3.0)\n"
    assert vbp.parse_deb_floors(control) == {"server": "9.2.0"}


def test_parse_rpm_floors_reads_recommends_and_comma_lists():
    spec = "Requires: percona-valkey >= 9.2.0, percona-valkey-json >= 1.0.3\nRecommends: percona-valkey-admin-server >= 1.2.0\n"
    assert vbp.parse_rpm_floors(spec) == {"server": "9.2.0", "json": "1.0.3", "admin": "1.2.0"}


def test_pick_bump_refuses_non_numeric_release():
    with pytest.raises(ValueError, match="non-numeric release"):
        vbp.pick_build(inv(noble={("9.2.0", "1percona1")}), {}, bump=True)


def test_pick_reports_distros_without_the_component():
    choice = vbp.pick_build(inv(noble={("9.2.0", "3")}), {}, bump=False,
                            all_dists=["deb:noble/amd64", "deb:noble/arm64"])
    assert choice.missing_on == ["deb:noble/arm64"]


def test_yum_dist_labels():
    assert vbp.yum_dist_label("9") == "el9"
    assert vbp.yum_dist_label("10") == "el10"
    assert vbp.yum_dist_label("2023") == "amzn2023"
    assert vbp.yum_dist_label("43") == "fc43"


def test_main_warns_when_a_repo_is_empty(capsys):
    rc = vbp.main(["valkey-92", "--packaging-branch", "9.2"], fetch=fake_site({}))
    out = capsys.readouterr().out
    assert "valkey-92/testing: nothing published" in out
    assert "valkey-91/testing: nothing published" in out
    assert rc == 1


def test_main_network_error_exits_2(capsys):
    def broken(url):
        raise vbp.urllib.error.URLError("timed out")
    assert vbp.main(["valkey-92"], fetch=broken) == 2
    assert "cannot read repo data" in capsys.readouterr().err


# --- the bundle follows the server version -------------------------------------

def test_pick_pinned_version_ignores_other_versions_in_previous_repo():
    # valkey-92 never had a bundle; valkey-91 has 9.1.3: the 9.2.0 build must not get 9.1.3.
    choice = vbp.pick_build({}, inv(noble={("9.1.3", "4")}), bump=False, version="9.2.0")
    assert (choice.version, choice.release, choice.source) == ("9.2.0", "1", "new")


def test_pick_pinned_version_keeps_existing_release_of_that_version():
    target = inv(noble={("9.1.3", "1"), ("9.2.0", "2")})
    choice = vbp.pick_build(target, {}, bump=False, version="9.2.0")
    assert (choice.version, choice.release, choice.source) == ("9.2.0", "2", "target")


def test_pick_pinned_version_bump_starts_at_one_when_unpublished():
    choice = vbp.pick_build({}, inv(noble={("9.1.3", "4")}), bump=True, version="9.2.0")
    assert (choice.version, choice.release) == ("9.2.0", "1")


def bundle_site(target_pkgs, prev_pkgs):
    files = {}
    for repo, pkgs in (("valkey-93", target_pkgs), ("valkey-92", prev_pkgs)):
        base = f"https://repo.percona.com/{repo}"
        files[base + "/apt/dists/"] = b'<a href="noble/">'
        files[base + "/apt/dists/noble/testing/binary-amd64/Packages"] = "\n\n".join(
            f"Package: {name}\nVersion: {ver}" for name, ver in pkgs).encode()
    return fake_site(files)


def test_main_bundle_version_follows_server_not_previous_repo(capsys):
    fetch = bundle_site(
        [("percona-valkey-server", "9.3.0-1.noble")],
        [("percona-valkey-server", "9.2.0-3.noble"), ("percona-valkey-bundle", "9.2.0-2.noble")],
    )
    vbp.main(["valkey-93", "--packaging-branch", "9.3", "--json"], fetch=fetch)
    params = json.loads(capsys.readouterr().out)["params"]
    assert params["VALKEY_VERSION"] == "9.3.0"
    assert params["VALKEY_BUNDLE_VERSION"] == "9.3.0"
    assert params["BUNDLE_RELEASE"] == "1"
