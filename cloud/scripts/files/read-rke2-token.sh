#!/usr/bin/env bash
set -euo pipefail

if ! sudo systemctl cat rke2-server >/dev/null 2>&1; then
  echo "ERROR: rke2-server.service is missing. The RKE2 install step did not complete on this VM."
  exit 1
fi

for _ in $(seq 1 60); do
  if sudo test -s /var/lib/rancher/rke2/server/node-token; then
    sudo cat /var/lib/rancher/rke2/server/node-token
    exit 0
  fi
  sleep 5
done

sudo systemctl status rke2-server --no-pager || true
sudo journalctl -u rke2-server -n 80 --no-pager || true
exit 1
