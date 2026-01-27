#!/usr/bin/env python3
"""Docker Image to JSON Converter - generates version service JSON from image definitions."""

import re
import requests
import json
import sys
import argparse
from typing import Dict, List, Optional, Tuple, Callable
from concurrent.futures import ThreadPoolExecutor, as_completed

DOCKER_AUTH_URL = "https://auth.docker.io/token"
DOCKER_REGISTRY_URL = "https://registry-1.docker.io/v2"
VERSION_SERVICE_BASE_URL = (
    "https://raw.githubusercontent.com/Percona-Lab/percona-version-service/main/sources"
)


def get_digest(
    image: str, arch: str = "amd64", session: requests.Session = None
) -> Optional[str]:
    if session is None:
        session = requests.Session()
    try:
        repo, tag = image.split(":")
        token_url = (
            f"{DOCKER_AUTH_URL}?service=registry.docker.io&scope=repository:{repo}:pull"
        )
        token = session.get(token_url, timeout=10).json()["token"]
        headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.docker.distribution.manifest.list.v2+json",
        }
        r = session.get(
            f"{DOCKER_REGISTRY_URL}/{repo}/manifests/{tag}",
            headers=headers,
            timeout=10,
        )
        r.raise_for_status()
        manifest = r.json()
        if "manifests" in manifest:
            for m in manifest["manifests"]:
                if m["platform"]["architecture"] == arch:
                    return m["digest"].replace("sha256:", "")
            print(f"Warning: No {arch} manifest in {image}", file=sys.stderr)
            return None
        return r.headers.get("Docker-Content-Digest", "").replace("sha256:", "")
    except Exception as e:
        print(f"Error: digest {image} ({arch}): {e}", file=sys.stderr)
        return None


def parse_input_file(filepath: str) -> Dict[str, str]:
    images = {}
    with open(filepath, "r") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                key = key.strip()
                if key.startswith("IMAGE"):
                    images[key] = value.strip()
    return images


def extract_version(image_path: str) -> str:
    return image_path.split(":")[1] if ":" in image_path else ""


def detect_product_type(images: Dict[str, str]) -> str:
    operator_image = images.get("IMAGE_OPERATOR", "")
    if "postgresql-operator" in operator_image:
        return "pg"
    if "server-mysql-operator" in operator_image:
        return "ps"
    if "xtradb-cluster-operator" in operator_image:
        return "pxc"
    if "server-mongodb-operator" in operator_image:
        return "psmdb"
    raise ValueError(f"Unknown operator type in IMAGE_OPERATOR: {operator_image!r}")


def fetch_image_hashes(
    image_path: str, session: requests.Session
) -> Tuple[str, Optional[str], Optional[str]]:
    return (
        image_path,
        get_digest(image_path, "amd64", session),
        get_digest(image_path, "arm64", session),
    )


def get_operator_version(images: Dict[str, str]) -> str:
    return extract_version(images.get("IMAGE_OPERATOR", ""))


def make_entry(
    image_path: str, hash_amd64: Optional[str], hash_arm64: Optional[str]
) -> Dict:
    entry = {"image_path": image_path, "status": "recommended", "critical": False}
    if hash_amd64:
        entry["image_hash"] = hash_amd64
    if hash_arm64:
        entry["image_hash_arm64"] = hash_arm64
    return entry


def is_pmm(key: str) -> bool:
    return "PMM_CLIENT" in key or key == "IMAGE_PMM3_CLIENT"


def categorize_by_rules(
    images: Dict[str, str],
    hashes: Dict[str, Tuple],
    rules: List[Tuple[Callable[[str], bool], str]],
    categories: List[str],
) -> Dict[str, Dict]:
    result = {cat: {} for cat in categories}
    for key, path in images.items():
        ver = extract_version(path)
        h = hashes.get(key, (None, None))
        entry = make_entry(path, h[0], h[1])
        for check, category in rules:
            if check(key):
                result[category][ver] = entry
                break
    return {k: v for k, v in result.items() if v}


def categorize_pxc(images: Dict[str, str], hashes: Dict[str, Tuple]) -> Dict[str, Dict]:
    rules = [
        (lambda k: "OPERATOR" in k, "operator"),
        (lambda k: "PXC" in k, "pxc"),
        (lambda k: "BACKUP" in k, "backup"),
        (is_pmm, "pmm"),
        (lambda k: "LOGCOLLECTOR" in k, "log_collector"),
        (lambda k: "PROXY" in k and "HAPROXY" not in k, "proxysql"),
        (lambda k: "HAPROXY" in k, "haproxy"),
    ]
    categories = [
        "pxc",
        "pmm",
        "proxysql",
        "haproxy",
        "backup",
        "log_collector",
        "operator",
    ]
    return categorize_by_rules(images, hashes, rules, categories)


