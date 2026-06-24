#!/usr/bin/env bash
set -euo pipefail

FIREWALLD_BASE_SCRIPT=""
INSTALL_ENV=""
REMOTE_KUBECONFIG="/etc/rancher/rke2/rke2.yaml"
RKE2_CONFIG=""

usage() {
  echo "Usage: $0 --config-file <path> --firewalld-script <path> [--install-env '<env vars>'] [--remote-kubeconfig <path>]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config-file)
      RKE2_CONFIG="$(cat "$2")"
      shift 2
      ;;
    --firewalld-script)
      FIREWALLD_BASE_SCRIPT="$2"
      shift 2
      ;;
    --install-env)
      INSTALL_ENV="$2"
      shift 2
      ;;
    --remote-kubeconfig)
      REMOTE_KUBECONFIG="$2"
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

if [[ -z "$RKE2_CONFIG" ]]; then
  echo "Missing required argument: --config-file"
  exit 1
fi

if sudo systemctl is-active --quiet rke2-server && sudo test -s /var/lib/rancher/rke2/server/node-token; then
  sudo /var/lib/rancher/rke2/bin/kubectl --kubeconfig "$REMOTE_KUBECONFIG" get nodes
  exit 0
fi

sudo dnf install -y curl tar wget iptables iscsi-initiator-utils
sudo systemctl enable --now iscsid || true

if [[ -n "$FIREWALLD_BASE_SCRIPT" ]]; then
  sudo bash "$FIREWALLD_BASE_SCRIPT"
fi

sudo mkdir -p /etc/rancher/rke2
printf '%s\n' "$RKE2_CONFIG" | sudo tee /etc/rancher/rke2/config.yaml >/dev/null

curl -sfL https://get.rke2.io -o /tmp/install-rke2.sh

if [[ -n "$INSTALL_ENV" ]]; then
  sudo env $INSTALL_ENV sh /tmp/install-rke2.sh
else
  sudo sh /tmp/install-rke2.sh
fi

sudo systemctl daemon-reload
sudo systemctl cat rke2-server >/dev/null
sudo systemctl enable rke2-server
sudo systemctl restart rke2-server

for _ in $(seq 1 60); do
  if sudo test -s /var/lib/rancher/rke2/server/node-token; then
    break
  fi
  sleep 5
done

sudo test -s /var/lib/rancher/rke2/server/node-token || {
  sudo systemctl status rke2-server --no-pager || true
  sudo journalctl -u rke2-server -n 80 --no-pager || true
  exit 1
}

sudo /var/lib/rancher/rke2/bin/kubectl --kubeconfig "$REMOTE_KUBECONFIG" get nodes
