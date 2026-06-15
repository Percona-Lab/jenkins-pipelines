#!/usr/bin/env bash
set -euo pipefail

RELEASE_NAME=""
CHART=""
CHART_VERSION="latest"
NAMESPACE="default"

usage() {
  echo "Usage: $0 --release-name <name> --chart <repo/chart|path> [--chart-version <version>] [--namespace <namespace>]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-name)
      RELEASE_NAME="$2"
      shift 2
      ;;
    --chart)
      CHART="$2"
      shift 2
      ;;
    --chart-version)
      CHART_VERSION="$2"
      shift 2
      ;;
    --namespace)
      NAMESPACE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1
      ;;
  esac
done

[[ -n "$RELEASE_NAME" ]] || {
  echo "ERROR: --release-name is required"
  exit 1
}

[[ -n "$CHART" ]] || {
  echo "ERROR: --chart is required"
  exit 1
}

version_args=()
if [[ "$CHART_VERSION" != "latest" ]]; then
  version_args=(--version "$CHART_VERSION")
fi

helm upgrade --install "$RELEASE_NAME" "$CHART" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  "${version_args[@]}"