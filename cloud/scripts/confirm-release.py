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
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import yaml


EXTRA_CRD_CHARTS = {
    "psmdb": ["psmdb-operator-crds"],
}

OPERATOR_REPOSITORIES = {
    "psmdb": "percona-server-mongodb-operator",
    "pxc": "percona-xtradb-cluster-operator",
    "ps": "percona-server-mysql-operator",
    "pg": "percona-postgresql-operator",
}

VS_ENDPOINTS = {
    "development": "https://check-dev.percona.com/versions/v1",
    "production": "https://check.percona.com/versions/v1",
}

DOCKER_HUB_API = "https://hub.docker.com/v2/repositories"
DOCKER_HUB_PREFIXES = (
    "docker.io/",
    "index.docker.io/",
    "registry-1.docker.io/",
)


def log(section: str, message: str) -> None:
    print(f"[{section}] {message}", file=sys.stderr, flush=True)


def log_list(section: str, label: str, items: Iterable[str]) -> None:
    values = list(items)
    log(section, f"{label} ({len(values)}):")
    if not values:
        log(section, "  (none)")
        return
    for item in values:
        log(section, f"  - {item}")


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
    log("CRDs", f"operator: {operator_path}")
    if not operator_path.exists():
        log("CRDs", "operator CRD file is missing")
        return [f"Missing CRD file in operator repo: {operator_path}"]

    errors = []
    operator_crds = crds_by_name(load_yaml_docs(operator_path))
    log_list("CRDs", "operator CRDs", sorted(operator_crds))
    chart_names = [f"{abbrev}-operator", *EXTRA_CRD_CHARTS.get(abbrev, [])]

    for chart_name in chart_names:
        chart_dir = helm_dir / "charts" / chart_name
        log("CRDs", f"helm chart: {chart_dir}")
        if not chart_dir.exists():
            log("CRDs", f"helm chart '{chart_name}' is missing")
            errors.append(f"Missing Helm chart: {chart_dir}")
            continue
        try:
            helm_crds = crds_by_name(load_chart_crds(chart_dir))
        except RuntimeError as exc:
            log("CRDs", f"failed to load CRDs from '{chart_name}': {exc}")
            errors.append(f"Failed to render Helm chart '{chart_name}' for CRDs: {exc}")
            continue
        if not helm_crds:
            log("CRDs", f"no CRDs found in helm chart '{chart_name}'")
            errors.append(f"No CRDs found in Helm chart '{chart_name}'")
            continue

        log_list("CRDs", f"helm '{chart_name}' CRDs", sorted(helm_crds))
        missing_from_helm = sorted(operator_crds.keys() - helm_crds.keys())
        extra_in_helm = sorted(helm_crds.keys() - operator_crds.keys())
        identical = []
        different = []
        for name in sorted(operator_crds.keys() & helm_crds.keys()):
            if operator_crds[name] == helm_crds[name]:
                identical.append(name)
            else:
                different.append(name)

        log_list("CRDs", "identical", identical)
        log_list("CRDs", "only in operator", missing_from_helm)
        log_list("CRDs", f"only in helm '{chart_name}'", extra_in_helm)
        log_list("CRDs", "content differs", different)

        for name in missing_from_helm:
            errors.append(
                f"CRD '{name}' is present in operator repo but missing from Helm chart '{chart_name}'"
            )
        for name in extra_in_helm:
            errors.append(
                f"CRD '{name}' is present in Helm chart '{chart_name}' but missing from operator repo"
            )
        for name in different:
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
    log("Images", f"operator cr.yaml: {cr_path}")
    log("Images", f"helm values.yaml: {values_path}")
    if not cr_path.exists():
        log("Images", "cr.yaml is missing")
        return [f"Missing cr.yaml in operator repo: {cr_path}"], set()
    if not values_path.exists():
        log("Images", "values.yaml is missing")
        return [f"Missing values.yaml in Helm charts repo: {values_path}"], set()

    operator_images = collect_images(load_yaml_docs(cr_path))
    helm_images = collect_images(load_yaml_docs(values_path))
    only_operator = sorted(operator_images - helm_images)
    only_helm = sorted(helm_images - operator_images)
    log_list("Images", "cr.yaml", sorted(operator_images))
    log_list("Images", "values.yaml", sorted(helm_images))
    log_list("Images", "in both", sorted(operator_images & helm_images))
    log_list("Images", "only in cr.yaml", only_operator)
    log_list("Images", "only in values.yaml", only_helm)
    errors = [
        f"Image '{image}' is used in cr.yaml but not found in Helm values.yaml"
        for image in only_operator
    ]
    errors.extend(
        f"Image '{image}' is used in Helm values.yaml but not found in cr.yaml"
        for image in only_helm
    )
    return errors, operator_images | helm_images


