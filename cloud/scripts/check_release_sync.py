#!/usr/bin/env python3
"""Release Sync Checker - verifies an operator release branch is in sync with
the matching percona-helm-charts and percona-version-service branches.

Checks:
  1. CRDs: operator-repo/deploy/crd.yaml == helm-repo/charts/<abbrev>-operator/crds/crd.yaml
     (and any extra CRD-only charts, e.g. psmdb-operator-crds), ignoring schema
     "description" text since some charts intentionally strip it.
  2. Images: images used in operator-repo/deploy/cr.yaml match the images used in
     helm-repo/charts/<abbrev>-db/values.yaml, and both are marked "recommended" in
     the version-service repo's sources/operator.<version>.<abbrev>-operator.json
     and the live dev/prod version-service endpoints. Missing repo files or 404
     endpoints are skipped (not a failure) because they may not be available yet.
  3. README: images documented in helm-repo/charts/<abbrev>-db/README.md match the
     actual defaults in that chart's values.yaml
  4. Commented examples: best-effort check that, for each top-level spec section
     present in both files (e.g. "pxc", "backup"), the set of option names shown
     as commented-out examples (or set live) in cr.yaml roughly matches the set
     shown in values.yaml. Flat/name-based, not a full structural diff, so it can
     miss context - it's meant to surface obviously undocumented options, not to
     be a strict gate.
  5. RBAC: the rules granted by operator-repo/deploy/rbac.yaml (Role) and
     deploy/cw-rbac.yaml (ClusterRole) match what helm-repo/charts/<abbrev>-operator
     renders via `helm template` (namespaced and --set watchAllNamespaces=true
     respectively), since the chart's Role/ClusterRole templates are parameterized
     and can't be diffed as plain text.
  6. Deployment: the operator container in operator-repo/deploy/operator.yaml and
     deploy/cw-operator.yaml (command, env var names, ports, probes) and the
     webhook Service ports match what helm-repo/charts/<abbrev>-operator renders
     via `helm template`, for the same namespaced / cluster-wide modes as RBAC.
"""

import argparse
import difflib
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

import yaml

# Charts that ship CRDs separately from the main "<abbrev>-operator" chart and
# must also be checked against the operator repo's deploy/crd.yaml.
EXTRA_CRD_CHARTS = {
    "psmdb": ["psmdb-operator-crds"],
}

OPERATOR_PRODUCTS = {
    "pxc": "pxc-operator",
    "psmdb": "psmdb-operator",
    "ps": "ps-operator",
    "pg": "pg-operator",
}

VERSION_SERVICE_ENDPOINTS = {
    "dev": "https://check-dev.percona.com",
    "prod": "https://check.percona.com",
}

# Option names that legitimately only appear on one side (e.g. helm's structured
# image: {repository, tag} vs the operator's plain "image: repo:tag" string) and
# would otherwise show up as noise on every section that has an image field.
IGNORED_OPTION_KEYS = {"repository", "tag"}


def load_yaml_docs(path: Path) -> List[Any]:
    with open(path) as f:
        return [doc for doc in yaml.safe_load_all(f) if doc is not None]


def strip_descriptions(node: Any, parent_key: Optional[str] = None) -> Any:
    """Recursively drop OpenAPI schema 'description' text.

    A 'description' key is documentation text (safe to drop) everywhere except
    when its parent dict is a "properties" map, in which case "description" is
    itself a property *name* and must be kept (only its nested schema is
    recursed into).
    """
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


def crd_names(docs: List[Any]) -> Dict[str, Any]:
    names = {}
    for doc in docs:
        if isinstance(doc, dict) and doc.get("kind") == "CustomResourceDefinition":
            name = doc.get("metadata", {}).get("name", "<unknown>")
            names[name] = strip_descriptions(doc)
    return names


def load_crds_from_chart(chart_dir: Path) -> List[Any]:
    """Load CRD docs from a helm chart, either from crds/crd.yaml (single file)
    or from templates/*.yaml (one file per CRD, e.g. psmdb-operator-crds)."""
    crd_file = chart_dir / "crds" / "crd.yaml"
    if crd_file.exists():
        return load_yaml_docs(crd_file)

    docs: List[Any] = []
    templates_dir = chart_dir / "templates"
    if templates_dir.exists():
        for yaml_file in sorted(templates_dir.glob("*.yaml")):
            docs.extend(load_yaml_docs(yaml_file))
    return docs


