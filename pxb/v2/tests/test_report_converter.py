"""Exercise the real runner's converter selection without running a database."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
RUNNER = ROOT / "pxb/v2/local/test-binary"
START = '\nSUBUNIT2JUNITXML_CMD="PYTHONPATH='
END = 'mkdir -p server-tarball/'

FACTS = r"""
function [ {
    if [[ "$1" == '-f' && "${2:-}" == '/usr/bin/yum' ]]; then
        [[ "$HAS_YUM" == 1 ]]
    else
        builtin [ "$@"
    fi
}
rpm() {
    case "$2" in
        %rhel) printf '%s\n' "$RPM_RHEL" ;;
        %dist) printf '%s\n' "$RPM_DIST" ;;
        *) return 97 ;;
    esac
}
lsb_release() { printf '%s\n' "$CODENAME_FIXTURE"; }
sudo() {
    printf '%s\n' "$*" >> "$INSTALL_LOG"
    return "$INSTALL_STATUS"
}
errmsg() { exit 91; }
"""


class ReportConverter(unittest.TestCase):
    def exercise(self, *, rhel="%rhel", dist=".amzn2023", codename="", install_status=0):
        source = RUNNER.read_text()
        self.assertEqual(source.count(START), 1)
        self.assertEqual(source.count(END), 1)
        block = source[source.index(START):source.index(END)]
        with tempfile.TemporaryDirectory(prefix="pxb-report-converter-") as folder:
            work = Path(folder)
            for script, label in (("subunit2junitxml", "legacy"),
                                  ("subunit2junitxml_python3", "python3")):
                path = work / script
                path.write_text(f"#!/bin/sh\nprintf '{label}:%s\\n' \"$PYTHONPATH\"\n")
                path.chmod(0o755)
            log = work / "install.log"
            env = dict(os.environ, HAS_YUM="0" if codename else "1", RPM_RHEL=rhel,
                       RPM_DIST=dist, CODENAME_FIXTURE=codename, XTRABACKUP_TARGET="innodb80",
                       PYTHONPATH="", INSTALL_LOG=str(log), INSTALL_STATUS=str(install_status))
            result = subprocess.run(
                ["bash", "-ec", FACTS + "\n" + block + '\neval "$SUBUNIT2JUNITXML_CMD"'],
                cwd=work, env=env, text=True, capture_output=True, timeout=10, check=False,
            )
            return result, log.read_text().splitlines() if log.exists() else []

    def test_al2023_uses_python3_with_product_bundled_libraries(self):
        result, installs = self.exercise()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "python3:./python:/usr/local/lib/python:")
        self.assertEqual(installs, [], "AL2023 does not package the RHEL reporting RPMs")

    def test_rhel9_keeps_system_python3_packages(self):
        result, installs = self.exercise(rhel="9", dist=".el9")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "python3:")
        self.assertEqual(installs, ["yum install -y python3-junitxml python3-subunit"])

    def test_legacy_rhel_selection_is_unchanged(self):
        for rhel in ("7", "8"):
            with self.subTest(rhel=rhel):
                result, installs = self.exercise(rhel=rhel, dist=f".el{rhel}")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue(result.stdout.startswith("legacy:./python:"))
                self.assertEqual(installs, [])

    def test_apt_selection_is_unchanged(self):
        for codename in ("focal", "buster", "noble", "bookworm", "trixie", "xenial", "bionic"):
            with self.subTest(codename=codename):
                result, installs = self.exercise(codename=codename)
                self.assertEqual(result.returncode, 0, result.stderr)
                if codename in ("noble", "bookworm", "trixie"):
                    self.assertEqual(result.stdout.strip(), "python3:")
                    self.assertEqual(installs, ["apt update", "DEBIAN_FRONTEND=noninteractive apt install -y python3-subunit python3-junitxml"])
                else:
                    self.assertTrue(result.stdout.startswith("legacy:./python:"))
                    self.assertEqual(installs, ["apt update", "DEBIAN_FRONTEND=noninteractive apt install -y libtirpc3"]
                                     if codename in ("focal", "buster") else [])

    def test_package_install_failure_still_stops_the_runner(self):
        result, installs = self.exercise(rhel="9", dist=".el9", install_status=42)
        self.assertEqual(result.returncode, 42)
        self.assertEqual(result.stdout, "")
        self.assertEqual(len(installs), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