def normalize_image(image: str) -> str:
    image = image.strip()
    if "@" in image:
        image = image.split("@", 1)[0]
    for prefix in DOCKER_HUB_PREFIXES:
        if image.startswith(prefix):
            image = image[len(prefix):]
            break
    if image.startswith("library/"):
        image = image[len("library/"):]
    return image


def collect_recommended_images(node: Any, found: Optional[Set[str]] = None) -> Set[str]:
    if found is None:
        found = set()
    if isinstance(node, dict):
        image_path = node.get("image_path") or node.get("imagePath")
        if node.get("status") == "recommended" and image_path:
            found.add(normalize_image(image_path))
        for value in node.values():
            collect_recommended_images(value, found)
    elif isinstance(node, list):
        for value in node:
            collect_recommended_images(value, found)
    return found


def images_missing_from_recommended(images: Set[str], recommended: Set[str]) -> List[str]:
    return sorted(
        image for image in images if normalize_image(image) not in recommended
    )


def log_recommended_comparison(
    section: str, images: Set[str], recommended: Set[str], missing: List[str]
) -> None:
    log_list(section, "release images", sorted(images))
    log_list(section, "recommended images", sorted(recommended))
    matched = []
    for image in sorted(images):
        normalized = normalize_image(image)
        if normalized in recommended:
            if image == normalized:
                matched.append(image)
            else:
                matched.append(f"{image} -> {normalized}")
    log_list(section, "matched as recommended", matched)
    log_list(section, "not marked recommended", missing)


def check_vs_recommended(
    images: Set[str], vs_path: Path, label: str = "VS JSON"
) -> Tuple[List[str], Optional[str]]:
    log(label, f"file: {vs_path}")
    if not vs_path.exists():
        log(label, "file not found, skipping")
        return [], f"Version-service file not found ({vs_path}); skipping this check"

    with vs_path.open() as stream:
        recommended = collect_recommended_images(json.load(stream))
    missing = images_missing_from_recommended(images, recommended)
    log_recommended_comparison(label, images, recommended, missing)
    errors = [
        f"Image '{image}' is not marked 'recommended' in the version-service JSON"
        for image in missing
    ]
    return errors, None


def fetch_json(url: str) -> Any:
    request = Request(url, headers={"Accept": "application/json", "User-Agent": "confirm-release"})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def check_live_vs(
    images: Set[str],
    abbrev: str,
    version: str,
    environment: str,
    base_url: str,
) -> List[str]:
    url = f"{base_url.rstrip('/')}/{abbrev}-operator/{version}"
    section = f"VS {environment}"
    log(section, f"GET {url}")
    try:
        payload = fetch_json(url)
    except HTTPError as exc:
        log(section, f"HTTP {exc.code}")
        return [f"{environment} endpoint returned HTTP {exc.code}: {url}"]
    except (URLError, TimeoutError, json.JSONDecodeError) as exc:
        log(section, f"request failed: {exc}")
        return [f"{environment} endpoint request failed ({url}): {exc}"]

    versions = payload.get("versions") if isinstance(payload, dict) else None
    if not versions:
        log(section, "endpoint returned no versions")
        return [f"{environment} endpoint has no data for {abbrev}-operator {version}: {url}"]

    recommended = collect_recommended_images(payload)
    missing = images_missing_from_recommended(images, recommended)
    log_recommended_comparison(section, images, recommended, missing)
    return [
        f"Image '{image}' is not marked 'recommended' in the {environment} endpoint"
        for image in missing
    ]