def check_crds(operator_crd_path: Path, helm_dir: Path, abbrev: str) -> List[str]:
    errors = []

    if not operator_crd_path.exists():
        return [f"Missing CRD file in operator repo: {operator_crd_path}"]

    operator_crds = crd_names(load_yaml_docs(operator_crd_path))
    chart_names = [f"{abbrev}-operator"] + EXTRA_CRD_CHARTS.get(abbrev, [])

    for chart_name in chart_names:
        chart_dir = helm_dir / "charts" / chart_name
        docs = load_crds_from_chart(chart_dir)
        if not docs:
            errors.append(f"No CRDs found in helm chart '{chart_name}' ({chart_dir})")
            continue

        helm_crds = crd_names(docs)

        only_operator = sorted(set(operator_crds) - set(helm_crds))
        only_helm = sorted(set(helm_crds) - set(operator_crds))

        for name in only_operator:
            errors.append(
                f"CRD '{name}' present in operator repo but missing in helm chart '{chart_name}'"
            )
        for name in only_helm:
            errors.append(
                f"CRD '{name}' present in helm chart '{chart_name}' but missing in operator repo"
            )

        for name in sorted(set(operator_crds) & set(helm_crds)):
            if operator_crds[name] != helm_crds[name]:
                errors.append(
                    f"CRD '{name}' differs between operator repo and helm chart '{chart_name}'"
                )

    return errors


def collect_images(tree: Any, found: Set[str] = None) -> Set[str]:
    """Recursively collect every 'image' key's value as a normalized repo:tag string."""
    if found is None:
        found = set()

    if isinstance(tree, dict):
        for key, value in tree.items():
            if key == "image" and value is not None:
                if isinstance(value, str):
                    found.add(value)
                elif isinstance(value, dict) and "repository" in value and "tag" in value:
                    found.add(f"{value['repository']}:{value['tag']}")
            else:
                collect_images(value, found)
    elif isinstance(tree, list):
        for item in tree:
            collect_images(item, found)

    return found


def collect_images_with_paths(
    tree: Any, prefix: str = "", found: Dict[str, str] = None
) -> Dict[str, str]:
    """Like collect_images, but keyed by the dotted path to the 'image' field so
    values.yaml entries can be matched to their documented README row."""
    if found is None:
        found = {}

    if isinstance(tree, dict):
        for key, value in tree.items():
            path = f"{prefix}.{key}" if prefix else key
            if key == "image" and value is not None:
                if isinstance(value, str):
                    if value:
                        found[path] = value
                elif isinstance(value, dict) and value.get("repository") and value.get("tag"):
                    found[path] = f"{value['repository']}:{value['tag']}"
            else:
                collect_images_with_paths(value, path, found)
    elif isinstance(tree, list):
        for item in tree:
            collect_images_with_paths(item, prefix, found)

    return found


def check_images(cr_path: Path, values_path: Path) -> (List[str], Set[str]):
    """Compare images between cr.yaml and values.yaml. Independent of the
    version-service repo so it can still run when that branch doesn't exist."""
    if not cr_path.exists():
        return [f"Missing cr.yaml in operator repo: {cr_path}"], set()
    if not values_path.exists():
        return [f"Missing values.yaml in helm-charts repo: {values_path}"], set()

    errors = []
    images_cr = collect_images(load_yaml_docs(cr_path))
    images_helm = collect_images(load_yaml_docs(values_path))

    only_cr = sorted(images_cr - images_helm)
    only_helm = sorted(images_helm - images_cr)

    for image in only_cr:
        errors.append(f"Image '{image}' used in cr.yaml but not found in helm-charts values.yaml")
    for image in only_helm:
        errors.append(f"Image '{image}' used in helm-charts values.yaml but not found in cr.yaml")

    return errors, images_cr | images_helm


TOP_KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_-]*(?=:(\s|$))")


def line_true_indent(line: str) -> Optional[int]:
    """Best-effort YAML indentation of a line, resolving commented lines whose
    '#' may sit at the true column (indentation before the '#') or at column 0
    with the real indentation encoded as extra spaces after '#' - both styles
    are used interchangeably across these hand-maintained files. Since real
    YAML indentation is always an even number of spaces, an odd raw total is
    assumed to include one decorative "# " space and is corrected for that.
    """
    stripped = line.lstrip(" ")
    if not stripped:
        return None
    leading = len(line) - len(stripped)
    if stripped.startswith("#"):
        after = stripped[1:]
        after_spaces = len(after) - len(after.lstrip(" "))
        candidate = leading + after_spaces
        return candidate - 1 if candidate % 2 else candidate
    return leading


