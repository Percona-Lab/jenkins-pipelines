#!/usr/bin/env python3
"""Test plan generator for Kubernetes Operators."""

import json
import re
import argparse
import hashlib
import subprocess
from datetime import datetime, timezone
import requests
from pathlib import Path


GENERATOR_VERSION = "1"

DB_TYPE_TO_OPERATOR = {
    "PXC": "pxc",
    "MYSQL": "ps",
    "MONGOD": "psmdb",
    "POSTGRESQL": "pg",
}

OPERATOR_PLATFORMS = {
    "ps": ["DOKS", "EKS", "GKE", "MINIKUBE", "OPENSHIFT", "RANCHER"],
    "psmdb": ["AKS", "EKS", "GKE", "MINIKUBE", "OPENSHIFT", "RANCHER"],
    "pxc": ["AKS", "DOKS", "EKS", "GKE", "MINIKUBE", "OPENSHIFT"],
    "pg": ["AKS", "DOKS", "EKS", "GKE", "MINIKUBE", "OPENSHIFT", "RANCHER"],
}


def parse_versions_file(filepath: str) -> dict[str, str]:
    versions = {}
    with open(filepath) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, value = line.split("=", 1)
                versions[key.strip()] = value.strip()
    return versions


def detect_db_type(versions: dict[str, str]) -> str | None:
    patterns = ["PXC", "MYSQL", "MONGOD", "POSTGRESQL"]
    for pattern in patterns:
        if any(key.startswith(f"IMAGE_{pattern}") for key in versions):
            return pattern
    return None


def extract_db_versions(versions: dict[str, str], db_type: str) -> list[str]:
    pattern = re.compile(rf"^IMAGE_{db_type}(\d+)$")
    found = set()
    for key in versions:
        match = pattern.match(key)
        if match:
            found.add(match.group(1))
    return sorted(found, key=int, reverse=True)


def extract_community_versions(versions: dict[str, str]) -> list[tuple[str, str]]:
    pattern = re.compile(r"^IMAGE_POSTGRESQL(\d+)_(UBI\d+)_COMMUNITY$")
    found = {
        (match.group(1), match.group(2))
        for key in versions
        if (match := pattern.match(key))
        and versions[key]
        and match.group(2) != "UBI10"
    }
    return sorted(found, key=lambda item: (int(item[0]), item[1]), reverse=True)


def extract_postgis_versions(versions: dict[str, str]) -> list[str]:
    pattern = re.compile(r"^IMAGE_POSTGIS(\d+)$")
    found = {
        match.group(1)
        for key in versions
        if (match := pattern.match(key)) and versions[key]
    }
    return sorted(found, key=int, reverse=True)


def get_minikube():
    resp = requests.get(
        "https://api.github.com/repos/kubernetes/minikube/releases/latest", timeout=10
    )
    resp.raise_for_status()
    return resp.json().get("tag_name", "").lstrip("v")


def extract_k8s_platforms(
    versions: dict[str, str], operator: str
) -> dict[str, dict[str, str]]:
    platforms = {}
    for platform in OPERATOR_PLATFORMS[operator]:
        info = {}
        key_platform = "RKE2" if platform == "RANCHER" else platform
        for suffix in ["MIN", "MAX", "REL"]:
            key = f"{key_platform}_{suffix}"
            if key in versions:
                label = "version" if suffix == "REL" else suffix.lower()
                info[label] = versions[key]
        platforms[platform] = info or {"latest": "latest"}
    return platforms


def derive_git_branch(versions: dict[str, str], override: str | None = None) -> str:
    if override:
        return override

    operator_image = versions.get("IMAGE_OPERATOR", "")
    _, separator, tag = operator_image.rpartition(":")
    if not separator or not tag:
        raise ValueError(
            "IMAGE_OPERATOR must contain a tag, or pass --branch explicitly"
        )
    return f"release-{tag}"


def repository_revision() -> str | None:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return result.stdout.strip() or None