def docker_hub_tag(namespace: str, repository: str, tag: str) -> Tuple[bool, Optional[str], Optional[str]]:
    url = f"{DOCKER_HUB_API}/{namespace}/{repository}/tags/{tag}"
    try:
        payload = fetch_json(url)
    except HTTPError as exc:
        if exc.code == 404:
            return False, None, None
        return False, None, f"Docker Hub returned HTTP {exc.code} for {namespace}/{repository}:{tag}"
    except (URLError, TimeoutError, json.JSONDecodeError) as exc:
        return False, None, f"Docker Hub request failed for {namespace}/{repository}:{tag}: {exc}"
    return True, payload.get("digest"), None


def check_bundle_image(
    namespace: str, abbrev: str, version: str
) -> Tuple[List[str], List[str], Optional[str], str]:
    repository = OPERATOR_REPOSITORIES[abbrev]
    tag = f"{version}-community-bundle"
    image = f"{namespace}/{repository}:{tag}"
    exists, digest, request_error = docker_hub_tag(namespace, repository, tag)
    log("Bundle", f"{image}: exists={exists} digest={digest or '(none)'}")
    errors = []
    details = []
    if request_error:
        log("Bundle", f"{image}: {request_error}")
        errors.append(request_error)
    elif not exists:
        errors.append(f"Bundle image does not exist: {image}")
    elif not digest:
        errors.append(f"Docker Hub did not return a digest for bundle image: {image}")
    else:
        details.append(f"{image} digest {digest}")
    return errors, details, digest if exists else None, image


