#!/usr/bin/env python3
"""Verify that operator, Helm chart, and version-service releases are in sync."""

import argparse
import difflib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

import yaml


EXTRA_CRD_CHARTS = {
    "psmdb": ["psmdb-operator-crds"],
}


def load_yaml_docs(path: Path) -> List[Any]:
    with path.open() as stream:
        return [doc for doc in yaml.safe_load_all(stream) if doc is not None]


def render_helm_template(
    chart_dir: Path,
    set_args: Optional[List[str]] = None,
    include_crds: bool = False,
) -> List[Any]:
    command = ["helm", "template", "release-name", str(chart_dir)]
    for value in set_args or []:
        command.extend(["--set", value])
    if include_crds:
        command.append("--include-crds")

    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "helm template failed")
    return [doc for doc in yaml.safe_load_all(result.stdout) if doc is not None]


def strip_descriptions(node: Any, parent_key: Optional[str] = None) -> Any:
    if isinstance(node, dict):
        result = {}
        for key, value in node.items():
            if key == "description" and parent_key != "properties" and isinstance(value, str):
                continue
            result[key] = strip_descriptions(value, key)
        return result
    if isinstance(node, list):
        return [strip_descriptions(item, parent_key) for item in node]
    return node


def documents_by_kind(docs: Iterable[Any], kind: str) -> List[Dict[str, Any]]:
    return [doc for doc in docs if isinstance(doc, dict) and doc.get("kind") == kind]


def first_document(docs: Iterable[Any], kind: str) -> Optional[Dict[str, Any]]:
    return next(iter(documents_by_kind(docs, kind)), None)


def crds_by_name(docs: Iterable[Any]) -> Dict[str, Any]:
    result = {}
    for doc in documents_by_kind(docs, "CustomResourceDefinition"):
        name = doc.get("metadata", {}).get("name", "<unknown>")
        result[name] = strip_descriptions(doc)
    return result


def load_chart_crds(chart_dir: Path) -> List[Any]:
    crd_dir = chart_dir / "crds"
    crd_files = []
    if crd_dir.exists():
        crd_files = sorted(crd_dir.glob("*.yaml")) + sorted(crd_dir.glob("*.yml"))

    if crd_files:
        docs = []
        for path in crd_files:
            docs.extend(load_yaml_docs(path))
        return docs

    return render_helm_template(chart_dir, include_crds=True)


def check_crds(operator_path: Path, helm_dir: Path, abbrev: str) -> List[str]:
    if not operator_path.exists():
        return [f"Missing CRD file in operator repo: {operator_path}"]

    errors = []
    operator_crds = crds_by_name(load_yaml_docs(operator_path))
    chart_names = [f"{abbrev}-operator", *EXTRA_CRD_CHARTS.get(abbrev, [])]

    for chart_name in chart_names:
        chart_dir = helm_dir / "charts" / chart_name
        if not chart_dir.exists():
            errors.append(f"Missing Helm chart: {chart_dir}")
            continue
        try:
            helm_crds = crds_by_name(load_chart_crds(chart_dir))
        except RuntimeError as exc:
            errors.append(f"Failed to render Helm chart '{chart_name}' for CRDs: {exc}")
            continue
        if not helm_crds:
            errors.append(f"No CRDs found in Helm chart '{chart_name}'")
            continue

        for name in sorted(operator_crds.keys() - helm_crds.keys()):
            errors.append(
                f"CRD '{name}' is present in operator repo but missing from Helm chart '{chart_name}'"
            )
        for name in sorted(helm_crds.keys() - operator_crds.keys()):
            errors.append(
                f"CRD '{name}' is present in Helm chart '{chart_name}' but missing from operator repo"
            )
        for name in sorted(operator_crds.keys() & helm_crds.keys()):
            if operator_crds[name] != helm_crds[name]:
                errors.append(
                    f"CRD '{name}' differs between operator repo and Helm chart '{chart_name}'"
                )

    return errors


def collect_images(tree: Any, found: Optional[Set[str]] = None) -> Set[str]:
    if found is None:
        found = set()
    if isinstance(tree, dict):
        for key, value in tree.items():
            if key == "image" and isinstance(value, str) and value:
                found.add(value)
            elif (
                key == "image"
                and isinstance(value, dict)
                and value.get("repository")
                and value.get("tag")
            ):
                found.add(f"{value['repository']}:{value['tag']}")
            else:
                collect_images(value, found)
    elif isinstance(tree, list):
        for value in tree:
            collect_images(value, found)
    return found


