#!/usr/bin/env bash
set -euo pipefail

command -v helm >/dev/null 2>&1 || curl -sSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm repo add jetstack https://charts.jetstack.io || true
helm repo add rancher-stable https://releases.rancher.com/server-charts/stable || true
helm repo add longhorn https://charts.longhorn.io || true
helm repo update
