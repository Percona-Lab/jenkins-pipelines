#!/usr/bin/env bash
set -euo pipefail

# These interfaces exist only after RKE2/Canal creates the CNI dataplane.
# Trust them so pod-to-pod and pod-to-service traffic, including CoreDNS, is not blocked.
for iface in flannel.1 cni0; do
  if ip link show "$iface" >/dev/null 2>&1; then
    firewall-cmd --permanent --zone=trusted --add-interface="$iface" || true
  fi
done

for iface in $(ip -o link show | awk -F': ' '{print $2}' | grep '^cali' || true); do
  firewall-cmd --permanent --zone=trusted --add-interface="$iface" || true
done

firewall-cmd --reload || true
