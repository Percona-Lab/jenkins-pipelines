#!/usr/bin/env bash
set -euo pipefail

RELEASE_NAME=""
CHART=""
CHART_VERSION="latest"
NAMESPACE="default"
REMOTE_KUBECONFIG="/etc/rancher/rke2/rke2.yaml"
REMOTE_USER_KUBECONFIG="/tmp/rke2-user.yaml"
EXTRA_ARGS=()

usage() {
  echo "Usage: $0 --release-name <name> --chart <repo/chart|path> [--chart-version <version>] [--namespace <namespace>] [--remote-kubeconfig <path>] [--remote-user-kubeconfig <path>] [-- <extra helm args>]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-name)        RELEASE_NAME="$2";        shift 2 ;;
    --chart)               CHART="$2";               shift 2 ;;
    --chart-version)       CHART_VERSION="$2";       shift 2 ;;
    --namespace)           NAMESPACE="$2";           shift 2 ;;
    -h|--help)             usage; exit 0 ;;
    *)                     EXTRA_ARGS+=("$1");        shift ;;
  esac
done

[[ -n "$RELEASE_NAME" ]] || { echo "ERROR: --release-name is required"; exit 1; }
[[ -n "$CHART" ]]        || { echo "ERROR: --chart is required";        exit 1; }

sudo cp "$REMOTE_KUBECONFIG" "$REMOTE_USER_KUBECONFIG"
sudo chmod 0600 "$REMOTE_USER_KUBECONFIG"

export KUBECONFIG="$REMOTE_USER_KUBECONFIG"
export PATH="$PATH:/var/lib/rancher/rke2/bin"

version_args=()
if [[ "$CHART_VERSION" != "latest" ]]; then
  version_args=(--version "$CHART_VERSION")
fi

helm upgrade --install "$RELEASE_NAME" "$CHART" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  "${version_args[@]}" \
  "${EXTRA_ARGS[@]}"