def check_bundle_digests(
    lab_image: str,
    lab_digest: Optional[str],
    prod_image: str,
    prod_digest: Optional[str],
) -> Tuple[List[str], List[str], Optional[str]]:
    if not lab_digest or not prod_digest:
        reason = "one or both bundle images are missing a digest"
        log("Bundle", f"digest comparison skipped: {reason}")
        return [], [], reason

    if lab_digest != prod_digest:
        log("Bundle", f"digests differ: {lab_digest} vs {prod_digest}")
        return (
            [
                f"Bundle image digests differ: {lab_image}={lab_digest}, "
                f"{prod_image}={prod_digest}"
            ],
            [],
            None,
        )

    log("Bundle", f"digests match: {lab_digest}")
    return [], [f"Both bundle images use digest {lab_digest}"], None


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
    log("README", f"values.yaml: {values_path}")
    log("README", f"README.md: {readme_path}")
    if not values_path.exists():
        log("README", "values.yaml is missing")
        return [f"Missing values.yaml in Helm charts repo: {values_path}"]
    if not readme_path.exists():
        log("README", "README.md is missing")
        return [f"Missing README.md in Helm charts repo: {readme_path}"]

    values_images: Dict[str, str] = {}
    for doc in load_yaml_docs(values_path):
        collect_images_with_paths(doc, found=values_images)
    documented = readme_images(parse_readme_table(readme_path))
    log_list(
        "README",
        "values.yaml images",
        [f"{path} = {image}" for path, image in sorted(values_images.items())],
    )
    log_list(
        "README",
        "README.md images",
        [f"{path} = {image}" for path, image in sorted(documented.items())],
    )

    errors = []
    matching = []
    missing = []
    mismatched = []
    for path, image in sorted(values_images.items()):
        if path not in documented:
            missing.append(f"{path} = {image}")
            errors.append(
                f"values.yaml image '{image}' at '{path}' is not documented in README.md"
            )
        elif documented[path] != image:
            mismatched.append(f"{path}: values.yaml={image} README.md={documented[path]}")
            errors.append(
                f"values.yaml image at '{path}' is '{image}' but README.md documents "
                f"'{documented[path]}'"
            )
        else:
            matching.append(f"{path} = {image}")
    log_list("README", "matching", matching)
    log_list("README", "missing from README.md", missing)
    log_list("README", "value mismatch", mismatched)
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
        helm_args = ",".join(set_args) if set_args else "(defaults)"
        log("RBAC", f"{mode}: {operator_path} vs helm template {chart_dir} --set {helm_args}")
        if not operator_path.exists():
            log("RBAC", f"{mode}: operator file is missing")
            errors.append(f"Missing {mode} RBAC file in operator repo: {operator_path}")
            continue
        operator_doc = first_document(load_yaml_docs(operator_path), kind)
        if operator_doc is None:
            log("RBAC", f"{mode}: no {kind} in {operator_path.name}")
            errors.append(f"No {kind} found in operator repo's {operator_path.name}")
            continue
        try:
            helm_doc = first_document(
                render_helm_template(chart_dir, set_args), kind
            )
        except RuntimeError as exc:
            log("RBAC", f"{mode}: helm template failed: {exc}")
            errors.append(f"Failed to render Helm chart for {mode} RBAC: {exc}")
            continue
        if helm_doc is None:
            log("RBAC", f"{mode}: helm chart did not render a {kind}")
            errors.append(f"Helm chart did not render a {kind} in {mode} mode")
            continue

        operator_permissions = normalize_permissions(operator_doc.get("rules"))
        helm_permissions = normalize_permissions(helm_doc.get("rules"))
        log(
            "RBAC",
            f"{mode}: operator={len(operator_permissions)} permissions, "
            f"helm={len(helm_permissions)} permissions",
        )
        if operator_permissions == helm_permissions:
            log("RBAC", f"{mode}: identical")
            continue
        log_list(
            "RBAC",
            f"{mode} missing from helm",
            permission_lines(operator_permissions - helm_permissions),
        )
        log_list(
            "RBAC",
            f"{mode} only in helm",
            permission_lines(helm_permissions - operator_permissions),
        )

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
        helm_args = ",".join(set_args) if set_args else "(defaults)"
        log(
            "Deployment",
            f"{mode}: {operator_path} vs helm template {chart_dir} --set {helm_args}",
        )
        if not operator_path.exists():
            log("Deployment", f"{mode}: operator file is missing")
            errors.append(f"Missing {mode} operator deployment: {operator_path}")
            continue
        operator_docs = load_yaml_docs(operator_path)
        operator_deployment = first_document(operator_docs, "Deployment")
        try:
            helm_docs = render_helm_template(chart_dir, set_args)
        except RuntimeError as exc:
            log("Deployment", f"{mode}: helm template failed: {exc}")
            errors.append(f"Failed to render Helm chart for {mode} deployment: {exc}")
            continue
        helm_deployment = first_document(helm_docs, "Deployment")
        if operator_deployment is None or helm_deployment is None:
            log("Deployment", f"{mode}: Deployment is missing from operator or helm")
            errors.append(f"Deployment ({mode}) is missing from operator repo or rendered Helm chart")
            continue

        operator_spec = operator_container(operator_deployment)
        helm_spec = operator_container(helm_deployment)
        if operator_spec is None or helm_spec is None:
            log("Deployment", f"{mode}: no operator container to compare")
            errors.append(f"Deployment ({mode}) has no operator container to compare")
            continue

        operator_service = first_document(operator_docs, "Service")
        helm_service = first_document(helm_docs, "Service")
        operator_summary = deployment_lines(operator_spec) + service_lines(operator_service)
        helm_summary = deployment_lines(helm_spec) + service_lines(helm_service)
        log_list("Deployment", f"{mode} operator", operator_summary)
        log_list("Deployment", f"{mode} helm", helm_summary)
        if operator_summary == helm_summary:
            log("Deployment", f"{mode}: identical")
            continue
        log("Deployment", f"{mode}: differs")

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


def print_group(name: str) -> None:
    print(f"\n{'=' * 80}")
    print(name)
    print("=" * 80)


