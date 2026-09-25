#!/usr/bin/env python3

"""Update operator image references used by security release manifests."""

import argparse
import re
from pathlib import Path
from typing import Optional, Tuple


OPERATOR_REPOSITORY_PATTERN = (
    r"(?:docker\.io/)?(?:percona|perconalab)/"
    r"percona-server-mysql-operator"
)
YAML_IMAGE_PATTERN = re.compile(
    rf"^(?P<prefix>[ \t]*#?[ \t]*image:[ \t]+)"
    rf"{OPERATOR_REPOSITORY_PATTERN}:(?P<tag>[^\r\n \t#]+)"
    rf"(?P<suffix>[ \t]*(?:#.*)?)$",
    re.MULTILINE,
)
RELEASE_VERSION_PATTERN = re.compile(
    rf"^IMAGE_OPERATOR={OPERATOR_REPOSITORY_PATTERN}:[^\r\n \t]+$",
    re.MULTILINE,
)
DEPLOYMENT_KIND_PATTERN = re.compile(
    r"^kind:[ \t]*Deployment[ \t]*$",
    re.MULTILINE,
)
DOCUMENT_SEPARATOR_PATTERN = re.compile(r"(^---[ \t]*(?:\r?\n|$))", re.MULTILINE)


def update_operator_deployments(path: Path, image: str) -> Tuple[int, bool]:
    contents = path.read_text(encoding="utf-8")
    matches = 0
    parts = DOCUMENT_SEPARATOR_PATTERN.split(contents)

    for index, part in enumerate(parts):
        if not DEPLOYMENT_KIND_PATTERN.search(part):
            continue

        def replacement(match: re.Match) -> str:
            return f"{match.group('prefix')}{image}{match.group('suffix')}"

        parts[index], document_matches = YAML_IMAGE_PATTERN.subn(replacement, part)
        matches += document_matches

    updated = "".join(parts)
    changed = updated != contents

    if changed:
        path.write_text(updated, encoding="utf-8")

    return matches, changed


def yaml_structure(line: str) -> Tuple[int, str]:
    candidate = line.rstrip("\r\n")
    comment = re.match(r"^(?P<indent>[ \t]*)#(?P<body>.*)$", candidate)

    if comment:
        candidate = f"{comment.group('indent')}{comment.group('body')}"

    stripped = candidate.lstrip(" \t")
    return len(candidate) - len(stripped), stripped


def update_init_containers(path: Path, image: str) -> Tuple[int, bool]:
    contents = path.read_text(encoding="utf-8")
    lines = contents.splitlines(keepends=True)
    init_container_indent: Optional[int] = None
    matches = 0

    for index, line in enumerate(lines):
        indent, structure = yaml_structure(line)

        if structure.startswith("initContainer:"):
            init_container_indent = indent
            continue

        if init_container_indent is None or not structure:
            continue

        if indent <= init_container_indent:
            init_container_indent = None
            continue

        if not structure.startswith("image:"):
            continue

        def replacement(match: re.Match) -> str:
            return f"{match.group('prefix')}{image}{match.group('suffix')}"

        lines[index], line_matches = YAML_IMAGE_PATTERN.subn(replacement, line)
        matches += line_matches

    updated = "".join(lines)
    changed = updated != contents

    if changed:
        path.write_text(updated, encoding="utf-8")

    return matches, changed


def update_release_versions(path: Path, image: str) -> Tuple[int, bool]:
    contents = path.read_text(encoding="utf-8")
    updated, matches = RELEASE_VERSION_PATTERN.subn(
        f"IMAGE_OPERATOR={image}",
        contents,
    )
    changed = updated != contents

    if changed:
        path.write_text(updated, encoding="utf-8")

    return matches, changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", required=True)
    parser.add_argument("--deploy-dir", type=Path, default=Path("deploy"))
    parser.add_argument(
        "--release-versions",
        type=Path,
        default=Path("e2e-tests/release_versions"),
    )
    args = parser.parse_args()

    matched = 0
    changed_files = []

    operator_manifests = [
        args.deploy_dir / "bundle.yaml",
        args.deploy_dir / "cw-bundle.yaml",
        args.deploy_dir / "operator.yaml",
        args.deploy_dir / "cw-operator.yaml",
    ]

    for path in operator_manifests:
        if not path.exists():
            continue

        file_matches, changed = update_operator_deployments(path, args.image)
        matched += file_matches
        if changed:
            changed_files.append(path)

    cr_path = args.deploy_dir / "cr.yaml"
    if cr_path.exists():
        file_matches, changed = update_init_containers(cr_path, args.image)
        matched += file_matches
        if changed:
            changed_files.append(cr_path)

    release_matches, release_changed = update_release_versions(
        args.release_versions,
        args.image,
    )
    matched += release_matches
    if release_changed:
        changed_files.append(args.release_versions)

    if not matched:
        raise RuntimeError("No operator image references were found")

    if changed_files:
        print("Updated operator image references:")
        for path in changed_files:
            print(f"- {path}")
    else:
        print("Operator image references are already up to date")


if __name__ == "__main__":
    main()