def categorize_psmdb(
    images: Dict[str, str], hashes: Dict[str, Tuple]
) -> Dict[str, Dict]:
    rules = [
        (lambda k: "OPERATOR" in k, "operator"),
        (lambda k: "MONGOD" in k, "mongod"),
        (lambda k: "BACKUP" in k, "backup"),
        (is_pmm, "pmm"),
        (lambda k: "LOGCOLLECTOR" in k, "log_collector"),
    ]
    categories = ["mongod", "backup", "pmm", "log_collector", "operator"]
    return categorize_by_rules(images, hashes, rules, categories)


def categorize_ps(images: Dict[str, str], hashes: Dict[str, Tuple]) -> Dict[str, Dict]:
    rules = [
        (lambda k: "OPERATOR" in k, "operator"),
        (lambda k: "MYSQL" in k, "mysql"),
        (lambda k: "BACKUP" in k, "backup"),
        (is_pmm, "pmm"),
        (lambda k: "HAPROXY" in k, "haproxy"),
        (lambda k: "ORCHESTRATOR" in k, "orchestrator"),
        (lambda k: "ROUTER" in k, "router"),
        (lambda k: "TOOLKIT" in k, "toolkit"),
    ]
    categories = [
        "mysql",
        "pmm",
        "haproxy",
        "orchestrator",
        "router",
        "toolkit",
        "backup",
        "operator",
    ]
    return categorize_by_rules(images, hashes, rules, categories)


def categorize_pg(images: Dict[str, str], hashes: Dict[str, Tuple]) -> Dict[str, Dict]:
    result = {
        "operator": {},
        "pgbackrest": {},
        "pgbouncer": {},
        "pmm": {},
        "postgis": {},
        "postgresql": {},
    }

    pg_version_map = {}
    for key, path in images.items():
        if "POSTGRESQL" in key and "OPERATOR" not in key:
            match = re.search(r"(\d+)$", key)
            if match:
                full_ver = extract_version(path)
                pg_version_map[match.group(1)] = (
                    full_ver.rsplit("-", 1)[0] if "-" in full_ver else full_ver
                )

    for key, path in images.items():
        ver = extract_version(path)
        h = hashes.get(key, (None, None))
        entry = make_entry(path, h[0], h[1])
        suffix_match = re.search(r"(\d+)$", key)
        suffix = suffix_match.group(1) if suffix_match else None

        if key == "IMAGE_OPERATOR":
            result["operator"][ver] = entry
        elif "POSTGRESQL" in key and suffix in pg_version_map:
            result["postgresql"][pg_version_map[suffix]] = entry
        elif "PGBOUNCER" in key and suffix in pg_version_map:
            result["pgbouncer"][pg_version_map[suffix]] = entry
        elif "POSTGIS" in key and suffix in pg_version_map:
            result["postgis"][pg_version_map[suffix]] = entry
        elif "BACKREST" in key and suffix in pg_version_map:
            result["pgbackrest"][pg_version_map[suffix]] = entry
        elif is_pmm(key):
            result["pmm"][ver] = entry
    return {k: v for k, v in result.items() if v}


def categorize_images(images: Dict[str, str], max_workers: int = 10) -> Dict:
    product_type = detect_product_type(images)
    operator_version = get_operator_version(images)
    session = requests.Session()

    hash_results = {}
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {
            executor.submit(fetch_image_hashes, path, session): (key, path)
            for key, path in images.items()
        }
        for future in as_completed(futures):
            key, path = futures[future]
            try:
                _, h_amd, h_arm = future.result()
                hash_results[key] = (h_amd, h_arm)
            except Exception as e:
                print(f"Error: {key}: {e}", file=sys.stderr)
                hash_results[key] = (None, None)
    session.close()

    missing_digests = []
    for key, (h_amd, h_arm) in hash_results.items():
        image = images.get(key, key)
        if h_amd is None:
            missing_digests.append(f"{image} (amd64)")
        if h_arm is None:
            missing_digests.append(f"{image} (arm64)")

    if missing_digests:
        print(
            f"\nWARNING: Missing digests for {len(missing_digests)} image(s):",
            file=sys.stderr,
        )
        for m in missing_digests:
            print(f"  - {m}", file=sys.stderr)
        print("", file=sys.stderr)

    categorizers = {
        "pxc": categorize_pxc,
        "ps": categorize_ps,
        "pg": categorize_pg,
        "psmdb": categorize_psmdb,
    }
    matrix = categorizers.get(product_type, categorize_psmdb)(images, hash_results)

    return {
        "versions": [
            {
                "operator": operator_version,
                "product": f"{product_type}-operator",
                "matrix": matrix,
            }
        ]
    }


