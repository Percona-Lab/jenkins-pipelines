#!/usr/bin/env bash
set -euo pipefail

# Base firewall/sysctl rules needed before RKE2 starts.
dnf install -y firewalld || true
systemctl enable --now firewalld || true

# RKE2 API and agent supervisor.
firewall-cmd --permanent --add-port=6443/tcp || true
firewall-cmd --permanent --add-port=9345/tcp || true

# Kubelet metrics/exec/logs and Flannel VXLAN overlay used by RKE2 Canal.
firewall-cmd --permanent --add-port=10250/tcp || true
firewall-cmd --permanent --add-port=8472/udp || true

# Let pod traffic be masqueraded/forwarded by the CNI.
firewall-cmd --permanent --add-masquerade || true

cat >/etc/sysctl.d/99-rke2.conf <<EOF
net.ipv4.ip_forward=1
net.ipv6.conf.all.forwarding=1
EOF

sysctl --system
firewall-cmd --reload || true
