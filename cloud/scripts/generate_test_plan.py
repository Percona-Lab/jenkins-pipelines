#!/usr/bin/env python3
"""Test Plan Generator for Percona Kubernetes Operators"""

import json
import re
import argparse
from pathlib import Path


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


def extract_k8s_platforms(versions: dict[str, str]) -> dict[str, dict[str, str]]:
    platforms = {}
    for platform in ["GKE", "EKS", "AKS", "OPENSHIFT", "MINIKUBE"]:
        info = {}
        for suffix in ["MIN", "MAX", "REL"]:
            key = f"{platform}_{suffix}"
            if key in versions:
                label = "version" if suffix == "REL" else suffix.lower()
                info[label] = versions[key]
        if info:
            platforms[platform] = info
    return platforms


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
    k8s_platforms = extract_k8s_platforms(versions)

    platform_display = {
        "GKE": "GKE",
        "EKS": "EKS",
        "AKS": "AKS",
        "OPENSHIFT": "OpenShift",
        "MINIKUBE": "MiniKube",
    }

    for platform in ["GKE", "EKS", "AKS", "OPENSHIFT", "MINIKUBE"]:
        if platform not in k8s_platforms:
            continue
        info = k8s_platforms[platform]
        name = platform_display[platform]

        if platform == "MINIKUBE":
            k8s_ver = info.get("max", info.get("version", ""))
            if k8s_ver:
                lines.append(f"{name} <OVERRIDE> with Kubernetes v{k8s_ver}")
        else:
            min_v = info.get("min", "")
            max_v = info.get("max", "")
            if min_v and max_v:
                lines.append(f"{name} {min_v} - {max_v}")
            elif max_v:
                lines.append(f"{name} {max_v}")

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
        _add_if_exists(lines, versions, "IMAGE_PBM", "PBM")
        _add_if_exists(lines, versions, "IMAGE_PMM2_CLIENT", "PMM Client")
        _add_if_exists(lines, versions, "IMAGE_PMM3_CLIENT", "PMM3 Client")
        _add_if_exists(lines, versions, "IMAGE_LOGCOLLECTOR", "LogCollector")
        lines.append("cert-manager: <OVERRIDE>")

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
        lines.append("cert-manager: <OVERRIDE>")

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
        lines.append("cert-manager: <OVERRIDE>")

    elif db_type == "POSTGRESQL":
        pg_versions = []
        for key, val in sorted(versions.items()):
            if key.startswith("IMAGE_POSTGRESQL") and val:
                ver_str = val.split(":")[-1] if ":" in val else val
                pg_versions.append(ver_str)
        if pg_versions:
            lines.append(f"Postgres: {', '.join(pg_versions)}")
        _add_unique_versions(lines, versions, "IMAGE_BACKREST", "PGBackRest")
        _add_unique_versions(lines, versions, "IMAGE_PGBOUNCER", "PGBouncer")
        lines.append("patroni: <OVERRIDE>")
        _add_postgis_versions(lines, versions)
        _add_if_exists(lines, versions, "IMAGE_PMM_CLIENT", "PMM")
        _add_if_exists(lines, versions, "IMAGE_PMM3_CLIENT", "PMM3")
        lines.append("cert-manager: <OVERRIDE>")

    return lines


def _add_if_exists(lines: list[str], versions: dict[str, str], key: str, label: str):
    """Helper to add version line if key exists."""
    if key in versions and versions[key]:
        val = versions[key]
        ver = val.split(":")[-1] if ":" in val else val
        lines.append(f"{label}: {ver}")


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
    pairs = []
    for key, val in sorted(versions.items()):
        if key.startswith("IMAGE_POSTGIS") and val:
            tag = val.split(":")[-1] if ":" in val else val
            match = re.search(r"ppg([\d.]+)-postgres-gis([\d.]+)", tag)
            if match:
                pairs.append(f"{match.group(1)}: {match.group(2)}")
    if pairs:
        lines.append(f"PostGis: {', '.join(pairs)}")


def generate_test_plan(versions_file: str, primary_platform: str = "GKE") -> list[dict]:
    versions = parse_versions_file(versions_file)
    db_type = detect_db_type(versions)

    if not db_type:
        print("Error: Could not detect database type")
        return []

    db_versions = extract_db_versions(versions, db_type)
    k8s_platforms = extract_k8s_platforms(versions)

    print(f"Database: {db_type}")
    print(f"Versions: {', '.join(db_versions)}")
    print(f"Latest: {db_versions[0]}")
    print(f"Primary platform: {primary_platform}\n")

    test_plan = []
    latest = db_versions[0]

    for platform, k8s_info in k8s_platforms.items():
        for db_ver in db_versions:
            is_latest = db_ver == latest

            if not is_latest and platform != primary_platform:
                continue

            if platform == "MINIKUBE":
                k8s_labels = ["max"]
            elif is_latest:
                k8s_labels = ["min", "max"]
            else:
                k8s_labels = ["max"]

            cw_modes = ["YES", "NO"] if is_latest else ["YES"]

            for k8s_label in k8s_labels:
                k8s_actual = k8s_info.get(k8s_label) or k8s_info.get("version")
                if not k8s_actual:
                    continue

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

    return test_plan


def generate_markdown_table(test_plan: list[dict]) -> str:
    lines = [
        "| Platform | K8s Version | Pillar Version | CW | Failed Tests | Done |",
        "|----------|-------------|----------------|----|--------------|----|",
    ]

    sorted_plan = sorted(
        test_plan,
        key=lambda x: (x["platform"], x["k8s_version_actual"], x["pillar_version"]),
    )

    for t in sorted_plan:
        cw = "Yes" if t["cluster_wide"] == "YES" else "No"
        lines.append(
            f"| {t['platform']} | {t['k8s_version_actual']} | {t['pillar_version']} | {cw} |  |  |"
        )

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description="Generate test plan from versions file"
    )
    parser.add_argument("versions_file", help="Path to versions file")
    args = parser.parse_args()

    if not Path(args.versions_file).exists():
        print(f"Error: File '{args.versions_file}' not found")
        return 1

    versions = parse_versions_file(args.versions_file)
    db_type = detect_db_type(versions)

    if not db_type:
        print("Error: Could not detect database type")
        return 1

    test_plan = generate_test_plan(args.versions_file)
    if not test_plan:
        return 1

    with open("test-plan.json", "w") as f:
        json.dump(test_plan, f, indent=2)
    print("Saved: test-plan.json")

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