def collect_images_with_paths(
    tree: Any,
    prefix: str = "",
    found: Optional[Dict[str, str]] = None,
) -> Dict[str, str]:
    if found is None:
        found = {}
    if isinstance(tree, dict):
        for key, value in tree.items():
            path = f"{prefix}.{key}" if prefix else key
            if key == "image" and isinstance(value, str) and value:
                found[path] = value
            elif (
                key == "image"
                and isinstance(value, dict)
                and value.get("repository")
                and value.get("tag")
            ):
                found[path] = f"{value['repository']}:{value['tag']}"
            else:
                collect_images_with_paths(value, path, found)
    elif isinstance(tree, list):
        for value in tree:
            collect_images_with_paths(value, prefix, found)
    return found


def check_images(cr_path: Path, values_path: Path) -> Tuple[List[str], Set[str]]:
    if not cr_path.exists():
        return [f"Missing cr.yaml in operator repo: {cr_path}"], set()
    if not values_path.exists():
        return [f"Missing values.yaml in Helm charts repo: {values_path}"], set()

    operator_images = collect_images(load_yaml_docs(cr_path))
    helm_images = collect_images(load_yaml_docs(values_path))
    errors = [
        f"Image '{image}' is used in cr.yaml but not found in Helm values.yaml"
        for image in sorted(operator_images - helm_images)
    ]
    errors.extend(
        f"Image '{image}' is used in Helm values.yaml but not found in cr.yaml"
        for image in sorted(helm_images - operator_images)
    )
    return errors, operator_images | helm_images


def collect_recommended_images(node: Any, found: Optional[Set[str]] = None) -> Set[str]:
    if found is None:
        found = set()
    if isinstance(node, dict):
        if node.get("status") == "recommended" and node.get("image_path"):
            found.add(node["image_path"])
        for value in node.values():
            collect_recommended_images(value, found)
    elif isinstance(node, list):
        for value in node:
            collect_recommended_images(value, found)
    return found


def check_vs_recommended(
    images: Set[str], vs_path: Path
) -> Tuple[List[str], Optional[str]]:
    if not vs_path.exists():
        return [], f"Version-service file not found ({vs_path}); skipping this check"

    with vs_path.open() as stream:
        recommended = collect_recommended_images(json.load(stream))
    errors = [
        f"Image '{image}' is not marked 'recommended' in the version-service JSON"
        for image in sorted(images - recommended)
    ]
    return errors, None


def parse_readme_table(path: Path) -> Dict[str, str]:
    rows = {}
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line.startswith("|") or "---" in line:
            continue
        cells = [cell.strip() for cell in line.strip("|").split("|")]
        if len(cells) < 3:
            continue
        parameter = re.sub(r"[`~]", "", cells[0]).strip()
        default = cells[-1].replace("`", "").strip()
        if parameter and parameter.lower() != "parameter":
            rows[parameter] = default
    return rows


def readme_images(rows: Dict[str, str]) -> Dict[str, str]:
    images = {}
    parts: Dict[str, Dict[str, str]] = {}
    for parameter, default in rows.items():
        if parameter.endswith(".image.repository") or parameter == "image.repository":
            base = parameter[: -len(".repository")]
            parts.setdefault(base, {})["repository"] = default
        elif parameter.endswith(".image.tag") or parameter == "image.tag":
            base = parameter[: -len(".tag")]
            parts.setdefault(base, {})["tag"] = default
        elif parameter.endswith(".image") or parameter == "image":
            if default:
                images[parameter] = default
    for base, values in parts.items():
        if values.get("repository") and values.get("tag"):
            images[base] = f"{values['repository']}:{values['tag']}"
    return images


def check_readme_images(values_path: Path, readme_path: Path) -> List[str]:
    if not values_path.exists():
        return [f"Missing values.yaml in Helm charts repo: {values_path}"]
    if not readme_path.exists():
        return [f"Missing README.md in Helm charts repo: {readme_path}"]

    values_images: Dict[str, str] = {}
    for doc in load_yaml_docs(values_path):
        collect_images_with_paths(doc, found=values_images)
    documented = readme_images(parse_readme_table(readme_path))

    errors = []
    for path, image in sorted(values_images.items()):
        if path not in documented:
            errors.append(
                f"values.yaml image '{image}' at '{path}' is not documented in README.md"
            )
        elif documented[path] != image:
            errors.append(
                f"values.yaml image at '{path}' is '{image}' but README.md documents "
                f"'{documented[path]}'"
            )
    return errors


def unified_diff(
    title: str,
    operator_label: str,
    helm_label: str,
    operator_lines: List[str],
    helm_lines: List[str],
) -> str:
    lines = difflib.unified_diff(
        operator_lines,
        helm_lines,
        fromfile=operator_label,
        tofile=helm_label,
        lineterm="",
    )
    return f"  --- {title} ---\n" + "\n".join(f"    {line}" for line in lines)


Permission = Tuple[str, str, str, Tuple[str, ...]]


