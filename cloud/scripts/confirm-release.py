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


def classify_crds(
    operator_crds: Dict[str, Any], helm_crds: Dict[str, Any]
) -> Tuple[List[str], List[str], List[str], List[str]]:
    missing = sorted(operator_crds.keys() - helm_crds.keys())
    extra = sorted(helm_crds.keys() - operator_crds.keys())
    identical = []
    different = []
    for name in sorted(operator_crds.keys() & helm_crds.keys()):
        if operator_crds[name] == helm_crds[name]:
            identical.append(name)
        else:
            different.append(name)
    return identical, missing, extra, different


def chart_crd_errors(
    chart_name: str, missing: List[str], extra: List[str], different: List[str]
) -> List[str]:
    errors = [
        f"CRD '{name}' is present in operator repo but missing from Helm chart '{chart_name}'"
        for name in missing
    ]
    errors.extend(
        f"CRD '{name}' is present in Helm chart '{chart_name}' but missing from operator repo"
        for name in extra
    )
    errors.extend(
        f"CRD '{name}' differs between operator repo and Helm chart '{chart_name}'"
        for name in different
    )
    return errors


def load_helm_crds(
    chart_dir: Path, chart_name: str
) -> Tuple[Optional[Dict[str, Any]], List[str]]:
    log("CRDs", f"helm chart: {chart_dir}")
    if not chart_dir.exists():
        log("CRDs", f"helm chart '{chart_name}' is missing")
        return None, [f"Missing Helm chart: {chart_dir}"]
    try:
        helm_crds = crds_by_name(load_chart_crds(chart_dir))
    except RuntimeError as exc:
        log("CRDs", f"failed to load CRDs from '{chart_name}': {exc}")
        return None, [f"Failed to render Helm chart '{chart_name}' for CRDs: {exc}"]
    if not helm_crds:
        log("CRDs", f"no CRDs found in helm chart '{chart_name}'")
        return None, [f"No CRDs found in Helm chart '{chart_name}'"]
    return helm_crds, []


def check_chart_crds(
    chart_dir: Path, chart_name: str, operator_crds: Dict[str, Any]
) -> List[str]:
    helm_crds, errors = load_helm_crds(chart_dir, chart_name)
    if helm_crds is None:
        return errors
    identical, missing, extra, different = classify_crds(operator_crds, helm_crds)
    log_list("CRDs", f"helm '{chart_name}' CRDs", sorted(helm_crds))
    log_list("CRDs", "identical", identical)
    log_list("CRDs", "only in operator", missing)
    log_list("CRDs", f"only in helm '{chart_name}'", extra)
    log_list("CRDs", "content differs", different)
    return chart_crd_errors(chart_name, missing, extra, different)


def check_crds(operator_path: Path, helm_dir: Path, abbrev: str) -> List[str]:
    log("CRDs", f"operator: {operator_path}")
    if not operator_path.exists():
        log("CRDs", "operator CRD file is missing")
        return [f"Missing CRD file in operator repo: {operator_path}"]

    operator_crds = crds_by_name(load_yaml_docs(operator_path))
    log_list("CRDs", "operator CRDs", sorted(operator_crds))
    chart_names = [f"{abbrev}-operator", *EXTRA_CRD_CHARTS.get(abbrev, [])]
    errors = []
    for chart_name in chart_names:
        errors.extend(
            check_chart_crds(helm_dir / "charts" / chart_name, chart_name, operator_crds)
        )
    return errors


def dict_image_ref(value: Any) -> Optional[str]:
    if not isinstance(value, dict):
        return None
    repository = value.get("repository")
    tag = value.get("tag")
    if repository and tag:
        return f"{repository}:{tag}"
    return None


def image_ref(key: str, value: Any) -> Optional[str]:
    if key != "image":
        return None
    if isinstance(value, str):
        return value or None
    return dict_image_ref(value)


def nested_path(prefix: str, key: str) -> str:
    return f"{prefix}.{key}" if prefix else key


def walk_images(tree: Any, prefix: str, found: Dict[str, str]) -> None:
    if isinstance(tree, list):
        for value in tree:
            walk_images(value, prefix, found)
        return
    if not isinstance(tree, dict):
        return
    for key, value in tree.items():
        path = nested_path(prefix, key)
        image = image_ref(key, value)
        if image:
            found[path] = image
            continue
        walk_images(value, path, found)