def line_key(line: str) -> Optional[str]:
    """The YAML mapping key a line defines, whether live or commented-out, or
    None if the line isn't a "key:" line (e.g. prose comment, list item value)."""
    stripped = line.strip()
    if not stripped:
        return None
    content = stripped[1:].strip() if stripped.startswith("#") else stripped
    if content.startswith("- "):
        content = content[2:].strip()
    elif content == "-":
        return None
    match = TOP_KEY_RE.match(content)
    return match.group(0) if match else None


def line_is_list_item(line: str) -> bool:
    stripped = line.strip()
    if stripped.startswith("#"):
        stripped = stripped[1:].strip()
    return stripped.startswith("- ") or stripped == "-"


def find_top_level_blocks(lines: List[str], start: int, end: int, indent: int) -> Dict[str, List[str]]:
    """Split lines[start:end] into blocks keyed by each key (live or
    commented-out) found at exactly `indent` true spaces. A block runs from its
    header line up to (not including) the next such key. List items are never
    treated as headers, even if their computed indent happens to match."""
    headers: List[Tuple[int, str]] = []
    for i in range(start, end):
        line = lines[i]
        if line_is_list_item(line):
            continue
        key = line_key(line)
        if key is None or line_true_indent(line) != indent:
            continue
        headers.append((i, key))

    blocks: Dict[str, List[str]] = {}
    for i, (line_idx, key) in enumerate(headers):
        block_end = headers[i + 1][0] if i + 1 < len(headers) else end
        blocks[key] = lines[line_idx:block_end]
    return blocks


def extract_key_bag(block_lines: List[str]) -> Set[str]:
    """Collect every YAML mapping key name appearing in a block, whether live or
    commented-out, ignoring nesting depth (this is a flat "which option names
    are mentioned here" check, not a structural diff)."""
    return {key for line in block_lines if (key := line_key(line))}


def check_commented_examples(cr_path: Path, values_path: Path) -> List[str]:
    """Best-effort check that documented options (live defaults or commented-out
    examples) roughly match between cr.yaml's spec.<section> and values.yaml's
    top-level <section>, for sections present in both files."""
    if not cr_path.exists() or not values_path.exists():
        return []

    cr_lines = cr_path.read_text().splitlines()
    values_lines = values_path.read_text().splitlines()

    spec_idx = next((i for i, line in enumerate(cr_lines) if re.match(r"^spec:\s*$", line)), None)
    if spec_idx is None:
        return []

    child_indent = None
    for line in cr_lines[spec_idx + 1 :]:
        if line_key(line) is not None and not line_is_list_item(line):
            child_indent = line_true_indent(line)
            break
    if not child_indent:
        return []

    cr_blocks = find_top_level_blocks(cr_lines, spec_idx + 1, len(cr_lines), child_indent)
    values_blocks = find_top_level_blocks(values_lines, 0, len(values_lines), 0)

    errors = []
    for section in sorted(set(cr_blocks) & set(values_blocks)):
        cr_bag = extract_key_bag(cr_blocks[section]) - IGNORED_OPTION_KEYS
        values_bag = extract_key_bag(values_blocks[section]) - IGNORED_OPTION_KEYS

        for option in sorted(cr_bag - values_bag):
            errors.append(
                f"Option '{section}.{option}' is documented in operator repo's {cr_path.name} "
                f"but not found (live or commented) under '{section}' in helm chart's {values_path.name}"
            )
        for option in sorted(values_bag - cr_bag):
            errors.append(
                f"Option '{section}.{option}' is documented in helm chart's {values_path.name} "
                f"but not found (live or commented) under '{section}' in operator repo's {cr_path.name}"
            )

    return errors


def recommended_images(vs_data: Dict[str, Any]) -> Set[str]:
    images: Set[str] = set()
    for version_entry in vs_data.get("versions", []):
        for _category, entries in version_entry.get("matrix", {}).items():
            for _ver, entry in entries.items():
                if entry.get("status") == "recommended":
                    image_path = entry.get("image_path") or entry.get("imagePath")
                    if image_path:
                        images.add(image_path)
    return images


def check_recommended_images(images: Set[str], recommended: Set[str], source: str) -> List[str]:
    errors = []
    for image in sorted(images):
        if image not in recommended:
            errors.append(f"Image '{image}' is not marked 'recommended' in {source}")
    return errors