def generate_version_info(versions: dict[str, str], db_type: str) -> str:
    """Generate informational message about tested versions.

    Does not include cert-manager (taken separately).
    """
    operator_names = {
        "PXC": "PXC",
        "MYSQL": "PS",
        "MONGOD": "PSMDB",
        "POSTGRESQL": "PG",
    }
    op_name = operator_names.get(db_type, db_type)

    release_version = ""
    if "IMAGE_OPERATOR" in versions:
        release_version = versions["IMAGE_OPERATOR"].split(":")[-1]

    lines = [
        f"INFO: For {op_name} Operator {release_version} release we will proceed with:"
    ]
    lines.append("")

    lines.append("Kubernetes Platforms:")
    operator = DB_TYPE_TO_OPERATOR[db_type]
    k8s_platforms = extract_k8s_platforms(versions, operator)

    platform_display = {
        "GKE": "GKE",
        "EKS": "EKS",
        "AKS": "AKS",
        "OPENSHIFT": "OpenShift",
        "MINIKUBE": "MiniKube",
        "DOKS": "DOKS",
        "RANCHER": "Rancher/RKE2",
    }

    for platform in OPERATOR_PLATFORMS[operator]:
        info = k8s_platforms[platform]
        name = platform_display[platform]

        if platform == "MINIKUBE":
            k8s_ver = info.get("max") or info.get("version") or info.get("latest")
            if k8s_ver == "latest":
                lines.append(f"{name} latest")
            elif k8s_ver:
                lines.append(f"{name} {get_minikube()} with Kubernetes v{k8s_ver}")
        else:
            min_v = info.get("min", "")
            max_v = info.get("max", "")
            if min_v and max_v:
                lines.append(f"{name} {min_v} - {max_v}")
            elif max_v:
                lines.append(f"{name} {max_v}")
            else:
                lines.append(f"{name} latest")

    lines.append("")
    lines.append("Software supported:")
    lines.extend(_get_software_versions(versions, db_type))

    return "\n".join(lines)


def _get_software_versions(versions: dict[str, str], db_type: str) -> list[str]:
    """Extract software versions based on database type."""
    lines = []

    if db_type == "MONGOD":
        for key, val in sorted(versions.items(), reverse=True):
            if key.startswith("IMAGE_MONGOD") and val:
                ver = key.replace("IMAGE_MONGOD", "")
                lines.append(
                    f"MongoDB {ver[0]}.{ver[1:]}: {val.split(':')[-1] if ':' in val else val}"
                )
        _add_if_exists(lines, versions, "IMAGE_BACKUP", "PBM")
        _add_if_exists(lines, versions, "IMAGE_PMM2_CLIENT", "PMM Client")
        _add_if_exists(lines, versions, "IMAGE_PMM3_CLIENT", "PMM3 Client")
        _add_if_exists(lines, versions, "IMAGE_LOGCOLLECTOR", "LogCollector")
        _add_cert_manager(lines, versions)

    elif db_type == "PXC":
        for key, val in sorted(versions.items(), reverse=True):
            if key.startswith("IMAGE_PXC") and val:
                ver = key.replace("IMAGE_PXC", "")
                major = f"{ver[0]}.{ver[1:]}" if len(ver) == 2 else ver
                lines.append(
                    f"PXC {major}: {val.split(':')[-1] if ':' in val else val}"
                )
        for key in ["IMAGE_BACKUP84", "IMAGE_BACKUP80", "IMAGE_BACKUP57"]:
            if key in versions and versions[key]:
                ver = key.replace("IMAGE_BACKUP", "")
                major = f"{ver[0]}.{ver[1:]}" if len(ver) == 2 else ver
                lines.append(f"XtraBackup-{major}: {versions[key].split(':')[-1]}")
        _add_if_exists(lines, versions, "IMAGE_HAPROXY", "HAProxy")
        _add_if_exists(lines, versions, "IMAGE_PROXY", "ProxySQL")
        _add_if_exists(lines, versions, "IMAGE_PROXYSQL", "ProxySQL")
        _add_if_exists(
            lines, versions, "IMAGE_LOGCOLLECTOR", "LogCollector (fluent-bit)"
        )
        _add_if_exists(lines, versions, "IMAGE_PMM2_CLIENT", "PMM-Client2")
        _add_if_exists(lines, versions, "IMAGE_PMM3_CLIENT", "PMM-Client3")
        _add_cert_manager(lines, versions)

    elif db_type == "MYSQL":
        for key, val in sorted(versions.items(), reverse=True):
            if key.startswith("IMAGE_MYSQL") and val:
                ver = key.replace("IMAGE_MYSQL", "")
                major = f"{ver[0]}.{ver[1:]}" if len(ver) == 2 else ver
                lines.append(
                    f"Percona Server {major}: {val.split(':')[-1] if ':' in val else val}"
                )
        for key, val in sorted(versions.items(), reverse=True):
            if key.startswith("IMAGE_BACKUP") and val:
                ver = key.replace("IMAGE_BACKUP", "")
                if ver:
                    lines.append(
                        f"XtraBackup {ver[0]}.{ver[1:] if len(ver) > 1 else '0'}: {val.split(':')[-1] if ':' in val else val}"
                    )
        for key, val in sorted(versions.items(), reverse=True):
            if key.startswith("IMAGE_ROUTER") and val:
                ver = key.replace("IMAGE_ROUTER", "")
                if ver:
                    lines.append(
                        f"MySQL Router {ver[0]}.{ver[1:] if len(ver) > 1 else '0'}: {val.split(':')[-1] if ':' in val else val}"
                    )
        _add_if_exists(lines, versions, "IMAGE_HAPROXY", "HAProxy")
        _add_if_exists(lines, versions, "IMAGE_ORCHESTRATOR", "Orchestrator")
        _add_if_exists(lines, versions, "IMAGE_TOOLKIT", "Percona Toolkit")
        _add_if_exists(lines, versions, "IMAGE_PMM_CLIENT", "PMM Client")
        _add_cert_manager(lines, versions)

    elif db_type == "POSTGRESQL":
        pg_versions = []
        for key, val in sorted(versions.items()):
            if re.fullmatch(r"IMAGE_POSTGRESQL\d+", key) and val:
                ver_str = val.split(":")[-1] if ":" in val else val
                pg_versions.append(ver_str)
        if pg_versions:
            lines.append(f"Postgres: {', '.join(pg_versions)}")
        community_versions = [
            f"{major} {ubi}" for major, ubi in extract_community_versions(versions)
        ]
        if community_versions:
            lines.append(f"Community Postgres: {', '.join(community_versions)}")
        _add_unique_versions(lines, versions, "IMAGE_BACKREST", "PGBackRest")
        _add_unique_versions(lines, versions, "IMAGE_PGBOUNCER", "PGBouncer")
        lines.append("patroni: <OVERRIDE>")
        _add_postgis_versions(lines, versions)
        _add_if_exists(lines, versions, "IMAGE_PMM_CLIENT", "PMM")
        _add_cert_manager(lines, versions)

    return lines