def normalize_permissions(rules: Any) -> Set[Permission]:
    permissions = set()
    for rule in rules or []:
        verbs = rule.get("verbs") or []
        resource_names = tuple(sorted(rule.get("resourceNames") or []))
        for api_group in rule.get("apiGroups") or [""]:
            for resource in rule.get("resources") or []:
                for verb in verbs:
                    permissions.add((api_group, resource, verb, resource_names))
        for url in rule.get("nonResourceURLs") or []:
            for verb in verbs:
                permissions.add(("<non-resource>", url, verb, ()))
    return permissions


def permission_lines(permissions: Set[Permission]) -> List[str]:
    lines = []
    for api_group, resource, verb, resource_names in sorted(permissions):
        group = api_group or '""'
        suffix = f" resourceNames={','.join(resource_names)}" if resource_names else ""
        lines.append(f"{group}/{resource}: {verb}{suffix}")
    return lines


def check_rbac(
    operator_dir: Path, helm_dir: Path, abbrev: str
) -> Tuple[List[str], List[str]]:
    errors = []
    diffs = []
    chart_dir = helm_dir / "charts" / f"{abbrev}-operator"
    checks = [
        ("Role", "namespaced", operator_dir / "deploy" / "rbac.yaml", []),
        (
            "ClusterRole",
            "cluster-wide",
            operator_dir / "deploy" / "cw-rbac.yaml",
            ["watchAllNamespaces=true"],
        ),
    ]

    for kind, mode, operator_path, set_args in checks:
        if not operator_path.exists():
            errors.append(f"Missing {mode} RBAC file in operator repo: {operator_path}")
            continue
        operator_doc = first_document(load_yaml_docs(operator_path), kind)
        if operator_doc is None:
            errors.append(f"No {kind} found in operator repo's {operator_path.name}")
            continue
        try:
            helm_doc = first_document(
                render_helm_template(chart_dir, set_args), kind
            )
        except RuntimeError as exc:
            errors.append(f"Failed to render Helm chart for {mode} RBAC: {exc}")
            continue
        if helm_doc is None:
            errors.append(f"Helm chart did not render a {kind} in {mode} mode")
            continue

        operator_permissions = normalize_permissions(operator_doc.get("rules"))
        helm_permissions = normalize_permissions(helm_doc.get("rules"))
        if operator_permissions == helm_permissions:
            continue

        missing = len(operator_permissions - helm_permissions)
        extra = len(helm_permissions - operator_permissions)
        errors.append(
            f"RBAC ({mode}) differs: {missing} permission(s) missing from Helm and "
            f"{extra} permission(s) only in Helm"
        )
        diffs.append(
            unified_diff(
                f"RBAC diff ({mode})",
                f"operator:{operator_path.name}",
                f"helm:rendered-{kind}",
                permission_lines(operator_permissions),
                permission_lines(helm_permissions),
            )
        )
    return errors, diffs


