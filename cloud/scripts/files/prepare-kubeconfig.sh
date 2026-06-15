#!/usr/bin/env bash
set -euo pipefail

MASTER_EXTERNAL_IP=""
OUTPUT_FILE="/tmp/rke2.yaml"
REMOTE_KUBECONFIG="/etc/rancher/rke2/rke2.yaml"

usage() {
  echo "Usage: $0 --master-external-ip <ip> [--remote-kubeconfig <path>] [--output-file <path>]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --master-external-ip)
      MASTER_EXTERNAL_IP="$2"
      shift 2
      ;;
    --remote-kubeconfig)
      REMOTE_KUBECONFIG="$2"
      shift 2
      ;;
    --output-file)
      OUTPUT_FILE="$2"
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

if [[ -z "$MASTER_EXTERNAL_IP" ]]; then
  echo "Missing required argument: --master-external-ip"
  exit 1
fi

sudo sed "s/127.0.0.1/${MASTER_EXTERNAL_IP}/" "$REMOTE_KUBECONFIG" > "$OUTPUT_FILE"
sudo chown "$(id -u):$(id -g)" "$OUTPUT_FILE"
chmod 0600 "$OUTPUT_FILE"