def _add_if_exists(lines: list[str], versions: dict[str, str], key: str, label: str):
    """Helper to add version line if key exists."""
    if key in versions and versions[key]:
        val = versions[key]
        ver = val.split(":")[-1] if ":" in val else val
        lines.append(f"{label}: {ver}")


def _add_cert_manager(lines: list[str], versions: dict[str, str]):
    lines.append(f"cert-manager: {versions.get('CERT_MANAGER') or '<OVERRIDE>'}")


def _add_unique_versions(
    lines: list[str], versions: dict[str, str], prefix: str, label: str
):
    """Extract unique versions from keys matching prefix (e.g., IMAGE_PGBOUNCER18)."""
    seen = set()
    for key, val in versions.items():
        if key.startswith(prefix) and val:
            ver = val.split(":")[-1] if ":" in val else val
            seen.add(ver)
    if seen:
        lines.append(f"{label}: {', '.join(sorted(seen))}")


def _add_postgis_versions(lines: list[str], versions: dict[str, str]):
    """Extract PostGis versions as 'pg_version: gis_version' pairs."""
    values = []
    for key, val in sorted(versions.items()):
        if key.startswith("IMAGE_POSTGIS") and val:
            tag = val.split(":")[-1] if ":" in val else val
            match = re.search(r"ppg([\d.]+)-postgres-gis([\d.]+)", tag)
            if match:
                values.append(f"{match.group(1)}: {match.group(2)}")
            else:
                values.append(tag)
    if values:
        lines.append(f"PostGis: {', '.join(values)}")


def _k8s_labels(platform: str, info: dict[str, str], is_latest: bool) -> list[str]:
    if platform == "MINIKUBE":
        if "max" in info:
            return ["max"]
        if "version" in info:
            return ["version"]
        return ["latest"]

    if platform == "DOKS" or not is_latest:
        if "max" in info:
            return ["max"]
        return ["latest"]

    labels = [label for label in ("min", "max") if label in info]
    return labels or ["latest"]


