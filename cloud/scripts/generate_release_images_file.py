#!/usr/bin/env python3

import re
import argparse
import requests
from datetime import datetime
from packaging.version import parse as parse_version
from concurrent.futures import ThreadPoolExecutor, as_completed

PMM_CLIENT = "2.44.1-1"
PMM_SERVER = "2.44.1"
LOGCOLLECTOR = "4.0.1"
PG_MAJOR_VERSIONS = ["13", "14", "15", "16", "17"]

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


def get_image_lines(op, ver):
    P, D = fetch_percona_version, fetch_dockerhub_tag

    base = {
        "psmdb": {
            "8.0": (P, "percona-server-mongodb-8.0"),
            "7.0": (P, "percona-server-mongodb-7.0"),
            "6.0": (P, "percona-server-mongodb-6.0"),
            "backup": (P, "percona-backup-mongodb"),
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
            "pmm3": (P, "pmm3"),
        },
        "pg": {
            "17": (D, "percona/percona-distribution-postgresql", "17"),
            "16": (D, "percona/percona-distribution-postgresql", "16"),
            "15": (D, "percona/percona-distribution-postgresql", "15"),
            "14": (D, "percona/percona-distribution-postgresql", "14"),
            "13": (D, "percona/percona-distribution-postgresql", "13"),
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

    v = fetch_parallel(base)
    pmm3 = v.pop("pmm3", None)

    img = {
        "psmdb": [
            ("OPERATOR", "percona-server-mongodb-operator", ver),
            ("MONGOD80", "percona-server-mongodb", v.get("8.0")),
            ("MONGOD70", "percona-server-mongodb", v.get("7.0")),
            ("MONGOD60", "percona-server-mongodb", v.get("6.0")),
            ("BACKUP", "percona-backup-mongodb", v.get("backup")),
            ("PMM_CLIENT", "pmm-client", PMM_CLIENT),
            ("PMM_SERVER", "pmm-server", PMM_SERVER),
            ("PMM3_CLIENT", "pmm-client", pmm3),
            ("PMM3_SERVER", "pmm-server", pmm3),
            ("LOGCOLLECTOR", "fluentbit", LOGCOLLECTOR),
        ],
        "pxc": [
            ("OPERATOR", "percona-xtradb-cluster-operator", ver),
            ("PXC84", "percona-xtradb-cluster", v.get("8.4")),
            ("PXC80", "percona-xtradb-cluster", v.get("8.0")),
            ("PXC57", "percona-xtradb-cluster", v.get("5.7")),
            ("BACKUP84", "percona-xtrabackup", v.get("backup84")),
            ("BACKUP80", "percona-xtrabackup", v.get("backup80")),
            ("BACKUP57", "percona-xtrabackup", v.get("backup57")),
            ("PROXY", "proxysql2", v.get("proxysql")),
            ("HAPROXY", "haproxy", v.get("haproxy")),
            ("LOGCOLLECTOR", "fluentbit", LOGCOLLECTOR),
            ("PMM_CLIENT", "pmm-client", PMM_CLIENT),
            ("PMM_SERVER", "pmm-server", PMM_SERVER),
            ("PMM3_CLIENT", "pmm-client", pmm3),
            ("PMM3_SERVER", "pmm-server", pmm3),
        ],
        "pg": [("OPERATOR", "percona-postgresql-operator", ver)]
        + [
            (f"POSTGRESQL{n}", "percona-distribution-postgresql", v.get(n))
            for n in PG_MAJOR_VERSIONS
        ]
        + [
            (f"PGBACKREST{n}", "percona-pgbackrest", v.get("pgbackrest"))
            for n in PG_MAJOR_VERSIONS
        ]
        + [
            (f"PGBOUNCER{n}", "percona-pgbouncer", v.get("pgbouncer"))
            for n in PG_MAJOR_VERSIONS
        ]
        + [
            (f"POSTGIS{n}", "percona-postgresql-operator", "postgis")
            for n in PG_MAJOR_VERSIONS
        ]
        + [
            ("UPGRADE", "percona-postgresql-operator", f"{ver}-upgrade"),
            ("PMM_CLIENT", "pmm-client", PMM_CLIENT),
            ("PMM_SERVER", "pmm-server", PMM_SERVER),
            ("PMM3_CLIENT", "pmm-client", pmm3),
            ("PMM3_SERVER", "pmm-server", pmm3),
        ],
        "ps": [
            ("OPERATOR", "percona-server-mysql-operator", ver),
            ("MYSQL84", "percona-server", v.get("8.4")),
            ("BACKUP84", "percona-xtrabackup", v.get("backup84")),
            (
                "ROUTER84",
                "percona-mysql-router",
                v.get("8.4", "").split("-")[0] or None,
            ),
            ("MYSQL80", "percona-server", v.get("8.0")),
            ("BACKUP80", "percona-xtrabackup", v.get("backup80")),
            (
                "ROUTER80",
                "percona-mysql-router",
                v.get("8.0", "").split("-")[0] or None,
            ),
            ("HAPROXY", "haproxy", v.get("haproxy")),
            ("ORCHESTRATOR", "percona-orchestrator", v.get("orchestrator")),
            ("TOOLKIT", "percona-toolkit", v.get("toolkit")),
            ("PMM3_CLIENT", "pmm-client", pmm3),
            ("PMM3_SERVER", "pmm-server", pmm3),
        ],
    }.get(op, [])

    return [f"IMAGE_{name}=percona/{repo}:{tag}" for name, repo, tag in img if tag]


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


def get_aks():
    today = datetime.now().date()
    try:
        resp = _session.get(
            "https://learn.microsoft.com/en-us/azure/aks/supported-kubernetes-versions?tabs=azure-cli",
            timeout=15,
        )
        resp.raise_for_status()
        versions = []
        for ver, _, _, ga, eol in re.findall(
            r"<tr[^>]*>.*?<td>(\d+\.\d+)</td>.*?<td>([^<]+)</td>.*?<td>([^<]+)</td>.*?<td>([^<]+)</td>.*?<td>([^<]+)</td>.*?</tr>",
            resp.text,
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
            except:
                continue
        if versions:
            return sort_vers(list(set(versions)))
    except:
        pass
    resp = _session.get(
        "https://endoflife.date/api/azure-kubernetes-service.json", timeout=10
    )
    resp.raise_for_status()
    return sort_vers(filter_active(resp.json(), today))


def get_minikube():
    resp = _session.get(
        "https://api.github.com/repos/kubernetes/minikube/releases/latest", timeout=10
    )
    resp.raise_for_status()
    return resp.json().get("tag_name", "").lstrip("v")


def get_openshift():
    resp = _session.get("https://endoflife.date/api/red-hat-openshift.json", timeout=10)
    resp.raise_for_status()
    versions = sort_vers(filter_active(resp.json(), datetime.now().date()))
    if not versions:
        return None, None
    patches = {}
    for minor in [versions[0], versions[-1]]:
        try:
            r = _session.get(
                f"https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable-{minor}/release.txt",
                timeout=10,
            )
            if r.ok:
                for line in r.text.split("\n"):
                    if line.startswith("Name:"):
                        patches[minor] = line.split(":")[1].strip()
                        break
        except:
            pass
    return patches.get(versions[-1]), patches.get(versions[0])


def get_k8s_lines():
    tasks = {
        "eks": (get_eks,),
        "gke": (get_gke,),
        "aks": (get_aks,),
        "mk": (get_minikube,),
        "os": (get_openshift,),
    }
    r = fetch_parallel(tasks)
    lines = []
    for name, key in [("GKE", "gke"), ("EKS", "eks"), ("AKS", "aks")]:
        if v := r.get(key):
            lines += [f"{name}_MIN={v[-1]}", f"{name}_MAX={v[0]}"]
    if os := r.get("os"):
        if os[0] and os[1]:
            lines += [f"OPENSHIFT_MIN={os[0]}", f"OPENSHIFT_MAX={os[1]}"]
    if mk := r.get("mk"):
        lines.append(f"MINIKUBE_REL={mk}")
    return lines


def main():
    parser = argparse.ArgumentParser(
        description="Generate version info for Percona Operators"
    )
    parser.add_argument("operator", choices=["psmdb", "pxc", "ps", "pg"])
    parser.add_argument("version")
    args = parser.parse_args()

    print(f"Fetching versions for {args.operator} {args.version}...")
    lines = get_image_lines(args.operator, args.version) + get_k8s_lines()
    file_name = f"release_versions.txt"
    with open(file_name, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"Written to {file_name}")


if __name__ == "__main__":
    main()