def operator_container(deployment: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    containers = (
        deployment.get("spec", {})
        .get("template", {})
        .get("spec", {})
        .get("containers", [])
        if deployment
        else []
    )
    return containers[0] if containers else None


def deployment_lines(container: Dict[str, Any]) -> List[str]:
    lines = [
        f"name: {container.get('name')}",
        f"command: {container.get('command') or []}",
    ]
    for name in sorted(
        entry.get("name") for entry in container.get("env", []) if entry.get("name")
    ):
        lines.append(f"env: {name}")
    for port in sorted(
        (
            entry.get("containerPort"),
            entry.get("protocol", "TCP"),
        )
        for entry in container.get("ports", [])
    ):
        lines.append(f"port: {port[0]}/{port[1]}")
    for probe_name in ("livenessProbe", "readinessProbe"):
        http_get = (container.get(probe_name) or {}).get("httpGet")
        if http_get:
            lines.append(
                f"{probe_name}: {http_get.get('path')}:{http_get.get('port')}/"
                f"{http_get.get('scheme')}"
            )
    return lines


def service_lines(service: Optional[Dict[str, Any]]) -> List[str]:
    if not service:
        return []
    ports = service.get("spec", {}).get("ports", [])
    return [
        f"service port: {port.get('port')} -> {port.get('targetPort')}"
        for port in sorted(ports, key=lambda item: (item.get("port"), str(item.get("targetPort"))))
    ]


def check_deployment(
    operator_dir: Path, helm_dir: Path, abbrev: str
) -> Tuple[List[str], List[str]]:
    errors = []
    diffs = []
    chart_dir = helm_dir / "charts" / f"{abbrev}-operator"
    checks = [
        ("namespaced", operator_dir / "deploy" / "operator.yaml", []),
        (
            "cluster-wide",
            operator_dir / "deploy" / "cw-operator.yaml",
            ["watchAllNamespaces=true"],
        ),
    ]

    for mode, operator_path, set_args in checks:
        if not operator_path.exists():
            errors.append(f"Missing {mode} operator deployment: {operator_path}")
            continue
        operator_docs = load_yaml_docs(operator_path)
        operator_deployment = first_document(operator_docs, "Deployment")
        try:
            helm_docs = render_helm_template(chart_dir, set_args)
        except RuntimeError as exc:
            errors.append(f"Failed to render Helm chart for {mode} deployment: {exc}")
            continue
        helm_deployment = first_document(helm_docs, "Deployment")
        if operator_deployment is None or helm_deployment is None:
            errors.append(f"Deployment ({mode}) is missing from operator repo or rendered Helm chart")
            continue

        operator_spec = operator_container(operator_deployment)
        helm_spec = operator_container(helm_deployment)
        if operator_spec is None or helm_spec is None:
            errors.append(f"Deployment ({mode}) has no operator container to compare")
            continue

        operator_service = first_document(operator_docs, "Service")
        helm_service = first_document(helm_docs, "Service")
        operator_summary = deployment_lines(operator_spec) + service_lines(operator_service)
        helm_summary = deployment_lines(helm_spec) + service_lines(helm_service)
        if operator_summary == helm_summary:
            continue

        errors.append(
            f"Deployment ({mode}) differs between operator repo and rendered Helm chart"
        )
        diffs.append(
            unified_diff(
                f"Deployment diff ({mode})",
                f"operator:{operator_path.name}",
                f"helm:rendered-deployment-{mode}",
                operator_summary,
                helm_summary,
            )
        )
    return errors, diffs


def print_section(name: str, errors: List[str], diffs: Optional[List[str]] = None) -> None:
    print(f"\n{name}: {'OK' if not errors else 'MISMATCH'}")
    for error in errors:
        print(f"  - {error}")
    for diff in diffs or []:
        print(diff)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check an operator release against Helm charts and version service"
    )
    parser.add_argument("abbrev", help="Operator abbreviation: pxc, psmdb, ps, or pg")
    parser.add_argument("version", help="Operator version, for example 1.20.0")
    parser.add_argument("operator_repo_dir")
    parser.add_argument("helm_repo_dir")
    parser.add_argument("vs_nonprod_repo_dir")
    parser.add_argument("vs_prod_repo_dir")
    args = parser.parse_args()

    operator_dir = Path(args.operator_repo_dir)
    helm_dir = Path(args.helm_repo_dir)
    db_chart = helm_dir / "charts" / f"{args.abbrev}-db"

    crd_errors = check_crds(
        operator_dir / "deploy" / "crd.yaml", helm_dir, args.abbrev
    )
    image_errors, images = check_images(
        operator_dir / "deploy" / "cr.yaml", db_chart / "values.yaml"
    )
    readme_errors = check_readme_images(
        db_chart / "values.yaml", db_chart / "README.md"
    )

    vs_name = f"operator.{args.version}.{args.abbrev}-operator.json"
    vs_nonprod_errors, vs_nonprod_skip = check_vs_recommended(
        images, Path(args.vs_nonprod_repo_dir) / "sources" / vs_name
    )
    vs_prod_errors, vs_prod_skip = check_vs_recommended(
        images, Path(args.vs_prod_repo_dir) / "sources" / vs_name
    )
    rbac_errors, rbac_diffs = check_rbac(operator_dir, helm_dir, args.abbrev)
    deployment_errors, deployment_diffs = check_deployment(
        operator_dir, helm_dir, args.abbrev
    )

    all_errors = (
        crd_errors
        + image_errors
        + readme_errors
        + vs_nonprod_errors
        + vs_prod_errors
        + rbac_errors
        + deployment_errors
    )

    print("=" * 80)
    print(f"Confirm release: {args.abbrev} {args.version}")
    print("=" * 80)
    print_section("CRDs", crd_errors)
    print_section("Images (cr.yaml vs values.yaml)", image_errors)
    print_section("README (values.yaml vs README.md)", readme_errors)

    for label, errors, skip_reason in (
        ("non-prod", vs_nonprod_errors, vs_nonprod_skip),
        ("prod", vs_prod_errors, vs_prod_skip),
    ):
        if skip_reason:
            print(f"\nVersion Service ({label}): SKIPPED ({skip_reason})")
        else:
            print_section(f"Version Service ({label})", errors)

    print_section("RBAC", rbac_errors, rbac_diffs)
    print_section("Deployment", deployment_errors, deployment_diffs)
    print()
    if all_errors:
        print(f"RESULT: OUT OF SYNC ({len(all_errors)} issue(s) found)")
        return 1

    skipped = vs_nonprod_skip or vs_prod_skip
    suffix = " (with skipped checks, see above)" if skipped else ""
    print(f"RESULT: IN SYNC{suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
