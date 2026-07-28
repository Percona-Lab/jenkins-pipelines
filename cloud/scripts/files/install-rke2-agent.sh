#!/usr/bin/env bash
set -euo pipefail

FIREWALLD_BASE_SCRIPT=""
RKE2_CONFIG=""
INSTALL_ENV=""

usage() {
  echo "Usage: $0 --config-file <path> --firewalld-script <path> [--install-env '<env vars>']"
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

if sudo systemctl is-active --quiet rke2-agent; then
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
sudo systemctl cat rke2-agent >/dev/null
sudo systemctl enable rke2-agent
sudo systemctl start rke2-agent