def check_vs_recommended(images: Set[str], vs_json_path: Path) -> Tuple[List[str], Optional[str]]:
    """Check that every image is marked 'recommended' in the version-service JSON.

    Returns (errors, skip_reason). skip_reason is set (and errors empty) when the
    version-service branch/file isn't available yet - that's not a sync failure,
    just something we can't verify yet.
    """
    if not vs_json_path.exists():
        return [], f"Version-service file not found ({vs_json_path}) - branch may not exist yet, skipping this check"

    with open(vs_json_path) as f:
        vs_data = json.load(f)

    return check_recommended_images(images, recommended_images(vs_data), "the version-service JSON"), None


def version_service_url(base_url: str, abbrev: str, version: str) -> str:
    product = OPERATOR_PRODUCTS.get(abbrev, f"{abbrev}-operator")
    return f"{base_url}/versions/v1/{product}/{version}/recommended"


def check_vs_endpoint_recommended(
    images: Set[str], base_url: str, abbrev: str, version: str
) -> Tuple[List[str], Optional[str], str]:
    url = version_service_url(base_url, abbrev, version)
    request = urllib.request.Request(url, headers={"User-Agent": "release-sync-check/1.0"})

    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            vs_data = json.load(response)
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return [], f"Version-service endpoint not found ({url}) - release may not be published yet, skipping this check", url
        return [f"Failed to fetch version-service endpoint ({url}): HTTP {exc.code}"], None, url
    except (urllib.error.URLError, TimeoutError) as exc:
        return [f"Failed to fetch version-service endpoint ({url}): {exc}"], None, url
    except json.JSONDecodeError as exc:
        return [f"Version-service endpoint returned invalid JSON ({url}): {exc}"], None, url

    return check_recommended_images(images, recommended_images(vs_data), f"the live version-service endpoint ({url})"), None, url


def parse_readme_table(readme_path: Path) -> Dict[str, str]:
    """Parse the 'Parameter | Description | Default' table into {parameter: default}."""
    rows: Dict[str, str] = {}
    for line in readme_path.read_text().splitlines():
        line = line.strip()
        if not line.startswith("|") or "---" in line:
            continue

        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) < 3:
            continue

        param = re.sub(r"[`~]", "", cells[0]).strip()
        default = re.sub(r"[`]", "", cells[-1]).strip()

        if not param or param.lower() == "parameter":
            continue

        rows[param] = default
    return rows


def images_from_readme_rows(rows: Dict[str, str]) -> Dict[str, str]:
    images: Dict[str, str] = {}
    pending: Dict[str, Dict[str, str]] = {}

    for param, default in rows.items():
        if param.endswith(".image.repository") or param == "image.repository":
            base = param[: -len(".repository")]
            pending.setdefault(base, {})["repository"] = default
        elif param.endswith(".image.tag") or param == "image.tag":
            base = param[: -len(".tag")]
            pending.setdefault(base, {})["tag"] = default
        elif param.endswith(".image") or param == "image":
            if default:
                images[param] = default

    for base, parts in pending.items():
        repository = parts.get("repository", "")
        tag = parts.get("tag", "")
        if repository and tag:
            images[base] = f"{repository}:{tag}"

    return images


def check_readme_images(values_path: Path, readme_path: Path) -> List[str]:
    errors = []

    if not values_path.exists():
        return [f"Missing values.yaml in helm-charts repo: {values_path}"]
    if not readme_path.exists():
        return [f"Missing README.md in helm-charts repo: {readme_path}"]

    values_images: Dict[str, str] = {}
    for doc in load_yaml_docs(values_path):
        collect_images_with_paths(doc, "", values_images)

    readme_images = images_from_readme_rows(parse_readme_table(readme_path))

    for path, image in sorted(values_images.items()):
        if path not in readme_images:
            errors.append(f"values.yaml image '{image}' at '{path}' is not documented in README.md")
        elif readme_images[path] != image:
            errors.append(
                f"values.yaml image at '{path}' is '{image}' but README.md documents '{readme_images[path]}'"
            )

    return errors


def render_helm_template(chart_dir: Path, set_args: Optional[List[str]] = None) -> List[Any]:
    """Render a helm chart's templates and parse the resulting manifests."""
    cmd = ["helm", "template", "release-name", str(chart_dir)]
    for arg in set_args or []:
        cmd += ["--set", arg]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "helm template failed")

    return [doc for doc in yaml.safe_load_all(result.stdout) if doc is not None]