def print_section(
    name: str,
    errors: List[str],
    diffs: Optional[List[str]] = None,
    details: Optional[List[str]] = None,
) -> None:
    print(f"\n{name}: {'OK' if not errors else 'MISMATCH'}")
    for detail in details or []:
        print(f"  - {detail}")
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
    log("confirm-release", f"{args.abbrev} {args.version}")
    log("confirm-release", f"operator repo: {operator_dir}")
    log("confirm-release", f"helm repo: {helm_dir}")

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
        images, Path(args.vs_nonprod_repo_dir) / "sources" / vs_name, "VS main"
    )
    vs_prod_errors, vs_prod_skip = check_vs_recommended(
        images, Path(args.vs_prod_repo_dir) / "sources" / vs_name, "VS prod"
    )
    vs_dev_endpoint_errors = check_live_vs(
        images,
        args.abbrev,
        args.version,
        "development",
        VS_ENDPOINTS["development"],
    )
    vs_prod_endpoint_errors = check_live_vs(
        images,
        args.abbrev,
        args.version,
        "production",
        VS_ENDPOINTS["production"],
    )
    rbac_errors, rbac_diffs = check_rbac(operator_dir, helm_dir, args.abbrev)
    deployment_errors, deployment_diffs = check_deployment(
        operator_dir, helm_dir, args.abbrev
    )
    lab_errors, lab_details, lab_digest, lab_image = check_bundle_image(
        "perconalab", args.abbrev, args.version
    )
    prod_errors, prod_details, prod_digest, prod_image = check_bundle_image(
        "percona", args.abbrev, args.version
    )
    bundle_digest_errors, bundle_digest_details, bundle_digest_skip = check_bundle_digests(
        lab_image, lab_digest, prod_image, prod_digest
    )

    all_errors = (
        crd_errors
        + image_errors
        + readme_errors
        + vs_nonprod_errors
        + vs_prod_errors
        + vs_dev_endpoint_errors
        + vs_prod_endpoint_errors
        + rbac_errors
        + deployment_errors
        + lab_errors
        + prod_errors
        + bundle_digest_errors
    )

    print("=" * 80)
    print(f"Confirm release: {args.abbrev} {args.version}")
    print("=" * 80)
    print_group("HELM / OPERATOR")
    print_section("CRDs", crd_errors)
    print_section("Images (cr.yaml vs values.yaml)", image_errors)
    print_section("README (values.yaml vs README.md)", readme_errors)
    print_section("RBAC", rbac_errors, rbac_diffs)
    print_section("Deployment", deployment_errors, deployment_diffs)

    print_group("VERSION SERVICE")
    for label, errors, skip_reason in (
        ("Branch (non-prod)", vs_nonprod_errors, vs_nonprod_skip),
        ("Branch (prod)", vs_prod_errors, vs_prod_skip),
    ):
        if skip_reason:
            print(f"\nVersion Service {label}: SKIPPED ({skip_reason})")
        else:
            print_section(f"Version Service {label}", errors)
    print_section("Version Service Endpoint (development)", vs_dev_endpoint_errors)
    print_section("Version Service Endpoint (production)", vs_prod_endpoint_errors)

    print_group("DOCKER HUB")
    print_section("Bundle Images (perconalab)", lab_errors, details=lab_details)
    print_section("Bundle Images (percona)", prod_errors, details=prod_details)
    if bundle_digest_skip:
        print(f"\nBundle Images (digests): SKIPPED ({bundle_digest_skip})")
    else:
        print_section("Bundle Images (digests)", bundle_digest_errors, details=bundle_digest_details)

    print_group("RESULT")
    if all_errors:
        print(f"RESULT: OUT OF SYNC ({len(all_errors)} issue(s) found)")
        return 1

    skipped = vs_nonprod_skip or vs_prod_skip
    suffix = " (with skipped checks, see above)" if skipped else ""
    print(f"RESULT: IN SYNC{suffix}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
