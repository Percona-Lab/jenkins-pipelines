#!/usr/bin/env python3

"""Fix Go vulnerabilities reported by Trivy and commit each dependency update."""

import argparse
import json
import os
import re
import subprocess
import urllib.request
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple


Version = Tuple[int, int, int]
VERSION_PATTERN = re.compile(r"^(?:go|v)?(\d+)\.(\d+)\.(\d+)(?:\+incompatible)?$")
DOCKER_GO_PATTERN = re.compile(r"golang:(\d+)\.(\d+)(?:\.(\d+))?")


def run(
    *command: str,
    check: bool = True,
    cwd: Optional[Path] = None,
    capture_output: bool = False,
) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        check=check,
        cwd=cwd,
        text=True,
        capture_output=capture_output,
    )


def output(*command: str, cwd: Optional[Path] = None) -> str:
    result = run(*command, cwd=cwd, capture_output=True)
    return result.stdout.strip()


def parse_version(version: str) -> Version:
    match = VERSION_PATTERN.fullmatch(version.strip())
    if not match:
        raise ValueError(f"Unsupported stable version: {version}")
    return tuple(map(int, match.groups()))


def format_version(version: Version, prefix: str = "") -> str:
    return prefix + ".".join(map(str, version))


def stable_versions(versions: Iterable[str]) -> List[Version]:
    return sorted(
        {
            parse_version(version)
            for version in versions
            if VERSION_PATTERN.fullmatch(version.strip())
        }
    )


def select_fixed_line(installed: str, fixed_versions: Iterable[str]) -> Version:
    fixes = stable_versions(fixed_versions)
    if not fixes:
        raise RuntimeError("Trivy did not provide a stable fixed version")

    installed_version = parse_version(installed) if installed else None
    same_line = (
        [version for version in fixes if version[:2] == installed_version[:2]]
        if installed_version
        else []
    )
    return max(same_line) if same_line else min(fixes)


def latest_go_patch(version: Version) -> Version:
    candidates = [version]
    local_version = run(
        "go", "env", "GOVERSION", check=False, capture_output=True
    ).stdout.strip()
    if VERSION_PATTERN.fullmatch(local_version):
        parsed = parse_version(local_version)
        if parsed[:2] == version[:2]:
            candidates.append(parsed)

    try:
        with urllib.request.urlopen(
            "https://go.dev/dl/?mode=json&include=all", timeout=30
        ) as response:
            releases = json.load(response)
    except Exception as error:
        selected = max(candidates)
        print(f"Unable to query Go releases; using {format_version(selected)}: {error}")
        return selected

    releases = stable_versions(
        release.get("version", "") for release in releases if release.get("stable")
    )
    candidates.extend(release for release in releases if release[:2] == version[:2])
    return max(candidates)


def latest_module_version(
    module: str,
    installed: str,
    fixed_versions: Iterable[str],
    go_mod_directory: Path,
) -> Version:
    available = output(
        "go", "list", "-m", "-versions", module, cwd=go_mod_directory
    ).split()[1:]
    return select_latest_module_version(installed, fixed_versions, available)


def select_latest_module_version(
    installed: str,
    fixed_versions: Iterable[str],
    available_versions: Iterable[str],
) -> Version:
    fixes = stable_versions(fixed_versions)
    if not fixes:
        raise RuntimeError("Trivy did not provide a stable fixed module version")

    installed_version = parse_version(installed) if installed else None
    fixed_major = (
        installed_version[0]
        if installed_version
        and any(version[0] == installed_version[0] for version in fixes)
        else min(fixes)[0]
    )
    candidates = stable_versions(
        [*available_versions, *fixed_versions, *([installed] if installed else [])]
    )
    same_major = [version for version in candidates if version[0] == fixed_major]
    if not same_major:
        raise RuntimeError(f"No stable version found for major v{fixed_major}")

    return max(same_major)


def find_security_fixes(report: Path) -> Dict[str, Tuple[Set[str], Set[str]]]:
    data = json.loads(report.read_text(encoding="utf-8"))
    fixes: Dict[str, Tuple[Set[str], Set[str]]] = {}

    for result in data.get("Results") or []:
        if result.get("Type") not in {"gomod", "gobinary"}:
            continue

        for vulnerability in result.get("Vulnerabilities") or []:
            fixed = vulnerability.get("FixedVersion") or ""
            if vulnerability.get("Severity") not in {"HIGH", "CRITICAL"} or not fixed:
                continue

            package = vulnerability["PkgName"]
            versions, vulnerabilities = fixes.setdefault(package, (set(), set()))
            versions.update(version.strip() for version in fixed.split(","))
            vulnerabilities.add(
                f"{vulnerability['VulnerabilityID']} ({vulnerability['Severity']})"
            )

    return fixes


