#!/usr/bin/env python3
"""Docker Image to JSON Converter - generates version service JSON from image definitions."""

import re
import requests
import json
import sys
from typing import Dict, Optional, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed


def get_digest(
    image: str, arch: str = "amd64", session: requests.Session = None
) -> Optional[str]:
    if session is None:
        session = requests.Session()
    try:
        repo, tag = image.split(":")
        token_url = f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull"
        token = session.get(token_url, timeout=10).json()["token"]
        headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.docker.distribution.manifest.list.v2+json",
        }
        r = session.get(
            f"https://registry-1.docker.io/v2/{repo}/manifests/{tag}",
            headers=headers,
            timeout=10,
        )
        r.raise_for_status()
        manifest = r.json()
        if "manifests" in manifest:
            for m in manifest["manifests"]:
                if m["platform"]["architecture"] == arch:
                    return m["digest"].replace("sha256:", "")
            print(f"Warning: No {arch} manifest in {image}")
            return None
        return r.headers.get("Docker-Content-Digest", "").replace("sha256:", "")
    except Exception as e:
        print(f"Error fetching digest for {image} ({arch}): {e}")
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
    return "psmdb"


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


def categorize_pxc(images: Dict[str, str], hashes: Dict[str, Tuple]) -> Dict[str, Dict]:
    result = {
        "pxc": {},
        "pmm": {},
        "proxysql": {},
        "haproxy": {},
        "backup": {},
        "log_collector": {},
        "operator": {},
    }
    for key, path in images.items():
        ver = extract_version(path)
        h = hashes.get(key, (None, None))
        entry = make_entry(path, h[0], h[1])
        if "OPERATOR" in key:
            result["operator"][ver] = entry
        elif "PXC" in key:
            result["pxc"][ver] = entry
        elif "BACKUP" in key:
            result["backup"][ver] = entry
        elif "PMM_CLIENT" in key or key == "IMAGE_PMM3_CLIENT":
            result["pmm"][ver] = entry
        elif "LOGCOLLECTOR" in key:
            result["log_collector"][ver] = entry
        elif "PROXY" in key and "HAPROXY" not in key:
            result["proxysql"][ver] = entry
        elif "HAPROXY" in key:
            result["haproxy"][ver] = entry
    return {k: v for k, v in result.items() if v}


def categorize_psmdb(
    images: Dict[str, str], hashes: Dict[str, Tuple]
) -> Dict[str, Dict]:
    result = {"mongod": {}, "backup": {}, "pmm": {}, "logcollector": {}, "operator": {}}
    for key, path in images.items():
        ver = extract_version(path)
        h = hashes.get(key, (None, None))
        entry = make_entry(path, h[0], h[1])
        if "OPERATOR" in key:
            result["operator"][ver] = entry
        elif "MONGOD" in key:
            result["mongod"][ver] = entry
        elif "BACKUP" in key:
            result["backup"][ver] = entry
        elif "PMM_CLIENT" in key or key == "IMAGE_PMM3_CLIENT":
            result["pmm"][ver] = entry
        elif "LOGCOLLECTOR" in key:
            result["logcollector"][ver] = entry
    return {k: v for k, v in result.items() if v}


def categorize_ps(images: Dict[str, str], hashes: Dict[str, Tuple]) -> Dict[str, Dict]:
    result = {
        "mysql": {},
        "pmm": {},
        "haproxy": {},
        "orchestrator": {},
        "router": {},
        "toolkit": {},
        "backup": {},
        "operator": {},
    }
    for key, path in images.items():
        ver = extract_version(path)
        h = hashes.get(key, (None, None))
        entry = make_entry(path, h[0], h[1])
        if "OPERATOR" in key:
            result["operator"][ver] = entry
        elif "MYSQL" in key:
            result["mysql"][ver] = entry
        elif "BACKUP" in key:
            result["backup"][ver] = entry
        elif "PMM_CLIENT" in key or key == "IMAGE_PMM3_CLIENT":
            result["pmm"][ver] = entry
        elif "HAPROXY" in key:
            result["haproxy"][ver] = entry
        elif "ORCHESTRATOR" in key:
            result["orchestrator"][ver] = entry
        elif "ROUTER" in key:
            result["router"][ver] = entry
        elif "TOOLKIT" in key:
            result["toolkit"][ver] = entry
    return {k: v for k, v in result.items() if v}


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
        elif "PMM_CLIENT" in key or key == "IMAGE_PMM3_CLIENT":
            result["pmm"][ver] = entry
    return {k: v for k, v in result.items() if v}


def categorize_images(images: Dict[str, str], max_workers: int = 10) -> Dict:
    product_type = detect_product_type(images)
    print(f"Detected product type: {product_type}")

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
                print(f"✓ {key}: {path}")
            except Exception as e:
                print(f"✗ {key}: {e}")
                hash_results[key] = (None, None)
    session.close()

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


def main():
    if len(sys.argv) != 3:
        print("Usage: python generate_vs_frag.py <input.txt> <output.json>")
        sys.exit(1)

    input_file, output_file = sys.argv[1], sys.argv[2]
    print(f"Reading from {input_file}...")
    images = parse_input_file(input_file)

    print(f"\nFound {len(images)} images. Fetching hashes...\n")
    result = categorize_images(images)

    print(f"\nWriting to {output_file}...")
    with open(output_file, "w") as f:
        json.dump(result, f, indent=2)
    print("Done!")


if __name__ == "__main__":
    main()