def find_doc_by_kind(docs: List[Any], kind: str) -> Optional[Dict[str, Any]]:
    for doc in docs:
        if isinstance(doc, dict) and doc.get("kind") == kind:
            return doc
    return None


def format_diff(header: str, from_label: str, to_label: str, from_lines: List[str], to_lines: List[str]) -> str:
    diff_lines = list(
        difflib.unified_diff(from_lines, to_lines, fromfile=from_label, tofile=to_label, lineterm="")
    )
    body = "\n".join(f"    {line}" for line in diff_lines)
    return f"  --- {header} ---\n{body}"


def flatten_rules(rules: Optional[List[Dict[str, Any]]]) -> Set[Tuple[str, str, str]]:
    """Expand RBAC rules into a set of (apiGroup, resource, verb) triples so
    rules can be compared regardless of how they're grouped/ordered."""
    flat: Set[Tuple[str, str, str]] = set()
    for rule in rules or []:
        api_groups = rule.get("apiGroups") or [""]
        resources = rule.get("resources") or []
        verbs = rule.get("verbs") or []
        for api_group in api_groups:
            for resource in resources:
                for verb in verbs:
                    flat.add((api_group, resource, verb))
    return flat


def rule_lines(rules: Set[Tuple[str, str, str]]) -> List[str]:
    lines = []
    for api_group, resource, verb in sorted(rules):
        group_label = api_group or '""'
        lines.append(f"{group_label}/{resource}: {verb}")
    return lines


def check_rbac(operator_dir: Path, helm_dir: Path, abbrev: str) -> Tuple[List[str], List[str]]:
    """Compare RBAC rules between the operator repo and the rendered helm chart.

    The chart's role.yaml is templated (kind/name/namespace depend on
    .Values.watchNamespace / .Values.watchAllNamespaces), so it must be
    rendered with `helm template` rather than diffed as plain text.
    """
    errors: List[str] = []
    diffs: List[str] = []
    chart_dir = helm_dir / "charts" / f"{abbrev}-operator"

    # (kind, label, operator file, extra `helm template --set` args to get that kind)
    checks = [
        ("Role", "namespaced", operator_dir / "deploy" / "rbac.yaml", []),
        ("ClusterRole", "cluster-wide", operator_dir / "deploy" / "cw-rbac.yaml", ["watchAllNamespaces=true"]),
    ]

    for kind, label, operator_rbac_path, set_args in checks:
        if not operator_rbac_path.exists():
            continue

        operator_doc = find_doc_by_kind(load_yaml_docs(operator_rbac_path), kind)
        if operator_doc is None:
            errors.append(f"No '{kind}' found in operator repo's {operator_rbac_path.name}")
            continue

        try:
            helm_docs = render_helm_template(chart_dir, set_args)
        except RuntimeError as exc:
            errors.append(f"Failed to render helm chart '{chart_dir.name}' for {label} RBAC: {exc}")
            continue

        helm_doc = find_doc_by_kind(helm_docs, kind)
        if helm_doc is None:
            errors.append(f"Helm chart '{chart_dir.name}' did not render a '{kind}' ({label} mode)")
            continue

        operator_rules = flatten_rules(operator_doc.get("rules"))
        helm_rules = flatten_rules(helm_doc.get("rules"))

        label_errors: List[str] = []
        for api_group, resource, verb in sorted(operator_rules - helm_rules):
            group_label = api_group or '""'
            label_errors.append(
                f"RBAC ({label}): operator repo grants '{verb}' on '{group_label}/{resource}' "
                f"but helm chart's rendered {kind} does not"
            )
        for api_group, resource, verb in sorted(helm_rules - operator_rules):
            group_label = api_group or '""'
            label_errors.append(
                f"RBAC ({label}): helm chart's rendered {kind} grants '{verb}' on '{group_label}/{resource}' "
                f"but operator repo does not"
            )

        errors.extend(label_errors)

        if label_errors:
            diffs.append(
                format_diff(
                    f"RBAC diff ({label})",
                    f"operator:{operator_rbac_path.name}",
                    f"helm:rendered-{kind}",
                    rule_lines(operator_rules),
                    rule_lines(helm_rules),
                )
            )

    return errors, diffs