PXC_VERSION_LIMITS = {
    "pxc": {"8.4": 5, "8.0": 5, "5.7": 5},
    "pmm": {"2": 1, "3": 1},
    "proxysql": 1,
    "haproxy": 1,
    "backup": {"8.4": 1, "8.0": 1, "2.4": 1},
    "log_collector": 1,
    "operator": 1,
}

PSMDB_VERSION_LIMITS = {
    "mongod": {"8.0": 5, "7.0": 5, "6.0": 5},
    "pmm": {"2": 1, "3": 1},
    "backup": 1,
    "log_collector": 1,
    "operator": 1,
}

PS_VERSION_LIMITS = {
    "mysql": {"8.4": 5, "8.0": 5},
    "pmm": 1,
    "haproxy": 1,
    "orchestrator": 1,
    "router": {"8.4": 1, "8.0": 1},
    "toolkit": 1,
    "backup": {"8.4": 1, "8.0": 1},
    "operator": 1,
}

PG_VERSION_LIMITS = {
    "postgresql": {"13": 2, "14": 2, "15": 2, "16": 2, "17": 2},
    "pgbackrest": {"13": 2, "14": 2, "15": 2, "16": 2, "17": 2},
    "pgbouncer": {"13": 2, "14": 2, "15": 2, "16": 2, "17": 2},
    "postgis": {"13": 2, "14": 2, "15": 2, "16": 2, "17": 2},
    "pmm": {"2": 1, "3": 1},
    "operator": 1,
}

VERSION_LIMITS = {
    "pxc-operator": PXC_VERSION_LIMITS,
    "psmdb-operator": PSMDB_VERSION_LIMITS,
    "ps-operator": PS_VERSION_LIMITS,
    "pg-operator": PG_VERSION_LIMITS,
}


def generate_pxc_dep(result: Dict) -> Dict:
    """Generate dependency rules for PXC operator."""
    return {
        "backup": {
            "8.4.0": {">=": [{"var": "productVersion"}, "8.4"]},
            "8.0.35": {
                "and": [
                    {">=": [{"var": "productVersion"}, "8.0"]},
                    {"<": [{"var": "productVersion"}, "8.4"]},
                ]
            },
            "2.4.29": {
                "and": [
                    {">=": [{"var": "productVersion"}, "5.7"]},
                    {"<": [{"var": "productVersion"}, "8.0"]},
                ]
            },
        }
    }


def generate_psmdb_dep(result: Dict) -> Dict:
    """Generate dependency rules for PSMDB operator."""
    return {}


def generate_pg_dep(result: Dict) -> Dict:
    """Generate dependency rules for PG operator based on newest version per major."""
    matrix = result["versions"][0]["matrix"]
    dep = {}

    for category in ["pgbackrest", "postgis", "pgbouncer"]:
        if category not in matrix:
            continue

        by_major = {}
        for ver in matrix[category].keys():
            major = int(ver.split(".")[0])
            if major not in by_major:
                by_major[major] = []
            by_major[major].append(ver)

        if not by_major:
            continue

        newest_per_major = []
        for major, versions in by_major.items():
            newest = max(versions, key=parse_version_key)
            newest_per_major.append((major, newest))

        newest_per_major.sort(reverse=True)
        max_major = newest_per_major[0][0]

        dep[category] = {}
        for major, ver in newest_per_major:
            if major == max_major:
                dep[category][ver] = {">=": [{"var": "productVersion"}, ver]}
            else:
                dep[category][ver] = {
                    "and": [
                        {">=": [{"var": "productVersion"}, ver]},
                        {"<": [{"var": "productVersion"}, f"{major + 1}.0"]},
                    ]
                }

    return dep


