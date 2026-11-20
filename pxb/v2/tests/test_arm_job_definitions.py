"""Check ARM job definitions using their actual Jenkins Job Builder output."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
JOBS = ROOT / "pxb/v2/jenkins"


def render(source):
    with tempfile.TemporaryDirectory(prefix="pxb-arm-jjb-") as output:
        result = subprocess.run(
            ["jenkins-jobs", "test", str(source), "--config-xml", "-o", output],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=45, check=False,
        )
        if result.returncode:
            raise AssertionError(f"JJB failed for {source.name}:\n{result.stdout}")
        files = sorted(Path(output).rglob("config.xml"))
        if not files:
            raise AssertionError(f"JJB rendered no jobs for {source.name}")
        return [ET.parse(path).getroot() for path in files]


class ArmJobDefinitions(unittest.TestCase):
    def test_legacy_jobs_render(self):
        for stage in ("compile-param", "test-param", "trunk"):
            with self.subTest(stage=stage):
                render(JOBS / f"percona-xtrabackup-2.4-{stage}.yml")

    def test_legacy_arch_is_x86_only_and_expands_at_runtime(self):
        for stage in ("compile", "test"):
            with self.subTest(stage=stage):
                job, = render(JOBS / f"percona-xtrabackup-2.4-{stage}-param.yml")
                arch = next(axis for axis in job.find("axes")
                            if axis.findtext("name") == "ARCH")
                self.assertEqual([v.text for v in arch.findall("values/string")], ["x86_64"])
                forwarded = job.findtext(
                    ".//hudson.plugins.parameterizedtrigger.PredefinedBuildParameters/properties"
                )
                self.assertIn("ARCH=${ARCH}", forwarded)
                self.assertNotIn("${{", forwarded)

    def test_single_platform_script_exists(self):
        job, = render(JOBS / "percona-xtrabackup-9.x-single-platform.yml")
        path = job.findtext("definition/scriptPath")
        self.assertTrue(path)
        self.assertTrue((ROOT / path).is_file(), path)

    def test_all_family_jobs_render_with_existing_scripts(self):
        jobs = render(JOBS)
        self.assertEqual(len(jobs), 27)
        for job in jobs:
            path = job.findtext("definition/scriptPath")
            if path:
                self.assertTrue((ROOT / path).is_file(), path)

    def test_generated_pipeline_jobs_have_arch_before_first_run(self):
        sources = sorted(JOBS.glob("*-pipeline.yml"))
        sources += [JOBS / "percona-xtrabackup-9.x-single-platform.yml",
                    ROOT / "pxc/jenkins/prepare-pxc-build-docker.yml"]
        self.assertEqual(len(sources), 12)
        for source in sources:
            with self.subTest(source=source.name):
                job, = render(source)
                definitions = job.findall(".//parameterDefinitions/*")
                arch = next((p for p in definitions if p.findtext("name") == "ARCH"), None)
                self.assertIsNotNone(arch, "ARCH must be seeded before pre-pipeline validation")
                choices = [value.text for value in arch.findall(".//string")]
                self.assertEqual(choices, ["x86_64"] if "-2.4-" in source.name
                                 else ["x86_64", "aarch64"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