def collect_images_with_paths(tree: Any) -> Dict[str, str]:
    found: Dict[str, str] = {}
    walk_images(tree, "", found)
    return found


def collect_images(tree: Any) -> Set[str]:
    return set(collect_images_with_paths(tree).values())


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


def recommended_image(node: Dict[str, Any]) -> Optional[str]:
    if node.get("status") != "recommended":
        return None
    image_path = node.get("image_path") or node.get("imagePath")
    if not image_path:
        return None
    return normalize_image(image_path)


def collect_recommended_images(node: Any, found: Optional[Set[str]] = None) -> Set[str]:
    if found is None:
        found = set()
    if isinstance(node, dict):
        image = recommended_image(node)
        if image:
            found.add(image)
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


def parse_readme_row(line: str) -> Optional[Tuple[str, str]]:
    if not line.startswith("|") or "---" in line:
        return None
    cells = [cell.strip() for cell in line.strip("|").split("|")]
    if len(cells) < 3:
        return None
    parameter = re.sub(r"[`~]", "", cells[0]).strip()
    default = cells[-1].replace("`", "").strip()
    if not parameter or parameter.lower() == "parameter":
        return None
    return parameter, default


def parse_readme_table(path: Path) -> Dict[str, str]:
    rows = {}
    for raw_line in path.read_text().splitlines():
        row = parse_readme_row(raw_line.strip())
        if row:
            parameter, default = row
            rows[parameter] = default
    return rows


def readme_image_part(parameter: str) -> Optional[Tuple[str, str]]:
    if parameter.endswith(".image.repository") or parameter == "image.repository":
        return parameter[: -len(".repository")], "repository"
    if parameter.endswith(".image.tag") or parameter == "image.tag":
        return parameter[: -len(".tag")], "tag"
    return None


def is_image_parameter(parameter: str) -> bool:
    return parameter.endswith(".image") or parameter == "image"


def composed_readme_images(parts: Dict[str, Dict[str, str]]) -> Dict[str, str]:
    images = {}
    for base, values in parts.items():
        repository = values.get("repository")
        tag = values.get("tag")
        if repository and tag:
            images[base] = f"{repository}:{tag}"
    return images


def readme_images(rows: Dict[str, str]) -> Dict[str, str]:
    images = {}
    parts: Dict[str, Dict[str, str]] = {}
    for parameter, default in rows.items():
        part = readme_image_part(parameter)
        if part:
            base, field = part
            parts.setdefault(base, {})[field] = default
            continue
        if default and is_image_parameter(parameter):
            images[parameter] = default
    images.update(composed_readme_images(parts))
    return images


def compare_readme_images(
    values_images: Dict[str, str], documented: Dict[str, str]
) -> Tuple[List[str], List[str], List[str], List[str]]:
    errors = []
    matching = []
    missing = []
    mismatched = []
    for path, image in sorted(values_images.items()):
        documented_image = documented.get(path)
        if documented_image is None:
            missing.append(f"{path} = {image}")
            errors.append(
                f"values.yaml image '{image}' at '{path}' is not documented in README.md"
            )
        elif documented_image != image:
            mismatched.append(f"{path}: values.yaml={image} README.md={documented_image}")
            errors.append(
                f"values.yaml image at '{path}' is '{image}' but README.md documents "
                f"'{documented_image}'"
            )
        else:
            matching.append(f"{path} = {image}")
    return errors, matching, missing, mismatched


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
        values_images.update(collect_images_with_paths(doc))
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

    errors, matching, missing, mismatched = compare_readme_images(
        values_images, documented
    )
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


def resource_permissions(rule: Dict[str, Any]) -> Set[Permission]:
    verbs = rule.get("verbs") or []
    names = tuple(sorted(rule.get("resourceNames") or []))
    return {
        (api_group, resource, verb, names)
        for api_group in rule.get("apiGroups") or [""]
        for resource in rule.get("resources") or []
        for verb in verbs
    }


def non_resource_permissions(rule: Dict[str, Any]) -> Set[Permission]:
    verbs = rule.get("verbs") or []
    return {
        ("<non-resource>", url, verb, ())
        for url in rule.get("nonResourceURLs") or []
        for verb in verbs
    }


