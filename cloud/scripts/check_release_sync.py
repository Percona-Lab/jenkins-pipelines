#!/usr/bin/env python3
"""Release Sync Checker - verifies an operator release branch is in sync with
the matching percona-helm-charts and percona-version-service branches.

Checks:
  1. CRDs: operator-repo/deploy/crd.yaml == helm-repo/charts/<abbrev>-operator/crds/crd.yaml
     (and any extra CRD-only charts, e.g. psmdb-operator-crds), ignoring schema
     "description" text since some charts intentionally strip it.
  2. Images: images used in operator-repo/deploy/cr.yaml match the images used in
     helm-repo/charts/<abbrev>-db/values.yaml, and both are marked "recommended" in
     the version-service repo's sources/operator.<version>.<abbrev>-operator.json,
     checked against both the non-prod (release-<abbrev>-<version>) and prod
     (release-<abbrev>-<version>-prod) branches. Each is skipped (not a failure)
     if that branch/file isn't available yet - the other checks still run.
  3. README: images documented in helm-repo/charts/<abbrev>-db/README.md match the
     actual defaults in that chart's values.yaml
  4. RBAC: the rules granted by operator-repo/deploy/rbac.yaml (Role) and
     deploy/cw-rbac.yaml (ClusterRole) match what helm-repo/charts/<abbrev>-operator
     renders via `helm template` (namespaced and --set watchAllNamespaces=true
     respectively), since the chart's Role/ClusterRole templates are parameterized
     and can't be diffed as plain text.
  5. Deployment: the operator container in operator-repo/deploy/operator.yaml and
     deploy/cw-operator.yaml (command, env var names, ports, probes) and the
     webhook Service ports match what helm-repo/charts/<abbrev>-operator renders
     via `helm template`, for the same namespaced / cluster-wide modes as RBAC.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

import yaml

# Charts that ship CRDs separately from the main "<abbrev>-operator" chart and
# must also be checked against the operator repo's deploy/crd.yaml.
EXTRA_CRD_CHARTS = {
    "psmdb": ["psmdb-operator-crds"],
}


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


def check_vs_recommended(images: Set[str], vs_json_path: Path) -> (List[str], Optional[str]):
    """Check that every image is marked 'recommended' in the version-service JSON.

    Returns (errors, skip_reason). skip_reason is set (and errors empty) when the
    version-service branch/file isn't available yet - that's not a sync failure,
    just something we can't verify yet.
    """
    if not vs_json_path.exists():
        return [], f"Version-service file not found ({vs_json_path}) - branch may not exist yet, skipping this check"

    with open(vs_json_path) as f:
        vs_data = json.load(f)

    recommended: Set[str] = set()
    for version_entry in vs_data.get("versions", []):
        for _category, entries in version_entry.get("matrix", {}).items():
            for _ver, entry in entries.items():
                if entry.get("status") == "recommended" and entry.get("image_path"):
                    recommended.add(entry["image_path"])

    errors = []
    for image in sorted(images):
        if image not in recommended:
            errors.append(
                f"Image '{image}' is not marked 'recommended' in the version-service JSON"
            )

    return errors, None


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


def check_rbac(operator_dir: Path, helm_dir: Path, abbrev: str) -> List[str]:
    """Compare RBAC rules between the operator repo and the rendered helm chart.

    The chart's role.yaml is templated (kind/name/namespace depend on
    .Values.watchNamespace / .Values.watchAllNamespaces), so it must be
    rendered with `helm template` rather than diffed as plain text.
    """
    errors: List[str] = []
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

        for api_group, resource, verb in sorted(operator_rules - helm_rules):
            group_label = api_group or '""'
            errors.append(
                f"RBAC ({label}): operator repo grants '{verb}' on '{group_label}/{resource}' "
                f"but helm chart's rendered {kind} does not"
            )
        for api_group, resource, verb in sorted(helm_rules - operator_rules):
            group_label = api_group or '""'
            errors.append(
                f"RBAC ({label}): helm chart's rendered {kind} grants '{verb}' on '{group_label}/{resource}' "
                f"but operator repo does not"
            )

    return errors


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


def check_deployment(operator_dir: Path, helm_dir: Path, abbrev: str) -> List[str]:
    """Compare the operator's Deployment (and webhook Service) against what the
    helm chart renders, since the chart's deployment.yaml is templated."""
    errors: List[str] = []
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
        if operator_container is None or helm_container is None:
            errors.append(f"Deployment ({label}): could not find a container to compare")
        else:
            errors.extend(compare_containers(label, operator_container, helm_container))

        operator_svc = find_doc_by_kind(operator_docs, "Service")
        helm_svc = find_doc_by_kind(helm_docs, "Service")

        if operator_svc and helm_svc:
            operator_ports = summarize_service_ports(operator_svc)
            helm_ports = summarize_service_ports(helm_svc)
            if operator_ports != helm_ports:
                errors.append(
                    f"Deployment ({label}): operator repo's webhook Service ports {operator_ports} differ "
                    f"from helm chart's rendered Service ports {helm_ports}"
                )
        elif operator_svc and not helm_svc:
            errors.append(
                f"Deployment ({label}): operator repo defines a Service in {operator_path.name} "
                f"but helm chart did not render one"
            )
        elif helm_svc and not operator_svc:
            errors.append(
                f"Deployment ({label}): helm chart rendered a Service but operator repo's "
                f"{operator_path.name} does not define one"
            )

    return errors


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

    readme_errors = check_readme_images(
        helm_dir / "charts" / f"{args.abbrev}-db" / "values.yaml",
        helm_dir / "charts" / f"{args.abbrev}-db" / "README.md",
    )

    rbac_errors = check_rbac(operator_dir, helm_dir, args.abbrev)
    deployment_errors = check_deployment(operator_dir, helm_dir, args.abbrev)

    all_errors = (
        crd_errors + image_errors + vs_nonprod_errors + vs_prod_errors
        + readme_errors + rbac_errors + deployment_errors
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

    for label, errors, skip_reason in (
        ("non-prod", vs_nonprod_errors, vs_nonprod_skip),
        ("prod", vs_prod_errors, vs_prod_skip),
    ):
        if skip_reason:
            print(f"\nVersion Service ({label}): SKIPPED ({skip_reason})")
        else:
            print(f"\nVersion Service ({label}): {'OK' if not errors else 'MISMATCH'}")
            for err in errors:
                print(f"  - {err}")

    print(f"\nREADME: {'OK' if not readme_errors else 'MISMATCH'}")
    for err in readme_errors:
        print(f"  - {err}")

    print(f"\nRBAC: {'OK' if not rbac_errors else 'MISMATCH'}")
    for err in rbac_errors:
        print(f"  - {err}")

    print(f"\nDeployment: {'OK' if not deployment_errors else 'MISMATCH'}")
    for err in deployment_errors:
        print(f"  - {err}")

    print()
    if all_errors:
        print(f"RESULT: OUT OF SYNC ({len(all_errors)} issue(s) found)")
        return 1

    any_skipped = vs_nonprod_skip or vs_prod_skip
    print("RESULT: IN SYNC" + (" (with skipped checks, see above)" if any_skipped else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