def go_mod_version(go_mod: Path) -> str:
    for line in go_mod.read_text(encoding="utf-8").splitlines():
        if line.startswith("go "):
            return line.split()[1]
    raise RuntimeError(f"Go version was not found in {go_mod}")


def dockerfile_go_version(dockerfile: Path) -> Version:
    match = DOCKER_GO_PATTERN.search(dockerfile.read_text(encoding="utf-8"))
    if not match:
        raise RuntimeError(f"No golang image was found in {dockerfile}")
    major, minor, patch = match.groups()
    return int(major), int(minor), int(patch or 0)


def current_go_version(go_mod: Path, dockerfile: Path) -> Version:
    return max(parse_version(go_mod_version(go_mod)), dockerfile_go_version(dockerfile))


def module_version(module: str, go_mod_directory: Path) -> str:
    result = run(
        "go",
        "list",
        "-m",
        "-f",
        "{{.Version}}",
        module,
        cwd=go_mod_directory,
        check=False,
        capture_output=True,
    )
    return result.stdout.strip() if result.returncode == 0 else ""


def update_dockerfile(dockerfile: Path, version: str) -> None:
    contents = dockerfile.read_text(encoding="utf-8")
    updated, replacements = re.subn(
        r"(golang:)\d+(?:\.\d+){1,2}", rf"\g<1>{version}", contents
    )
    if not replacements:
        raise RuntimeError(f"No golang image was found in {dockerfile}")
    dockerfile.write_text(updated, encoding="utf-8")


def stage_go_files(go_mod: Path, dockerfile: Path) -> None:
    files = [str(go_mod), str(dockerfile)]
    go_sum = go_mod.with_name("go.sum")
    if go_sum.exists():
        files.append(str(go_sum))
    run("git", "add", *files)


def commit_update(
    package: str,
    old_version: str,
    new_version: str,
    vulnerabilities: Iterable[str],
    tag: str,
) -> None:
    if run("git", "diff", "--cached", "--quiet", check=False).returncode == 0:
        print(f"No additional change required for {package}")
        return

    run(
        "git",
        "commit",
        "-m",
        f"Security build {tag}: {package} {old_version or 'not-present'} to {new_version}",
        "-m",
        f"Vulnerabilities: {', '.join(sorted(vulnerabilities))}",
    )


def fix_stdlib(
    fixed_versions: Iterable[str],
    vulnerabilities: Iterable[str],
    go_mod: Path,
    dockerfile: Path,
    tag: str,
) -> None:
    old_version = format_version(current_go_version(go_mod, dockerfile))
    new_version = latest_go_patch(select_fixed_line(old_version, fixed_versions))
    formatted_version = format_version(new_version)
    print(f"Updating stdlib: {old_version} -> {formatted_version}")

    run("go", "mod", "edit", f"-go={formatted_version}", cwd=go_mod.parent)
    update_dockerfile(dockerfile, formatted_version)
    run("go", "mod", "tidy", cwd=go_mod.parent)
    stage_go_files(go_mod, dockerfile)
    commit_update("stdlib", old_version, formatted_version, vulnerabilities, tag)


def fix_module(
    module: str,
    fixed_versions: Iterable[str],
    vulnerabilities: Iterable[str],
    go_mod: Path,
    dockerfile: Path,
    tag: str,
) -> None:
    old_version = module_version(module, go_mod.parent)
    selected = latest_module_version(module, old_version, fixed_versions, go_mod.parent)
    target_version = format_version(selected, "v")
    print(f"Updating {module}: {old_version or 'not-present'} -> {target_version}")

    run("go", "get", f"{module}@{target_version}", cwd=go_mod.parent)
    run("go", "mod", "tidy", cwd=go_mod.parent)
    new_version = module_version(module, go_mod.parent)
    stage_go_files(go_mod, dockerfile)
    commit_update(module, old_version, new_version, vulnerabilities, tag)


def configure_github_credentials() -> None:
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("GITHUB_TOKEN is required")
    run(
        "git",
        "config",
        "--global",
        f"url.https://x-access-token:{token}@github.com/.insteadOf",
        "https://github.com/",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trivy-report", required=True, type=Path)
    parser.add_argument("--go-mod", required=True, type=Path)
    parser.add_argument("--dockerfile", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()

    configure_github_credentials()
    fixes = find_security_fixes(args.trivy_report)

    stdlib = fixes.pop("stdlib", None)
    for module, (versions, vulnerabilities) in sorted(fixes.items()):
        fix_module(
            module,
            versions,
            vulnerabilities,
            args.go_mod,
            args.dockerfile,
            args.tag,
        )

    if not stdlib:
        current_version = format_version(
            current_go_version(args.go_mod, args.dockerfile)
        )
        stdlib = ({current_version}, {"Preventive latest stable Go stdlib update"})

    fix_stdlib(*stdlib, args.go_mod, args.dockerfile, args.tag)

    print("Testing the complete dependency update")
    run("make", "test")


if __name__ == "__main__":
    main()