def normalize_permissions(rules: Any) -> Set[Permission]:
    permissions: Set[Permission] = set()
    for rule in rules or []:
        permissions |= resource_permissions(rule)
        permissions |= non_resource_permissions(rule)
    return permissions


def permission_lines(permissions: Set[Permission]) -> List[str]:
    lines = []
    for api_group, resource, verb, resource_names in sorted(permissions):
        group = api_group or '""'
        suffix = f" resourceNames={','.join(resource_names)}" if resource_names else ""
        lines.append(f"{group}/{resource}: {verb}{suffix}")
    return lines


def load_rbac_docs(
    kind: str,
    mode: str,
    operator_path: Path,
    chart_dir: Path,
    set_args: List[str],
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]], List[str]]:
    if not operator_path.exists():
        log("RBAC", f"{mode}: operator file is missing")
        return None, None, [f"Missing {mode} RBAC file in operator repo: {operator_path}"]
    operator_doc = first_document(load_yaml_docs(operator_path), kind)
    if operator_doc is None:
        log("RBAC", f"{mode}: no {kind} in {operator_path.name}")
        return None, None, [f"No {kind} found in operator repo's {operator_path.name}"]
    try:
        helm_doc = first_document(render_helm_template(chart_dir, set_args), kind)
    except RuntimeError as exc:
        log("RBAC", f"{mode}: helm template failed: {exc}")
        return None, None, [f"Failed to render Helm chart for {mode} RBAC: {exc}"]
    if helm_doc is None:
        log("RBAC", f"{mode}: helm chart did not render a {kind}")
        return None, None, [f"Helm chart did not render a {kind} in {mode} mode"]
    return operator_doc, helm_doc, []


def rbac_mismatch(
    mode: str,
    operator_path: Path,
    kind: str,
    operator_permissions: Set[Permission],
    helm_permissions: Set[Permission],
) -> Tuple[List[str], List[str]]:
    if operator_permissions == helm_permissions:
        log("RBAC", f"{mode}: identical")
        return [], []
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
    error = (
        f"RBAC ({mode}) differs: {missing} permission(s) missing from Helm and "
        f"{extra} permission(s) only in Helm"
    )
    diff = unified_diff(
        f"RBAC diff ({mode})",
        f"operator:{operator_path.name}",
        f"helm:rendered-{kind}",
        permission_lines(operator_permissions),
        permission_lines(helm_permissions),
    )
    return [error], [diff]


def check_rbac_mode(
    kind: str,
    mode: str,
    operator_path: Path,
    chart_dir: Path,
    set_args: List[str],
) -> Tuple[List[str], List[str]]:
    helm_args = ",".join(set_args) if set_args else "(defaults)"
    log("RBAC", f"{mode}: {operator_path} vs helm template {chart_dir} --set {helm_args}")
    operator_doc, helm_doc, errors = load_rbac_docs(
        kind, mode, operator_path, chart_dir, set_args
    )
    if errors:
        return errors, []
    operator_permissions = normalize_permissions(operator_doc.get("rules"))
    helm_permissions = normalize_permissions(helm_doc.get("rules"))
    log(
        "RBAC",
        f"{mode}: operator={len(operator_permissions)} permissions, "
        f"helm={len(helm_permissions)} permissions",
    )
    return rbac_mismatch(
        mode, operator_path, kind, operator_permissions, helm_permissions
    )


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
        mode_errors, mode_diffs = check_rbac_mode(
            kind, mode, operator_path, chart_dir, set_args
        )
        errors.extend(mode_errors)
        diffs.extend(mode_diffs)
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


def env_lines(container: Dict[str, Any]) -> List[str]:
    names = sorted(
        entry.get("name") for entry in container.get("env", []) if entry.get("name")
    )
    return [f"env: {name}" for name in names]


def port_lines(container: Dict[str, Any]) -> List[str]:
    ports = sorted(
        (entry.get("containerPort"), entry.get("protocol", "TCP"))
        for entry in container.get("ports", [])
    )
    return [f"port: {port}/{protocol}" for port, protocol in ports]