def generate_ps_dep(result: Dict) -> Dict:
    """Generate dependency rules for PS operator based on newest version per major."""
    matrix = result["versions"][0]["matrix"]
    dep = {}

    for category in ["backup", "router"]:
        if category not in matrix:
            continue

        by_major = {}
        for ver in matrix[category].keys():
            major = get_major_minor(ver)
            if major not in by_major:
                by_major[major] = []
            by_major[major].append(ver)

        if not by_major:
            continue

        newest_per_major = []
        for major, versions in by_major.items():
            newest = max(versions, key=parse_version_key)
            newest_per_major.append((major, newest))

        newest_per_major.sort(key=lambda x: parse_version_key(x[0]), reverse=True)
        max_major = newest_per_major[0][0]

        dep[category] = {}
        for major, ver in newest_per_major:
            if major == max_major:
                dep[category][ver] = {">=": [{"var": "productVersion"}, major]}
            else:
                next_major_idx = [m for m, _ in newest_per_major].index(major) - 1
                next_major = newest_per_major[next_major_idx][0]
                dep[category][ver] = {
                    "and": [
                        {">=": [{"var": "productVersion"}, major]},
                        {"<": [{"var": "productVersion"}, next_major]},
                    ]
                }

    return dep


DEP_GENERATORS = {
    "pxc-operator": generate_pxc_dep,
    "psmdb-operator": generate_psmdb_dep,
    "pg-operator": generate_pg_dep,
    "ps-operator": generate_ps_dep,
}


def generate_dep_file(product: str, output_file: str, result: Dict) -> None:
    """Generate dependency file for the given product."""
    generator = DEP_GENERATORS.get(product)
    if generator is None:
        return

    dep_file = output_file.replace(".json", ".dep.json")
    dep_data = generator(result)
    with open(dep_file, "w") as f:
        json.dump(dep_data, f, indent=2, sort_keys=True)


def parse_version_key(ver_str: str) -> Tuple:
    """Parse version string for sorting, handles versions like 8.0.42-33.1"""
    parts = re.split(r"[.-]", ver_str)
    result = []
    for p in parts:
        try:
            result.append(int(p))
        except ValueError:
            result.append(p)
    return tuple(result)


def get_major_minor(ver_str: str, major_only: bool = False) -> str:
    """Extract major.minor from version string like 8.0.42-33.1 -> 8.0

    If major_only=True, returns just the major version (e.g., 2.44.1-1 -> 2)
    """
    parts = ver_str.split(".")
    if major_only or len(parts) < 2:
        return parts[0].split("-")[0]
    return f"{parts[0]}.{parts[1].split('-')[0]}"


def fetch_previous_release(
    operator_version: str, product: str, base_url: str = None
) -> Dict:
    """Fetch previous release JSON from version service repo."""
    if base_url is None:
        base_url = VERSION_SERVICE_BASE_URL

    url = f"{base_url}/operator.{operator_version}.{product}.json"
    try:
        r = requests.get(url, timeout=30)
        r.raise_for_status()
        return r.json()
    except Exception as e:
        print(f"Error fetching {url}: {e}", file=sys.stderr)
        return None


def remove_recommended_status(data: Dict, categories_to_update: set = None) -> Dict:
    """Change 'recommended' status to 'available' only for specified categories.

    If categories_to_update is None, updates all categories.
    If specified, only updates categories in that set.
    """
    for version_entry in data.get("versions", []):
        matrix = version_entry.get("matrix", {})
        for category, versions in matrix.items():
            if (
                categories_to_update is not None
                and category not in categories_to_update
            ):
                continue
            for ver_key, ver_data in versions.items():
                if ver_data.get("status") == "recommended":
                    ver_data["status"] = "available"
    return data


def merge_fragment(old_data: Dict, fragment: Dict) -> Dict:
    """Merge new fragment into old data, fragment takes precedence."""
    if not old_data or not old_data.get("versions"):
        return fragment

    result = {"versions": []}
    frag_entry = fragment["versions"][0]
    old_entry = old_data["versions"][0]

    merged_matrix = {}
    all_categories = set(old_entry.get("matrix", {}).keys()) | set(
        frag_entry.get("matrix", {}).keys()
    )

    for category in all_categories:
        old_versions = old_entry.get("matrix", {}).get(category, {})
        new_versions = frag_entry.get("matrix", {}).get(category, {})
        merged_matrix[category] = {**old_versions, **new_versions}

    result["versions"].append(
        {
            "operator": frag_entry["operator"],
            "product": frag_entry["product"],
            "matrix": merged_matrix,
        }
    )

    return result


