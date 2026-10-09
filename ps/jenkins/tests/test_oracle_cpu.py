"""Offline checks for the Oracle CPU poller."""

from __future__ import annotations

import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

from oracle_cpu.core import (
    Collection,
    _component_name,
    _fetch,
    bug_map_from_csaf,
    cve_sha,
    format_slack,
    mysql_cve_components,
    parse_cves,
)
from oracle_cpu.diagnostics import load_report, write_status
from oracle_cpu.pipeline import apply_state, notification_items, poll
from oracle_cpu.state import ack_state, atomic_write, load_state, merge_pending, persistent_signature


_MISSING = object()


def _fresh(slug: str, cves: list[str], bugs: object = _MISSING) -> dict:
    return {
        "slug": slug,
        "title": slug,
        "url": f"https://www.oracle.com/security-alerts/{slug}.html",
        "cves": cves,
        "bug_cves": {} if bugs is _MISSING else bugs,
    }


class OracleCpuTest(unittest.TestCase):
    def test_bug_map_rejects_a_document_without_vulnerabilities(self) -> None:
        with self.assertRaises(ValueError):
            bug_map_from_csaf({"document": {}})

    def test_bug_map_keeps_only_bug_ids(self) -> None:
        bugs = bug_map_from_csaf(
            {
                "vulnerabilities": [
                    {
                        "cve": "CVE-2026-1000",
                        "ids": [
                            {
                                "system_name": "Oracle Bug ID of MySQL Server",
                                "text": "38888307",
                            }
                        ],
                    }
                ]
            }
        )
        self.assertEqual(bugs, {"38888307": ["CVE-2026-1000"]})

    def test_mysql_counts_and_components_follow_the_cve_diff(self) -> None:
        text = (
            "Vulnerability in the MySQL Server product of Oracle MySQL "
            "(component: Server: InnoDB)."
        )
        nested = (
            "Vulnerability in the Oracle Communications Unified Assurance product "
            "of Oracle Communications (component: Core (MySQL Server))."
        )
        self.assertEqual(_component_name(text), "Server: InnoDB")
        self.assertEqual(_component_name(nested), "Core (MySQL Server)")
        components = mysql_cve_components(
            {
                "vulnerabilities": [
                    {
                        "cve": "CVE-2026-1000",
                        "notes": [{"category": "description", "text": text}],
                    },
                    {
                        "cve": "CVE-2026-1001",
                        "notes": [
                            {
                                "category": "description",
                                "text": text.replace("InnoDB", "Optimizer"),
                            }
                        ],
                    },
                    {
                        "cve": "CVE-2026-2000",
                        "notes": [{"category": "description", "text": nested}],
                    },
                ]
            }
        )
        self.assertEqual(
            components,
            {"CVE-2026-1000": "Server: InnoDB", "CVE-2026-1001": "Server: Optimizer"},
        )
        slack = format_slack(
            ["CVE-2026-1000"],
            ["CVE-2026-1000", "CVE-2026-1001", "CVE-2026-2000"],
            components=components,
            bug_maps=[{"38888307": ["CVE-2026-1001"], "38888308": ["CVE-2026-1001"]}],
        )
        self.assertIn("+2 -0 CVEs", slack)
        self.assertIn("MySQL: +1 -0", slack)
        self.assertIn("Server: Optimizer (2)", slack)

    def test_parser_skips_modification_history(self) -> None:
        html = (
            "<h2>Risk</h2><p>CVE-2026-0001</p>"
            "<h2>Modification History</h2><p>CVE-2021-22555</p>"
            "<h2>Next</h2><p>CVE-2026-0002</p>"
        )
        self.assertEqual(parse_cves(html), ["CVE-2026-0001", "CVE-2026-0002"])

    def test_saved_removal_is_reported(self) -> None:
        previous = {
            "cpuapr2026": {
                "sha": "older",
                "cves": ["CVE-2026-2", "CVE-2021-22555"],
                "bug_cves": {"1": ["CVE-2026-2"]},
                "parser": 0,
            }
        }
        changes, rows = apply_state([_fresh("cpuapr2026", ["CVE-2026-2"])], previous)
        self.assertEqual(changes[0]["removed"], ["CVE-2021-22555"])
        self.assertNotIn("parser", rows["cpuapr2026"])

    def test_failed_csaf_keeps_the_cached_map_and_empty_map_replaces_it(self) -> None:
        previous = {
            "cpuapr2026": {
                "sha": cve_sha(["CVE-2026-1"]),
                "cves": ["CVE-2026-1"],
                "bug_cves": {"9": ["CVE-2026-1"]},
                "parser": 1,
            }
        }
        kept = _fresh("cpuapr2026", ["CVE-2026-1"])
        kept["bug_cves"] = None
        _changes, rows = apply_state([kept], previous)
        self.assertEqual(rows["cpuapr2026"]["bug_cves"], {"9": ["CVE-2026-1"]})
        replaced = _fresh("cpuapr2026", ["CVE-2026-1"], {})
        _changes, rows = apply_state([replaced], previous)
        self.assertEqual(rows["cpuapr2026"]["bug_cves"], {})

    def test_pending_message_is_replayed_until_ack(self) -> None:
        pending = [
            {
                "id": "cpuapr2026:abc-def-1",
                "slug": "cpuapr2026",
                "sha": "def",
                "slack": "added B",
            }
        ]
        kept = merge_pending(pending, [])
        items = notification_items([], "none", kept)
        self.assertEqual(items[0]["pending_id"], "cpuapr2026:abc-def-1")
        self.assertTrue(items[0]["changed"])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cpu-state.json"
            atomic_write(
                path,
                json.dumps(
                    {
                        "threads": {},
                        "pending": kept,
                        "advisories": {
                            "cpuapr2026": {
                                "sha": "def",
                                "cves": ["CVE-2026-1"],
                                "bug_cves": {"9": ["CVE-2026-1"]},
                                "parser": 1,
                            }
                        },
                    }
                )
                + "\n",
            )
            ack_state(
                path,
                "cpuapr2026:abc-def-1",
                "cpuapr2026",
                {"channelId": "C", "ts": "1.2", "threadId": "1.2"},
            )
            state, notes = load_state(path)
            self.assertEqual(notes, [])
            self.assertEqual(state["pending"], [])
            self.assertEqual(state["threads"]["cpuapr2026"]["threadId"], "1.2")
            self.assertEqual(state["advisories"]["cpuapr2026"]["bug_cves"]["9"], ["CVE-2026-1"])
            self.assertFalse(path.with_name(path.name + ".tmp").exists())

    def test_legacy_files_import_pending_and_threads(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "cpu-cves.json").write_text(
                json.dumps(
                    {
                        "parser": 0,
                        "advisories": {
                            "cpujul2026": {
                                "sha": "a",
                                "cves": ["CVE-2026-1"],
                                "bug_cves": {},
                            }
                        },
                    }
                ),
                encoding="utf-8",
            )
            (root / "cpu-slack.json").write_text(
                json.dumps(
                    {
                        "threads": {"cpujul2026": {"threadId": "9.9", "channelId": "C", "ts": "9.9"}},
                        "pending": [
                            {
                                "id": "cpujul2026:a-b-1",
                                "slug": "cpujul2026",
                                "sha": "b",
                                "slack": "text",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            state, notes = load_state(root / "cpu-state.json")
            self.assertNotIn("parser", state["advisories"]["cpujul2026"])
            self.assertEqual(state["pending"][0]["id"], "cpujul2026:a-b-1")
            self.assertEqual(state["threads"]["cpujul2026"]["threadId"], "9.9")
            self.assertEqual(notes[0]["outcome"], "imported")

    def test_corrupt_state_does_not_read_legacy_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "cpu-state.json").write_text("{", encoding="utf-8")
            (root / "cpu-cves.json").write_text(
                json.dumps({"advisories": {"cpuapr2026": {"cves": ["CVE-2026-1"], "bug_cves": {}}}}),
                encoding="utf-8",
            )
            state, notes = load_state(root / "cpu-state.json")
            self.assertEqual(state["advisories"], {})
            self.assertEqual(notes[0]["area"], "baseline")

    def test_ignore_state_keeps_cached_maps_and_reports_only_newest(self) -> None:
        previous = {
            "cspusep2026": {
                "sha": "n",
                "cves": ["CVE-2026-9"],
                "bug_cves": {"1": ["CVE-2026-9"]},
                "parser": 1,
            },
            "cpuapr2026": {
                "sha": "o",
                "cves": ["CVE-2026-1"],
                "bug_cves": {"2": ["CVE-2026-1"]},
                "parser": 1,
            },
        }
        changes, rows = apply_state(
            [
                _fresh("cspusep2026", ["CVE-2026-9", "CVE-2026-10"], {"1": ["CVE-2026-9"]}),
                _fresh("cpuapr2026", ["CVE-2026-1"], None),
            ],
            previous,
            ignore_cves=True,
        )
        self.assertEqual([change["slug"] for change in changes], ["cspusep2026"])
        self.assertEqual(rows["cpuapr2026"]["bug_cves"], {"2": ["CVE-2026-1"]})
        self.assertEqual(rows["cpuapr2026"]["cves"], ["CVE-2026-1"])

    def test_index_failure_republishes_the_cached_mapping(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            atomic_write(
                root / "cpu-state.json",
                json.dumps(
                    {
                        "threads": {"cpuapr2026": {"threadId": "1", "channelId": "C", "ts": "1"}},
                        "pending": [
                            {
                                "id": "cpuapr2026:a-b-1",
                                "slug": "cpuapr2026",
                                "sha": "b",
                                "slack": "still pending",
                            }
                        ],
                        "advisories": {
                            "cpuapr2026": {
                                "sha": "b",
                                "cves": ["CVE-2026-1"],
                                "bug_cves": {"9": ["CVE-2026-1"]},
                                "parser": 1,
                            }
                        },
                    }
                )
                + "\n",
            )

            def failed_index(count: int, notes: list | None = None) -> Collection:
                assert notes is not None
                notes.append(
                    {
                        "level": "warning",
                        "outcome": "failed",
                        "area": "index",
                        "slug": "",
                        "attempts": 3,
                        "message": "Index download failed after 3 attempts: URLError.",
                        "fallback": "",
                        "impact": "",
                        "exception": "",
                    }
                )
                return Collection([], False, notes, {"picked": 0, "refreshed": 0})

            with patch("oracle_cpu.pipeline.collect", failed_index):
                usable = poll(
                    root / "cpu-state.json",
                    root / "cpu-bug-cve.json",
                    root / "cpu-notify.json",
                    root / "cpu-run.json",
                    count=10,
                )
            self.assertTrue(usable)
            bugs = json.loads((root / "cpu-bug-cve.json").read_text(encoding="utf-8"))
            self.assertEqual(bugs["bugs"]["9"], ["CVE-2026-1"])
            state, _notes = load_state(root / "cpu-state.json")
            self.assertEqual(state["pending"][0]["slack"], "still pending")
            report, notes = load_report(root / "cpu-run.json")
            self.assertTrue(report["degraded"])
            self.assertTrue(report["usable"])
            self.assertTrue(any(note.get("area") == "index" for note in notes))

    def test_mapping_change_without_cve_diff_still_writes_bugs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            previous = {
                "cpuapr2026": {
                    "sha": cve_sha(["CVE-2026-1"]),
                    "cves": ["CVE-2026-1"],
                    "bug_cves": {},
                    "parser": 1,
                    "title": "CPU April 2026",
                    "url": "https://example.test/cpuapr2026.html",
                }
            }
            atomic_write(
                root / "cpu-state.json",
                json.dumps({"threads": {}, "pending": [], "advisories": previous}) + "\n",
            )
            fresh = [
                _fresh(
                    "cpuapr2026",
                    ["CVE-2026-1"],
                    {"42": ["CVE-2026-1"]},
                )
            ]

            def ok_collect(count: int, notes: list | None = None) -> Collection:
                return Collection(fresh, True, notes or [], {"picked": 1, "refreshed": 1})

            with patch("oracle_cpu.pipeline.collect", ok_collect):
                usable = poll(
                    root / "cpu-state.json",
                    root / "cpu-bug-cve.json",
                    root / "cpu-notify.json",
                    root / "cpu-run.json",
                    count=10,
                )
            self.assertTrue(usable)
            bugs = json.loads((root / "cpu-bug-cve.json").read_text(encoding="utf-8"))
            self.assertEqual(bugs["bugs"], {"42": ["CVE-2026-1"]})
            manifest = json.loads((root / "cpu-notify.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["items"], [])
            report, _notes = load_report(root / "cpu-run.json")
            self.assertFalse(report["degraded"])
            self.assertTrue(report["state_changed"])

    def test_unchanged_checkpoint_is_not_a_state_change(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            row = {
                "sha": cve_sha(["CVE-2026-1"]),
                "cves": ["CVE-2026-1"],
                "bug_cves": {"42": ["CVE-2026-1"]},
                "title": "cpuapr2026",
                "url": "https://www.oracle.com/security-alerts/cpuapr2026.html",
            }
            atomic_write(
                root / "cpu-state.json",
                json.dumps({"threads": {}, "pending": [], "advisories": {"cpuapr2026": row}})
                + "\n",
            )
            fresh = [_fresh("cpuapr2026", ["CVE-2026-1"], {"42": ["CVE-2026-1"]})]

            def ok_collect(count: int, notes: list | None = None) -> Collection:
                return Collection(fresh, True, notes or [], {"picked": 1, "refreshed": 1})

            with patch("oracle_cpu.pipeline.collect", ok_collect):
                poll(
                    root / "cpu-state.json",
                    root / "cpu-bug-cve.json",
                    root / "cpu-notify.json",
                    root / "cpu-run.json",
                    count=10,
                )
            report, _notes = load_report(root / "cpu-run.json")
            self.assertFalse(report["state_changed"])
            self.assertEqual(report["state_changed"], False)

    def test_corrupt_checkpoint_has_no_comparison(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "cpu-state.json").write_text("{", encoding="utf-8")
            fresh = [_fresh("cpuapr2026", ["CVE-2026-1"], {"42": ["CVE-2026-1"]})]

            def ok_collect(count: int, notes: list | None = None) -> Collection:
                return Collection(fresh, True, notes or [], {"picked": 1, "refreshed": 1})

            with patch("oracle_cpu.pipeline.collect", ok_collect):
                poll(
                    root / "cpu-state.json",
                    root / "cpu-bug-cve.json",
                    root / "cpu-notify.json",
                    root / "cpu-run.json",
                    count=10,
                )
            report, _notes = load_report(root / "cpu-run.json")
            self.assertIsNone(report["state_changed"])

    def test_pending_removal_changes_the_checkpoint(self) -> None:
        before = {"threads": {}, "pending": [{"id": "a", "slug": "s", "sha": "1", "slack": "t"}], "advisories": {}}
        after = {"threads": {}, "pending": [], "advisories": {}}
        self.assertNotEqual(persistent_signature(before), persistent_signature(after))

    def test_csaf_404_names_the_redirect_target(self) -> None:
        published = "https://www.oracle.com/docs/tech/security-alerts/cspumay2026csaf.json"
        direct = "https://www.oracle.com/a/tech/docs/security-alerts/cspumay2026csaf.json"

        def fake_get(url: str) -> tuple[int, str, str]:
            if url == published:
                return 301, "", direct
            return 404, "", ""

        with patch("oracle_cpu.core._http_get", fake_get), patch("oracle_cpu.core.time.sleep"):
            failed: list[str] = []
            with self.assertRaises(urllib.error.URLError):
                _fetch(published, failed)
        self.assertIn(published, failed[0])
        self.assertIn(direct, failed[0])
        self.assertIn("404", failed[0])

    def test_bug_map_is_written_when_checkpoint_save_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fresh = [_fresh("cpuapr2026", ["CVE-2026-1"], {"42": ["CVE-2026-1"]})]

            def ok_collect(count: int, notes: list | None = None) -> Collection:
                return Collection(fresh, True, notes or [], {"picked": 1, "refreshed": 1})

            with patch("oracle_cpu.pipeline.collect", ok_collect), patch(
                "oracle_cpu.pipeline.save_state", side_effect=PermissionError("denied")
            ):
                usable = poll(
                    root / "cpu-state.json",
                    root / "cpu-bug-cve.json",
                    root / "cpu-notify.json",
                    root / "cpu-run.json",
                    count=10,
                )
            self.assertTrue(usable)
            bugs = json.loads((root / "cpu-bug-cve.json").read_text(encoding="utf-8"))
            self.assertEqual(bugs["bugs"], {"42": ["CVE-2026-1"]})
            # No previous checkpoint was copied, and the replace failed, so
            # the mapping file is the only artifact Jenkins can publish.
            self.assertFalse((root / "cpu-state.json").exists())
            self.assertFalse((root / "cpu-notify.json").exists())
            report, notes = load_report(root / "cpu-run.json")
            self.assertFalse(report["checkpoint_saved"])
            self.assertTrue(report["usable"])
            self.assertTrue(any(note.get("area") == "baseline" for note in notes))

    def test_malformed_report_still_renders_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "cpu-run.json").write_text("{", encoding="utf-8")
            (root / "cpu-state.json").write_text("{", encoding="utf-8")
            write_status(
                root / "cpu-run.json",
                root / "cpu-state.json",
                root / "cpu-status.txt",
                root / "cpu-description.txt",
                "SUCCESS",
            )
            text = (root / "cpu-status.txt").read_text(encoding="utf-8")
            self.assertIn("not JSON", text)
            self.assertIn("Status: SUCCESS", text)


if __name__ == "__main__":
    unittest.main()
