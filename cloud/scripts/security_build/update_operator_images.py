#!/usr/bin/env python3

"""Update operator image references used by security release manifests."""

import argparse
import re
from pathlib import Path


DEPLOYMENT_KIND_PATTERN = re.compile(
    r"^kind:[ \t]*Deployment[ \t]*$",
    re.MULTILINE,
)

DOCUMENT_SEPARATOR_PATTERN = re.compile(
    r"(^---[ \t]*(?:\r?\n|$))",
    re.MULTILINE,
)


def build_operator_repository_pattern(operator: str) -> str:
    """Build a regex pattern for supported operator image repositories."""
    return rf"(?:docker\.io/)?(?:percona|perconalab)/{re.escape(operator)}"


def build_yaml_image_pattern(operator: str) -> re.Pattern[str]:
    """Build a regex pattern for operator image references in YAML files."""
    repository_pattern = build_operator_repository_pattern(operator)

    return re.compile(
        rf"^(?P<prefix>[ \t]*#?[ \t]*image:[ \t]+)"
        rf"{repository_pattern}:(?P<tag>[^\r\n \t#]+)"
        rf"(?P<suffix>[ \t]*(?:#.*)?)$",
        re.MULTILINE,
    )


def build_release_version_pattern(operator: str) -> re.Pattern[str]:
    """Build a regex pattern for IMAGE_OPERATOR entries."""
    repository_pattern = build_operator_repository_pattern(operator)

    return re.compile(
        rf"^IMAGE_OPERATOR={repository_pattern}:[^\r\n \t]+$",
        re.MULTILINE,
    )


def update_operator_deployments(
    path: Path,
    image: str,
    image_pattern: re.Pattern[str],
) -> tuple[int, bool]:
    """Update operator image references in Deployment documents."""
    contents = path.read_text(encoding="utf-8")
    matches = 0
    parts = DOCUMENT_SEPARATOR_PATTERN.split(contents)

    for index, part in enumerate(parts):
        if not DEPLOYMENT_KIND_PATTERN.search(part):
            continue

        def replacement(match: re.Match[str]) -> str:
            return (
                f"{match.group('prefix')}"
                f"{image}"
                f"{match.group('suffix')}"
            )

        parts[index], document_matches = image_pattern.subn(
            replacement,
            part,
        )
        matches += document_matches

    updated = "".join(parts)
    changed = updated != contents

    if changed:
        path.write_text(updated, encoding="utf-8")

    return matches, changed


def yaml_structure(line: str) -> tuple[int, str]:
    """Return indentation and effective YAML structure for a line."""
    candidate = line.rstrip("\r\n")

    comment = re.match(
        r"^(?P<indent>[ \t]*)#(?P<body>.*)$",
        candidate,
    )

    if comment:
        candidate = (
            f"{comment.group('indent')}"
            f"{comment.group('body')}"
        )

    stripped = candidate.lstrip(" \t")

    return len(candidate) - len(stripped), stripped


def update_init_containers(
    path: Path,
    image: str,
    image_pattern: re.Pattern[str],
) -> tuple[int, bool]:
    """Update operator images used inside initContainer sections."""
    contents = path.read_text(encoding="utf-8")
    lines = contents.splitlines(keepends=True)

    init_container_indent: int | None = None
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

        def replacement(match: re.Match[str]) -> str:
            return (
                f"{match.group('prefix')}"
                f"{image}"
                f"{match.group('suffix')}"
            )

        lines[index], line_matches = image_pattern.subn(
            replacement,
            line,
        )
        matches += line_matches

    updated = "".join(lines)
    changed = updated != contents

    if changed:
        path.write_text(updated, encoding="utf-8")

    return matches, changed


def update_release_versions(
    path: Path,
    image: str,
    release_pattern: re.Pattern[str],
) -> tuple[int, bool]:
    """Update IMAGE_OPERATOR in the release versions file."""
    contents = path.read_text(encoding="utf-8")

    updated, matches = release_pattern.subn(
        f"IMAGE_OPERATOR={image}",
        contents,
    )

    changed = updated != contents

    if changed:
        path.write_text(updated, encoding="utf-8")

    return matches, changed


def parse_args() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--operator",
        required=True,
        help="Operator repository name, e.g. percona-server-mysql-operator",
    )

    parser.add_argument(
        "--image",
        required=True,
        help="Full replacement image including tag",
    )

    parser.add_argument(
        "--deploy-dir",
        type=Path,
        default=Path("deploy"),
    )

    parser.add_argument(
        "--release-versions",
        type=Path,
        default=Path("e2e-tests/release_versions"),
    )

    return parser.parse_args()


def main() -> None:
    """Update operator image references."""
    args = parse_args()

    image_pattern = build_yaml_image_pattern(args.operator)
    release_pattern = build_release_version_pattern(args.operator)

    matched = 0
    changed_files: list[Path] = []

    operator_manifests = [
        args.deploy_dir / "bundle.yaml",
        args.deploy_dir / "cw-bundle.yaml",
        args.deploy_dir / "operator.yaml",
        args.deploy_dir / "cw-operator.yaml",
    ]

    for path in operator_manifests:
        if not path.exists():
            continue

        file_matches, changed = update_operator_deployments(
            path,
            args.image,
            image_pattern,
        )

        matched += file_matches

        if changed:
            changed_files.append(path)

    cr_path = args.deploy_dir / "cr.yaml"

    if cr_path.exists():
        file_matches, changed = update_init_containers(
            cr_path,
            args.image,
            image_pattern,
        )

        matched += file_matches

        if changed:
            changed_files.append(cr_path)

    if args.release_versions.exists():
        release_matches, release_changed = update_release_versions(
            args.release_versions,
            args.image,
            release_pattern,
        )

        matched += release_matches

        if release_changed:
            changed_files.append(args.release_versions)

    if not matched:
        raise RuntimeError(
            f"No image references found for operator: {args.operator}"
        )

    if not changed_files:
        print("Operator image references are already up to date")
        return

    print("Updated operator image references:")

    for path in changed_files:
        print(f"- {path}")


if __name__ == "__main__":
    main()