def trim_old_versions(data: Dict, limits: Dict) -> Dict:
    """Remove old versions based on configured limits per category."""
    for version_entry in data.get("versions", []):
        matrix = version_entry.get("matrix", {})
        for category, versions in list(matrix.items()):
            limit = limits.get(category)
            if limit is None:
                continue

            if isinstance(limit, dict):
                use_major_only = all("." not in k for k in limit.keys())
                by_major = {}
                for ver_key in versions.keys():
                    major = get_major_minor(ver_key, major_only=use_major_only)
                    if major not in by_major:
                        by_major[major] = []
                    by_major[major].append(ver_key)

                versions_to_keep = set()
                for major, ver_list in by_major.items():
                    sorted_vers = sorted(ver_list, key=parse_version_key, reverse=True)
                    major_limit = limit.get(major, 1)
                    versions_to_keep.update(sorted_vers[:major_limit])

                matrix[category] = {
                    k: v for k, v in versions.items() if k in versions_to_keep
                }
            else:
                sorted_vers = sorted(
                    versions.keys(), key=parse_version_key, reverse=True
                )
                versions_to_keep = set(sorted_vers[:limit])
                matrix[category] = {
                    k: v for k, v in versions.items() if k in versions_to_keep
                }

    return data


def generate_full_release_from_fragment(
    fragment: Dict, previous_version: str, output_file: str
) -> Dict:
    """Generate full release JSON by merging fragment dict with previous release."""
    frag_entry = fragment["versions"][0]
    product = frag_entry["product"]
    frag_categories = set(frag_entry.get("matrix", {}).keys())

    old_data = fetch_previous_release(previous_version, product)
    if old_data is None:
        print("Warning: using fragment as-is (no previous release)", file=sys.stderr)
        result = fragment
    else:
        old_data = remove_recommended_status(old_data, frag_categories)
        result = merge_fragment(old_data, fragment)

    limits = VERSION_LIMITS.get(product, {})
    if limits:
        result = trim_old_versions(result, limits)

    with open(output_file, "w") as f:
        json.dump(result, f, indent=2, sort_keys=True)

    return result


def generate_full_release(
    fragment_file: str, previous_version: str, output_file: str
) -> Dict:
    """Generate full release JSON by merging fragment file with previous release."""
    with open(fragment_file, "r") as f:
        fragment = json.load(f)
    return generate_full_release_from_fragment(fragment, previous_version, output_file)


def main():
    parser = argparse.ArgumentParser(
        description="Generate version service JSON fragments or full releases"
    )
    subparsers = parser.add_subparsers(dest="command", help="Commands")

    frag_parser = subparsers.add_parser(
        "frag", help="Generate fragment from image definitions"
    )
    frag_parser.add_argument("input_file", help="Input file with IMAGE_* definitions")
    frag_parser.add_argument("output_file", help="Output JSON file")

    full_parser = subparsers.add_parser(
        "full", help="Generate full release from fragment JSON"
    )
    full_parser.add_argument("fragment_file", help="Fragment JSON file")
    full_parser.add_argument(
        "previous_version", help="Previous operator version (e.g., 1.18.0)"
    )
    full_parser.add_argument("output_file", help="Output JSON file")

    release_parser = subparsers.add_parser(
        "release", help="Generate full release from input.txt (frag + merge)"
    )
    release_parser.add_argument(
        "input_file", help="Input file with IMAGE_* definitions"
    )
    release_parser.add_argument(
        "previous_version", help="Previous operator version (e.g., 1.18.0)"
    )
    release_parser.add_argument("output_file", help="Output JSON file")

    args = parser.parse_args()

    if args.command == "frag":
        images = parse_input_file(args.input_file)
        result = categorize_images(images)
        with open(args.output_file, "w") as f:
            json.dump(result, f, indent=2, sort_keys=True)
    elif args.command == "full":
        generate_full_release(
            args.fragment_file, args.previous_version, args.output_file
        )
    elif args.command == "release":
        images = parse_input_file(args.input_file)
        fragment = categorize_images(images)
        result = generate_full_release_from_fragment(
            fragment, args.previous_version, args.output_file
        )
        product = fragment["versions"][0]["product"]
        generate_dep_file(product, args.output_file, result)
    else:
        if len(sys.argv) == 3:
            images = parse_input_file(sys.argv[1])
            result = categorize_images(images)
            with open(sys.argv[2], "w") as f:
                json.dump(result, f, indent=2, sort_keys=True)
        else:
            parser.print_help()


if __name__ == "__main__":
    main()
