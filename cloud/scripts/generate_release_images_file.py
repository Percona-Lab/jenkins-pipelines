#!/usr/bin/env python3

import re
import argparse
import requests
from datetime import datetime
from packaging.version import parse as parse_version
from concurrent.futures import ThreadPoolExecutor, as_completed

PMM_CLIENT = "2.44.1-1"
PMM_SERVER = "2.44.1"
PG_MAJOR_VERSIONS = ["14", "15", "16", "17", "18"]

MONTH_MAP = {
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

_session = requests.Session()


def fetch_parallel(tasks):
    results = {}
    with ThreadPoolExecutor(max_workers=12) as ex:
        futures = {ex.submit(fn, *args): key for key, (fn, *args) in tasks.items()}
        for f in as_completed(futures):
            try:
                results[futures[f]] = f.result()
            except Exception as e:
                print(f"Error fetching {futures[f]}: {e}")
                results[futures[f]] = None
    return results


def fetch_percona_version(product):
    resp = _session.post(
        "https://www.percona.com/products-api.php", data={"version": product}
    )
    versions = []
    for m in re.findall(r'value="([^"]*)"', resp.text):
        if v := re.search(r"(\d+(?:\.\d+)*(?:-\d+)?)", m.split("|")[0]):
            versions.append(v.group(1))
    return max(versions, key=parse_version) if versions else None


def fetch_dockerhub_tag(repo, prefix=None):
    resp = _session.get(
        f"https://registry.hub.docker.com/v2/repositories/{repo}/tags",
        params={"page_size": 100},
    )
    resp.raise_for_status()
    versions = []
    for tag in resp.json()["results"]:
        name = tag["name"]
        if prefix and not name.startswith(f"{prefix}."):
            continue
        try:
            parsed = parse_version(name.lstrip("v").replace("-", "."))
            if not parsed.is_prerelease:
                versions.append((parsed, name))
        except:
            continue
    return max(versions)[1] if versions else None


def format_image_line(name, repo, tag):
    return f"IMAGE_{name}=percona/{repo}:{tag}"


def append_image_line(lines, name, repo, tag):
    if tag:
        lines.append(format_image_line(name, repo, tag))


def build_pg_image_lines(operator_version, versions, pmm3):
    lines = [
        format_image_line("OPERATOR", "percona-postgresql-operator", operator_version)
    ]

    for major in PG_MAJOR_VERSIONS:
        pg_tag = versions.get(major)
        if not pg_tag:
            continue

        lines.append("")
        append_image_line(
            lines, f"POSTGRESQL{major}", "percona-distribution-postgresql", pg_tag
        )
        append_image_line(
            lines,
            f"PGBOUNCER{major}",
            "percona-pgbouncer",
            versions.get("pgbouncer"),
        )
        append_image_line(
            lines,
            f"POSTGIS{major}",
            "percona-distribution-postgresql-with-postgis",
            versions.get(f"postgis{major}"),
        )
        append_image_line(
            lines,
            f"BACKREST{major}",
            "percona-pgbackrest",
            versions.get("pgbackrest"),
        )

    lines.extend(
        [
            "",
            format_image_line(
                "UPGRADE",
                "percona-postgresql-operator",
                f"{operator_version}-upgrade",
            ),
            "",
            format_image_line("PMM_CLIENT", "pmm-client", PMM_CLIENT),
            format_image_line("PMM_SERVER", "pmm-server", PMM_SERVER),
        ]
    )
    if pmm3:
        lines.extend(
            [
                format_image_line("PMM3_CLIENT", "pmm-client", pmm3),
                format_image_line("PMM3_SERVER", "pmm-server", pmm3),
            ]
        )
    return lines


def get_image_tasks(op):
    P, D = fetch_percona_version, fetch_dockerhub_tag

    return {
        "psmdb": {
            "8.0": (P, "percona-server-mongodb-8.0"),
            "7.0": (P, "percona-server-mongodb-7.0"),
            "6.0": (P, "percona-server-mongodb-6.0"),
            "backup": (P, "percona-backup-mongodb"),
            "logcollector": (D, "percona/fluentbit"),
            "pmm3": (P, "pmm3"),
        },
        "pxc": {
            "8.4": (D, "percona/percona-xtradb-cluster", "8.4"),
            "8.0": (D, "percona/percona-xtradb-cluster", "8.0"),
            "5.7": (D, "percona/percona-xtradb-cluster", "5.7"),
            "backup84": (D, "percona/percona-xtrabackup", "8.4"),
            "backup80": (D, "percona/percona-xtrabackup", "8.0"),
            "backup57": (D, "percona/percona-xtrabackup", "2.4"),
            "haproxy": (D, "percona/haproxy"),
            "proxysql": (D, "percona/proxysql2"),
            "logcollector": (D, "percona/fluentbit"),
            "pmm3": (P, "pmm3"),
        },
        "pg": {
            "18": (D, "percona/percona-distribution-postgresql", "18"),
            "17": (D, "percona/percona-distribution-postgresql", "17"),
            "16": (D, "percona/percona-distribution-postgresql", "16"),
            "15": (D, "percona/percona-distribution-postgresql", "15"),
            "14": (D, "percona/percona-distribution-postgresql", "14"),
            "postgis18": (
                D,
                "percona/percona-distribution-postgresql-with-postgis",
                "18",
            ),
            "postgis17": (
                D,
                "percona/percona-distribution-postgresql-with-postgis",
                "17",
            ),
            "postgis16": (
                D,
                "percona/percona-distribution-postgresql-with-postgis",
                "16",
            ),
            "postgis15": (
                D,
                "percona/percona-distribution-postgresql-with-postgis",
                "15",
            ),
            "postgis14": (
                D,
                "percona/percona-distribution-postgresql-with-postgis",
                "14",
            ),
            "pgbackrest": (D, "percona/percona-pgbackrest"),
            "pgbouncer": (D, "percona/percona-pgbouncer"),
            "pmm3": (P, "pmm3"),
        },
        "ps": {
            "8.4": (D, "percona/percona-server", "8.4"),
            "8.0": (D, "percona/percona-server", "8.0"),
            "backup84": (D, "percona/percona-xtrabackup", "8.4"),
            "backup80": (D, "percona/percona-xtrabackup", "8.0"),
            "orchestrator": (D, "percona/percona-orchestrator"),
            "haproxy": (D, "percona/haproxy"),
            "toolkit": (D, "percona/percona-toolkit"),
            "pmm3": (P, "pmm3"),
        },
    }.get(op, {})


def build_standard_image_lines(op, operator_version, versions, pmm3):
    image_defs = {
        "psmdb": [
            ("OPERATOR", "percona-server-mongodb-operator", operator_version),
            ("MONGOD80", "percona-server-mongodb", versions.get("8.0")),
            ("MONGOD70", "percona-server-mongodb", versions.get("7.0")),
            ("MONGOD60", "percona-server-mongodb", versions.get("6.0")),
            ("BACKUP", "percona-backup-mongodb", versions.get("backup")),
            ("PMM_CLIENT", "pmm-client", PMM_CLIENT),
            ("PMM_SERVER", "pmm-server", PMM_SERVER),
            ("PMM3_CLIENT", "pmm-client", pmm3),
            ("PMM3_SERVER", "pmm-server", pmm3),
            ("LOGCOLLECTOR", "fluentbit", versions.get("logcollector")),
        ],
        "pxc": [
            ("OPERATOR", "percona-xtradb-cluster-operator", operator_version),
            ("PXC84", "percona-xtradb-cluster", versions.get("8.4")),
            ("PXC80", "percona-xtradb-cluster", versions.get("8.0")),
            ("PXC57", "percona-xtradb-cluster", versions.get("5.7")),
            ("BACKUP84", "percona-xtrabackup", versions.get("backup84")),
            ("BACKUP80", "percona-xtrabackup", versions.get("backup80")),
            ("BACKUP57", "percona-xtrabackup", versions.get("backup57")),
            ("PROXY", "proxysql2", versions.get("proxysql")),
            ("HAPROXY", "haproxy", versions.get("haproxy")),
            ("LOGCOLLECTOR", "fluentbit", versions.get("logcollector")),
            ("PMM_CLIENT", "pmm-client", PMM_CLIENT),
            ("PMM_SERVER", "pmm-server", PMM_SERVER),
            ("PMM3_CLIENT", "pmm-client", pmm3),
            ("PMM3_SERVER", "pmm-server", pmm3),
        ],
        "ps": [
            ("OPERATOR", "percona-server-mysql-operator", operator_version),
            ("MYSQL84", "percona-server", versions.get("8.4")),
            ("BACKUP84", "percona-xtrabackup", versions.get("backup84")),
            (
                "ROUTER84",
                "percona-mysql-router",
                versions.get("8.4", "").split("-")[0] or None,
            ),
            ("MYSQL80", "percona-server", versions.get("8.0")),
            ("BACKUP80", "percona-xtrabackup", versions.get("backup80")),
            (
                "ROUTER80",
                "percona-mysql-router",
                versions.get("8.0", "").split("-")[0] or None,
            ),
            ("HAPROXY", "haproxy", versions.get("haproxy")),
            ("ORCHESTRATOR", "percona-orchestrator", versions.get("orchestrator")),
            ("TOOLKIT", "percona-toolkit", versions.get("toolkit")),
            ("PMM3_CLIENT", "pmm-client", pmm3),
            ("PMM3_SERVER", "pmm-server", pmm3),
        ],
    }.get(op, [])

    return [format_image_line(name, repo, tag) for name, repo, tag in image_defs if tag]


def get_image_lines(op, ver):
    versions = fetch_parallel(get_image_tasks(op))
    pmm3 = versions.pop("pmm3", None)
    if op == "pg":
        return build_pg_image_lines(ver, versions, pmm3)
    return build_standard_image_lines(op, ver, versions, pmm3)


def sort_vers(vers, rev=True):
    return sorted(vers, key=lambda x: [int(p) for p in x.split(".")], reverse=rev)


def filter_active(data, today):
    return [
        i["cycle"]
        for i in data
        if isinstance(i.get("eol"), str)
        and datetime.strptime(i["eol"], "%Y-%m-%d").date() >= today
    ]


def get_eks():
    resp = _session.get("https://endoflife.date/api/amazon-eks.json", timeout=10)
    resp.raise_for_status()
    return sort_vers(filter_active(resp.json(), datetime.now().date()))


def get_gke():
    resp = _session.get(
        "https://docs.cloud.google.com/feeds/kubernetes-engine-stable-channel-release-notes.xml",
        timeout=10,
    )
    resp.raise_for_status()
    if m := re.search(
        r"now available in the Stable channel.*?<ul>(.*?)</ul>",
        resp.text,
        re.DOTALL | re.IGNORECASE,
    ):
        minor_map = {}
        for v in re.findall(r"(\d+\.\d+\.\d+-gke\.\d+)", m.group(1)):
            minor_map[".".join(v.split(".")[:2])] = v
        return sort_vers(list(minor_map.keys()))
    return None


def parse_aks_versions(html, today):
    versions = []
    for ver, _, _, ga, eol in re.findall(
        r"<tr[^>]*>.*?<td>(\d+\.\d+)</td>.*?<td>([^<]+)</td>.*?<td>([^<]+)</td>.*?<td>([^<]+)</td>.*?<td>([^<]+)</td>.*?</tr>",
        html,
        re.DOTALL,
    ):
        ga, eol = ga.strip().lower(), eol.strip().lower()
        if not ga or ga == "-" or not eol or eol == "-":
            continue
        try:
            ga_p, eol_p = ga.replace(",", "").split(), eol.replace(",", "").split()
            if (int(ga_p[-1]), MONTH_MAP.get(ga_p[0][:3], 0)) > (
                today.year,
                today.month,
            ):
                continue
            eol_day = int(eol_p[1]) if len(eol_p) == 3 else 28
            if (
                datetime(
                    int(eol_p[-1]), MONTH_MAP.get(eol_p[0][:3], 1), min(eol_day, 28)
                ).date()
                >= today
            ):
                versions.append(ver)
        except Exception:
            continue
    return sort_vers(list(set(versions))) if versions else None


def get_aks_from_docs(today):
    resp = _session.get(
        "https://learn.microsoft.com/en-us/azure/aks/supported-kubernetes-versions?tabs=azure-cli",
        timeout=15,
    )
    resp.raise_for_status()
    return parse_aks_versions(resp.text, today)


def get_aks_from_eol(today):
    resp = _session.get(
        "https://endoflife.date/api/azure-kubernetes-service.json", timeout=10
    )
    resp.raise_for_status()
    return sort_vers(filter_active(resp.json(), today))


def get_aks():
    today = datetime.now().date()
    try:
        if versions := get_aks_from_docs(today):
            return versions
    except Exception:
        pass
    return get_aks_from_eol(today)


def get_minikube():
    resp = _session.get(
        "https://api.github.com/repos/kubernetes/minikube/releases/latest", timeout=10
    )
    resp.raise_for_status()
    body = resp.json().get("body", "")
    if m := re.search(
        r"Kubernetes(?: version)? v?(\d+\.\d+\.\d+)", body, re.IGNORECASE
    ):
        return m.group(1)
    return None


def get_openshift():
    base_url = "https://mirror.openshift.com/pub/openshift-v4/amd64/clients/ocp"
    try:
        latest_resp = _session.get(f"{base_url}/stable/release.txt", timeout=10)
        latest_resp.raise_for_status()
    except Exception:
        return None, None

    latest_match = re.search(
        r"^Name:\s*(\d+\.\d+\.\d+)\s*$", latest_resp.text, re.MULTILINE
    )
    if not latest_match:
        return None, None
    latest_patch = latest_match.group(1)

    min_patch = None
    try:
        eol_resp = _session.get(
            "https://endoflife.date/api/red-hat-openshift.json", timeout=10
        )
        eol_resp.raise_for_status()
        active_minors = filter_active(eol_resp.json(), datetime.now().date())
        if active_minors:
            min_minor = min(active_minors, key=lambda x: [int(p) for p in x.split(".")])
            min_resp = _session.get(
                f"{base_url}/stable-{min_minor}/release.txt", timeout=10
            )
            min_resp.raise_for_status()
            min_match = re.search(
                r"^Name:\s*(\d+\.\d+\.\d+)\s*$", min_resp.text, re.MULTILINE
            )
            if min_match:
                min_patch = min_match.group(1)
    except Exception:
        min_patch = None

    return min_patch, latest_patch


K8S_VERSION_FETCHERS = {
    "GKE": ("gke", get_gke),
    "EKS": ("eks", get_eks),
    "AKS": ("aks", get_aks),
    "OPENSHIFT": ("os", get_openshift),
    "MINIKUBE": ("mk", get_minikube),
}


def get_supported_platforms(operator):
    platforms = ["GKE", "EKS", "OPENSHIFT", "MINIKUBE"]
    if operator != "ps":
        platforms.insert(2, "AKS")
    return platforms


def get_k8s_lines(operator):
    platforms = get_supported_platforms(operator)
    tasks = {
        key: (fetcher,)
        for platform in platforms
        for key, fetcher in [K8S_VERSION_FETCHERS[platform]]
    }
    r = fetch_parallel(tasks)
    lines = []
    for platform in ("GKE", "EKS", "AKS"):
        if platform not in platforms:
            continue
        key, _ = K8S_VERSION_FETCHERS[platform]
        if v := r.get(key):
            lines += [f"{platform}_MIN={v[-1]}", f"{platform}_MAX={v[0]}"]
    if "OPENSHIFT" in platforms and (os := r.get("os")):
        if os[0] and os[1]:
            lines += [f"OPENSHIFT_MIN={os[0]}", f"OPENSHIFT_MAX={os[1]}"]
    if "MINIKUBE" in platforms and (mk := r.get("mk")):
        minikube_key = "MINIKUBE_MAX" if operator in {"ps", "pg"} else "MINIKUBE_REL"
        lines.append(f"{minikube_key}={mk}")
    return lines


def main():
    parser = argparse.ArgumentParser(
        description="Generate version info for Percona Operators"
    )
    parser.add_argument("operator", choices=["psmdb", "pxc", "ps", "pg"])
    parser.add_argument("version")
    args = parser.parse_args()

    print(f"Fetching versions for {args.operator} {args.version}...")
    lines = get_image_lines(args.operator, args.version) + get_k8s_lines(args.operator)
    file_name = f"release_versions.txt"
    with open(file_name, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"Written to {file_name}")


if __name__ == "__main__":
    main()
