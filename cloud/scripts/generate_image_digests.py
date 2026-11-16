#!/usr/bin/env python3

import argparse
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

from generate_vs import fetch_image_hashes, parse_input_file


def format_digest(digest):
    return f"sha256:{digest}" if digest else "MISSING"


def image_group(key):
    if key.endswith("_COMMUNITY"):
        match = re.search(r"_(UBI\d+)_", key)
        if match:
            return f"{match.group(1)} community images"
        return "UBI9 community images"

    match = re.search(r"_UBI(\d+)$", key)
    if match:
        return f"UBI{match.group(1)} images"
    return "UBI9 images"


def render_digest_lines(images, digests):
    group_order = [
        "UBI9 images",
        "UBI8 images",
        "UBI10 images",
        "UBI8 community images",
        "UBI9 community images",
    ]
    groups = {group: [] for group in group_order}
    seen = {group: set() for group in groups}
    for key in sorted(images):
        group = image_group(key)
        if group not in groups:
            groups[group] = []
            seen[group] = set()
        image = images[key]
        if image in seen[group]:
            continue
        seen[group].add(image)
        amd64, arm64 = digests.get(key, (None, None))
        groups[group].append((image, amd64, arm64))

    lines = []
    for group in list(group_order) + [g for g in groups if g not in group_order]:
        entries = groups.get(group) or []
        if not entries:
            continue
        if lines:
            lines.append("")
        lines.extend(
            [
                f"## {group}",
                "",
                "| Image | Digest |",
                "|---|---|",
            ]
        )
        for image, amd64, arm64 in sorted(entries):
            lines.extend(
                [
                    f"| {image} (x86_64) | {format_digest(amd64)} |",
                    f"| {image} (ARM64) | {format_digest(arm64)} |",
                ]
            )
    return lines


def resolve_digests(images, max_workers=10):
    session = requests.Session()
    results = {}
    try:
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {
                executor.submit(fetch_image_hashes, image, session): key
                for key, image in images.items()
            }
            for future in as_completed(futures):
                key = futures[future]
                try:
                    _, amd64, arm64 = future.result()
                    results[key] = (amd64, arm64)
                except Exception as exc:
                    print(f"Error: {key}: {exc}", file=sys.stderr)
                    results[key] = (None, None)
    finally:
        session.close()
    return results


def main():
    parser = argparse.ArgumentParser(
        description="Generate an image-to-digest report from release versions"
    )
    parser.add_argument("input_file", help="Release versions file")
    parser.add_argument("output_file", help="Output digest report")
    args = parser.parse_args()

    images = parse_input_file(args.input_file)
    if not images:
        print(f"Error: no IMAGE_* entries found in {args.input_file}", file=sys.stderr)
        return 1

    digests = resolve_digests(images)
    lines = render_digest_lines(images, digests)
    Path(args.output_file).write_text("\n".join(lines) + "\n")

    missing = [
        f"{key} ({arch})"
        for key in sorted(images)
        for arch, digest in zip(("amd64", "arm64"), digests.get(key, (None, None)))
        if not digest
    ]
    if missing:
        print(
            f"Warning: {len(missing)} image digest(s) could not be resolved:",
            file=sys.stderr,
        )
        for item in missing:
            print(f"  - {item}", file=sys.stderr)

    print(f"Written to {args.output_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