def generate_test_plan(versions_file: str, primary_platform: str = "GKE") -> list[dict]:
    versions = parse_versions_file(versions_file)
    db_type = detect_db_type(versions)

    if not db_type:
        print("Error: Could not detect database type")
        return []

    operator = DB_TYPE_TO_OPERATOR[db_type]
    db_versions = extract_db_versions(versions, db_type)
    if not db_versions:
        print("Error: Could not detect database versions")
        return []

    k8s_platforms = extract_k8s_platforms(versions, operator)

    print(f"Database: {db_type}")
    print(f"Versions: {', '.join(db_versions)}")
    # PostgreSQL 19 is experimental; keep 18 as primary until 19 is stable.
    latest = (
        "18" if db_type == "POSTGRESQL" and "18" in db_versions else db_versions[0]
    )
    print(f"Primary version: {latest}")
    print(f"Primary platform: {primary_platform}\n")

    test_plan = []

    for platform, k8s_info in k8s_platforms.items():
        for db_ver in db_versions:
            is_latest = db_ver == latest

            if not is_latest and platform != primary_platform:
                continue

            cw_modes = ["YES", "NO"] if is_latest else ["YES"]

            for k8s_label in _k8s_labels(platform, k8s_info, is_latest):
                k8s_actual = k8s_info[k8s_label]
                for cw in cw_modes:
                    test_plan.append(
                        {
                            "platform": platform,
                            "k8s_version": k8s_label,
                            "k8s_version_actual": k8s_actual,
                            "pillar_version": db_ver,
                            "cluster_wide": cw,
                        }
                    )

    if db_type == "POSTGRESQL":
        gke_info = k8s_platforms["GKE"]
        for db_ver in extract_postgis_versions(versions):
            k8s_label = "max" if "max" in gke_info else "latest"
            test_plan.append(
                {
                    "platform": "GKE",
                    "k8s_version": k8s_label,
                    "k8s_version_actual": gke_info[k8s_label],
                    "pillar_version": f"{db_ver}-postgis",
                    "cluster_wide": "YES",
                }
            )

        for db_ver, ubi_version in extract_community_versions(versions):
            k8s_label = "max" if "max" in gke_info else "latest"
            test_plan.append(
                {
                    "platform": "GKE",
                    "k8s_version": k8s_label,
                    "k8s_version_actual": gke_info[k8s_label],
                    "pillar_version": f"{db_ver}-community",
                    "ubi_version": ubi_version,
                    "cluster_wide": "YES",
                }
            )

    return test_plan


def generate_markdown_table(test_plan: list[dict]) -> str:
    lines = [
        "| Platform | K8s Version | Pillar Version | UBI | CW | Failed Tests | Done |",
        "|----------|-------------|----------------|-----|----|--------------|----|",
    ]

    sorted_plan = sorted(
        test_plan,
        key=lambda x: (
            x["platform"],
            x["k8s_version_actual"],
            x["pillar_version"],
            x.get("ubi_version", ""),
        ),
    )

    for t in sorted_plan:
        cw = "Yes" if t["cluster_wide"] == "YES" else "No"
        ubi = t.get("ubi_version", "")
        lines.append(
            f"| {t['platform']} | {t['k8s_version_actual']} | {t['pillar_version']} | {ubi} | {cw} |  |  |"
        )

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description="Generate test plan from versions file"
    )
    parser.add_argument("versions_file", help="Path to versions file")
    parser.add_argument(
        "--branch",
        help="Operator branch to test (default: release-{IMAGE_OPERATOR tag})",
    )
    args = parser.parse_args()

    if not Path(args.versions_file).exists():
        print(f"Error: File '{args.versions_file}' not found")
        return 1

    versions = parse_versions_file(args.versions_file)
    db_type = detect_db_type(versions)

    if not db_type:
        print("Error: Could not detect database type")
        return 1

    operator = DB_TYPE_TO_OPERATOR[db_type]
    try:
        git_branch = derive_git_branch(versions, args.branch)
    except ValueError as exc:
        print(f"Error: {exc}")
        return 1

    test_plan = generate_test_plan(args.versions_file)
    if not test_plan:
        return 1

    with open("test_plan.json", "w") as f:
        json.dump(
            {
                "schema_version": 1,
                "operator": operator,
                "git_branch": git_branch,
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "generator": {
                    "version": GENERATOR_VERSION,
                    "repository_revision": repository_revision(),
                    "versions_sha256": hashlib.sha256(
                        Path(args.versions_file).read_bytes()
                    ).hexdigest(),
                },
                "cells": test_plan,
            },
            f,
            indent=2,
        )
        f.write("\n")
    print("Saved: test_plan.json")

    md_content = [
        generate_version_info(versions, db_type),
        "",
        "---",
        "",
        generate_markdown_table(test_plan),
    ]

    full_md = "\n".join(md_content)
    with open("test_plan.md", "w") as f:
        f.write(full_md)
    print("Saved: test_plan.md")

    print("\n" + "=" * 80)
    print(full_md)

    return 0


if __name__ == "__main__":
    exit(main())
