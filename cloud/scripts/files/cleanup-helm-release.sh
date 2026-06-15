#!/usr/bin/env bash
set -euo pipefail

NAMESPACE=""
RELEASE=""
REMOTE_KUBECONFIG="/etc/rancher/rke2/rke2.yaml"
REMOTE_USER_KUBECONFIG="/tmp/rke2-user.yaml"

usage() {
  echo "Usage: $0 --release <release> --namespace <namespace> [--remote-kubeconfig <path>] [--remote-user-kubeconfig <path>]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)
      RELEASE="$2"
      shift 2
      ;;
    --namespace)
      NAMESPACE="$2"
      shift 2
      ;;
    --remote-kubeconfig)
      REMOTE_KUBECONFIG="$2"
      shift 2
      ;;
    --remote-user-kubeconfig)
      REMOTE_USER_KUBECONFIG="$2"
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

if [[ -z "$RELEASE" ]]; then
  echo "Missing required argument: --release"
  exit 1
fi

if [[ -z "$NAMESPACE" ]]; then
  echo "Missing required argument: --namespace"
  exit 1
fi

sudo cp "$REMOTE_KUBECONFIG" "$REMOTE_USER_KUBECONFIG"
sudo chown "$(id -u):$(id -g)" "$REMOTE_USER_KUBECONFIG"
chmod 0600 "$REMOTE_USER_KUBECONFIG"
export KUBECONFIG="$REMOTE_USER_KUBECONFIG"
export PATH="$PATH:/var/lib/rancher/rke2/bin"

helm uninstall "$RELEASE" --namespace "$NAMESPACE" || true
kubectl -n "$NAMESPACE" delete job --all --ignore-not-found=true || true
kubectl -n "$NAMESPACE" delete pod --all --ignore-not-found=true --force --grace-period=0 || true