def get_container(deploy_doc: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not deploy_doc:
        return None
    containers = deploy_doc.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
    return containers[0] if containers else None


def summarize_service_ports(service_doc: Dict[str, Any]) -> List[Tuple[Any, Any]]:
    return sorted(
        (p.get("port"), p.get("targetPort")) for p in service_doc.get("spec", {}).get("ports", [])
    )


def compare_containers(
    label: str, operator_container: Dict[str, Any], helm_container: Dict[str, Any]
) -> List[str]:
    """Compare the operator's container spec against the helm-rendered one.

    Only compares structural/behavioral fields (env var *names*, ports, probes,
    command) - not literal values like image tags or resource limits, which are
    expected to legitimately differ between the two sources.
    """
    errors = []

    if operator_container.get("name") != helm_container.get("name"):
        errors.append(
            f"Deployment ({label}): container name '{operator_container.get('name')}' in operator repo "
            f"differs from helm chart's rendered '{helm_container.get('name')}'"
        )

    if (operator_container.get("command") or []) != (helm_container.get("command") or []):
        errors.append(
            f"Deployment ({label}): container command {operator_container.get('command')} in operator repo "
            f"differs from helm chart's rendered {helm_container.get('command')}"
        )

    operator_envs = {e.get("name") for e in operator_container.get("env", []) if e.get("name")}
    helm_envs = {e.get("name") for e in helm_container.get("env", []) if e.get("name")}

    for name in sorted(operator_envs - helm_envs):
        errors.append(
            f"Deployment ({label}): env var '{name}' set in operator repo's deployment "
            f"but missing from helm chart's rendered deployment"
        )
    for name in sorted(helm_envs - operator_envs):
        errors.append(
            f"Deployment ({label}): env var '{name}' set in helm chart's rendered deployment "
            f"but missing from operator repo's deployment"
        )

    def port_set(container: Dict[str, Any]) -> Set[Tuple[Any, str]]:
        return {(p.get("containerPort"), p.get("protocol", "TCP")) for p in container.get("ports", [])}

    operator_ports = port_set(operator_container)
    helm_ports = port_set(helm_container)

    for port in sorted(operator_ports - helm_ports, key=str):
        errors.append(
            f"Deployment ({label}): containerPort {port} exposed in operator repo but not in "
            f"helm chart's rendered deployment"
        )
    for port in sorted(helm_ports - operator_ports, key=str):
        errors.append(
            f"Deployment ({label}): containerPort {port} exposed in helm chart's rendered deployment "
            f"but not in operator repo"
        )

    for probe_name in ("livenessProbe", "readinessProbe"):
        operator_http = (operator_container.get(probe_name) or {}).get("httpGet") or {}
        helm_http = (helm_container.get(probe_name) or {}).get("httpGet") or {}
        operator_key = (operator_http.get("path"), operator_http.get("port"), operator_http.get("scheme")) if operator_http else None
        helm_key = (helm_http.get("path"), helm_http.get("port"), helm_http.get("scheme")) if helm_http else None
        if operator_key != helm_key:
            errors.append(
                f"Deployment ({label}): {probe_name} in operator repo ({operator_key}) differs from "
                f"helm chart's rendered deployment ({helm_key})"
            )

    return errors


def container_lines(container: Dict[str, Any]) -> List[str]:
    lines = [
        f"name: {container.get('name')}",
        f"command: {container.get('command')}",
    ]
    env_names = sorted({e.get("name") for e in container.get("env", []) if e.get("name")})
    for name in env_names:
        lines.append(f"env: {name}")

    ports = sorted(
        {(p.get("containerPort"), p.get("protocol", "TCP")) for p in container.get("ports", [])}, key=str
    )
    for port, protocol in ports:
        lines.append(f"port: {port}/{protocol}")

    for probe_name in ("livenessProbe", "readinessProbe"):
        http = (container.get(probe_name) or {}).get("httpGet") or {}
        if http:
            lines.append(f"{probe_name}: {http.get('path')}:{http.get('port')}/{http.get('scheme')}")

    return lines


def service_lines(doc: Optional[Dict[str, Any]]) -> List[str]:
    if not doc:
        return []
    ports = sorted(doc.get("spec", {}).get("ports", []), key=lambda p: (p.get("port"), str(p.get("targetPort"))))
    return [f"service port: {p.get('port')} -> {p.get('targetPort')}" for p in ports]


def labels_with_diffs(diffs: List[str], header_prefix: str) -> Set[str]:
    labels: Set[str] = set()
    pattern = re.compile(rf"{re.escape(header_prefix)} \(([^)]+)\)")
    for diff in diffs:
        match = pattern.search(diff)
        if match:
            labels.add(match.group(1))
    return labels


def print_errors_without_diff(errors: List[str], section: str, diff_labels: Set[str]) -> None:
    for err in errors:
        if any(err.startswith(f"{section} ({label}):") for label in diff_labels):
            continue
        print(f"  - {err}")


def check_deployment(operator_dir: Path, helm_dir: Path, abbrev: str) -> Tuple[List[str], List[str]]:
    """Compare the operator's Deployment (and webhook Service) against what the
    helm chart renders, since the chart's deployment.yaml is templated."""
    errors: List[str] = []
    diffs: List[str] = []
    chart_dir = helm_dir / "charts" / f"{abbrev}-operator"

    checks = [
        ("namespaced", operator_dir / "deploy" / "operator.yaml", []),
        ("cluster-wide", operator_dir / "deploy" / "cw-operator.yaml", ["watchAllNamespaces=true"]),
    ]

    for label, operator_path, set_args in checks:
        if not operator_path.exists():
            continue

        operator_docs = load_yaml_docs(operator_path)
        operator_deploy = find_doc_by_kind(operator_docs, "Deployment")
        if operator_deploy is None:
            errors.append(f"No 'Deployment' found in operator repo's {operator_path.name}")
            continue

        try:
            helm_docs = render_helm_template(chart_dir, set_args)
        except RuntimeError as exc:
            errors.append(f"Failed to render helm chart '{chart_dir.name}' for {label} deployment: {exc}")
            continue

        helm_deploy = find_doc_by_kind(helm_docs, "Deployment")
        if helm_deploy is None:
            errors.append(f"Helm chart '{chart_dir.name}' did not render a 'Deployment' ({label} mode)")
            continue

        operator_container = get_container(operator_deploy)
        helm_container = get_container(helm_deploy)

        label_errors: List[str] = []
        if operator_container is None or helm_container is None:
            label_errors.append(f"Deployment ({label}): could not find a container to compare")
        else:
            label_errors.extend(compare_containers(label, operator_container, helm_container))

        operator_svc = find_doc_by_kind(operator_docs, "Service")
        helm_svc = find_doc_by_kind(helm_docs, "Service")

        if operator_svc and helm_svc:
            operator_ports = summarize_service_ports(operator_svc)
            helm_ports = summarize_service_ports(helm_svc)
            if operator_ports != helm_ports:
                label_errors.append(
                    f"Deployment ({label}): operator repo's webhook Service ports {operator_ports} differ "
                    f"from helm chart's rendered Service ports {helm_ports}"
                )
        elif operator_svc and not helm_svc:
            label_errors.append(
                f"Deployment ({label}): operator repo defines a Service in {operator_path.name} "
                f"but helm chart did not render one"
            )
        elif helm_svc and not operator_svc:
            label_errors.append(
                f"Deployment ({label}): helm chart rendered a Service but operator repo's "
                f"{operator_path.name} does not define one"
            )

        errors.extend(label_errors)

        if label_errors and operator_container is not None and helm_container is not None:
            operator_lines = container_lines(operator_container) + service_lines(operator_svc)
            helm_lines = container_lines(helm_container) + service_lines(helm_svc)
            diffs.append(
                format_diff(
                    f"Deployment diff ({label})",
                    f"operator:{operator_path.name}",
                    f"helm:rendered-deployment-{label}",
                    operator_lines,
                    helm_lines,
                )
            )

    return errors, diffs


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check that an operator release is in sync with helm-charts and version-service repos"
    )
    parser.add_argument("abbrev", help="Operator abbreviation (e.g. pxc, psmdb, ps, pg)")
    parser.add_argument("version", help="Version being released (e.g. 1.20.0)")
    parser.add_argument("operator_repo_dir", help="Path to the checked out operator repo")
    parser.add_argument("helm_repo_dir", help="Path to the checked out percona-helm-charts repo")
    parser.add_argument(
        "vs_nonprod_repo_dir",
        help="Path to the checked out percona-version-service repo (non-prod release branch)",
    )
    parser.add_argument(
        "vs_prod_repo_dir",
        help="Path to the checked out percona-version-service repo (prod release branch)",
    )
    args = parser.parse_args()

    operator_dir = Path(args.operator_repo_dir)
    helm_dir = Path(args.helm_repo_dir)
    vs_nonprod_dir = Path(args.vs_nonprod_repo_dir)
    vs_prod_dir = Path(args.vs_prod_repo_dir)

    crd_errors = check_crds(
        operator_dir / "deploy" / "crd.yaml",
        helm_dir,
        args.abbrev,
    )

    image_errors, all_images = check_images(
        operator_dir / "deploy" / "cr.yaml",
        helm_dir / "charts" / f"{args.abbrev}-db" / "values.yaml",
    )

    vs_json_name = f"operator.{args.version}.{args.abbrev}-operator.json"
    vs_nonprod_errors, vs_nonprod_skip = check_vs_recommended(
        all_images, vs_nonprod_dir / "sources" / vs_json_name
    )
    vs_prod_errors, vs_prod_skip = check_vs_recommended(
        all_images, vs_prod_dir / "sources" / vs_json_name
    )
    vs_dev_live_errors, vs_dev_live_skip, _vs_dev_live_url = check_vs_endpoint_recommended(
        all_images,
        VERSION_SERVICE_ENDPOINTS["dev"],
        args.abbrev,
        args.version,
    )
    vs_prod_live_errors, vs_prod_live_skip, _vs_prod_live_url = check_vs_endpoint_recommended(
        all_images,
        VERSION_SERVICE_ENDPOINTS["prod"],
        args.abbrev,
        args.version,
    )

    readme_errors = check_readme_images(
        helm_dir / "charts" / f"{args.abbrev}-db" / "values.yaml",
        helm_dir / "charts" / f"{args.abbrev}-db" / "README.md",
    )

    commented_examples_errors = check_commented_examples(
        operator_dir / "deploy" / "cr.yaml",
        helm_dir / "charts" / f"{args.abbrev}-db" / "values.yaml",
    )

    rbac_errors, rbac_diffs = check_rbac(operator_dir, helm_dir, args.abbrev)
    deployment_errors, deployment_diffs = check_deployment(operator_dir, helm_dir, args.abbrev)

    all_errors = (
        crd_errors + image_errors
        + vs_nonprod_errors + vs_prod_errors + vs_dev_live_errors + vs_prod_live_errors
        + readme_errors + commented_examples_errors + rbac_errors + deployment_errors
    )

    print("=" * 80)
    print(f"Release sync check: {args.abbrev} {args.version}")
    print("=" * 80)

    print(f"\nCRDs: {'OK' if not crd_errors else 'MISMATCH'}")
    for err in crd_errors:
        print(f"  - {err}")

    print(f"\nImages (cr.yaml vs values.yaml): {'OK' if not image_errors else 'MISMATCH'}")
    for err in image_errors:
        print(f"  - {err}")

    print(f"\nREADME: {'OK' if not readme_errors else 'MISMATCH'}")
    for err in readme_errors:
        print(f"  - {err}")

    print(f"\nCommented examples (cr.yaml vs values.yaml): {'OK' if not commented_examples_errors else 'MISMATCH'}")
    for err in commented_examples_errors:
        print(f"  - {err}")

    for label, errors, skip_reason in (
        ("non-prod repo", vs_nonprod_errors, vs_nonprod_skip),
        ("prod repo", vs_prod_errors, vs_prod_skip),
        ("dev live", vs_dev_live_errors, vs_dev_live_skip),
        ("prod live", vs_prod_live_errors, vs_prod_live_skip),
    ):
        if skip_reason:
            print(f"\nVersion Service ({label}): SKIPPED ({skip_reason})")
        else:
            print(f"\nVersion Service ({label}): {'OK' if not errors else 'MISMATCH'}")
            for err in errors:
                print(f"  - {err}")

    print(f"\nRBAC: {'OK' if not rbac_errors else 'MISMATCH'}")
    print_errors_without_diff(rbac_errors, "RBAC", labels_with_diffs(rbac_diffs, "RBAC diff"))
    for diff in rbac_diffs:
        print(diff)

    print(f"\nDeployment: {'OK' if not deployment_errors else 'MISMATCH'}")
    print_errors_without_diff(
        deployment_errors,
        "Deployment",
        labels_with_diffs(deployment_diffs, "Deployment diff"),
    )
    for diff in deployment_diffs:
        print(diff)

    print()
    if all_errors:
        print(f"RESULT: OUT OF SYNC ({len(all_errors)} issue(s) found)")
        return 1

    any_skipped = vs_nonprod_skip or vs_prod_skip or vs_dev_live_skip or vs_prod_live_skip
    print("RESULT: IN SYNC" + (" (with skipped checks, see above)" if any_skipped else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