def probe_lines(container: Dict[str, Any]) -> List[str]:
    lines = []
    for probe_name in ("livenessProbe", "readinessProbe"):
        http_get = (container.get(probe_name) or {}).get("httpGet")
        if not http_get:
            continue
        lines.append(
            f"{probe_name}: {http_get.get('path')}:{http_get.get('port')}/"
            f"{http_get.get('scheme')}"
        )
    return lines


def deployment_lines(container: Dict[str, Any]) -> List[str]:
    return [
        f"name: {container.get('name')}",
        f"command: {container.get('command') or []}",
        *env_lines(container),
        *port_lines(container),
        *probe_lines(container),
    ]


def service_lines(service: Optional[Dict[str, Any]]) -> List[str]:
    if not service:
        return []
    ports = service.get("spec", {}).get("ports", [])
    return [
        f"service port: {port.get('port')} -> {port.get('targetPort')}"
        for port in sorted(ports, key=lambda item: (item.get("port"), str(item.get("targetPort"))))
    ]


def load_deployment_docs(
    mode: str, operator_path: Path, chart_dir: Path, set_args: List[str]
) -> Tuple[Optional[List[Any]], Optional[List[Any]], List[str]]:
    if not operator_path.exists():
        log("Deployment", f"{mode}: operator file is missing")
        return None, None, [f"Missing {mode} operator deployment: {operator_path}"]
    operator_docs = load_yaml_docs(operator_path)
    try:
        helm_docs = render_helm_template(chart_dir, set_args)
    except RuntimeError as exc:
        log("Deployment", f"{mode}: helm template failed: {exc}")
        return None, None, [f"Failed to render Helm chart for {mode} deployment: {exc}"]
    return operator_docs, helm_docs, []


def deployment_summaries(
    mode: str, operator_docs: List[Any], helm_docs: List[Any]
) -> Tuple[Optional[List[str]], Optional[List[str]], List[str]]:
    operator_deployment = first_document(operator_docs, "Deployment")
    helm_deployment = first_document(helm_docs, "Deployment")
    if operator_deployment is None or helm_deployment is None:
        log("Deployment", f"{mode}: Deployment is missing from operator or helm")
        return None, None, [
            f"Deployment ({mode}) is missing from operator repo or rendered Helm chart"
        ]
    operator_spec = operator_container(operator_deployment)
    helm_spec = operator_container(helm_deployment)
    if operator_spec is None or helm_spec is None:
        log("Deployment", f"{mode}: no operator container to compare")
        return None, None, [f"Deployment ({mode}) has no operator container to compare"]
    operator_summary = deployment_lines(operator_spec) + service_lines(
        first_document(operator_docs, "Service")
    )
    helm_summary = deployment_lines(helm_spec) + service_lines(
        first_document(helm_docs, "Service")
    )
    return operator_summary, helm_summary, []


def check_deployment_mode(
    mode: str, operator_path: Path, chart_dir: Path, set_args: List[str]
) -> Tuple[List[str], List[str]]:
    helm_args = ",".join(set_args) if set_args else "(defaults)"
    log(
        "Deployment",
        f"{mode}: {operator_path} vs helm template {chart_dir} --set {helm_args}",
    )
    operator_docs, helm_docs, errors = load_deployment_docs(
        mode, operator_path, chart_dir, set_args
    )
    if errors:
        return errors, []
    operator_summary, helm_summary, errors = deployment_summaries(
        mode, operator_docs, helm_docs
    )
    if errors:
        return errors, []
    log_list("Deployment", f"{mode} operator", operator_summary)
    log_list("Deployment", f"{mode} helm", helm_summary)
    if operator_summary == helm_summary:
        log("Deployment", f"{mode}: identical")
        return [], []
    log("Deployment", f"{mode}: differs")
    return (
        [f"Deployment ({mode}) differs between operator repo and rendered Helm chart"],
        [
            unified_diff(
                f"Deployment diff ({mode})",
                f"operator:{operator_path.name}",
                f"helm:rendered-deployment-{mode}",
                operator_summary,
                helm_summary,
            )
        ],
    )


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
        mode_errors, mode_diffs = check_deployment_mode(
            mode, operator_path, chart_dir, set_args
        )
        errors.extend(mode_errors)
        diffs.extend(mode_diffs)
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
