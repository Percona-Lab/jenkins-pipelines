#!/usr/bin/env bash
# OL10 bootstrap finalize provisioner (runs via finalize-ol10.pkr.hcl).
# Turns Oracle's official OL10 cloud image (default user opc, no ssm-agent) into a
# base the factory can consume: default user ec2-user + amazon-ssm-agent + latest
# errata, then de-instance so the produced AMI re-seeds on first boot. Packer
# (finalize-ol10.pkr.hcl) owns the AMI/snapshot TAGS - this script only mutates
# the image. amazon-ssm-agent is installed at launch by the template's user_data.
set -euo pipefail

# default_user -> ec2-user. 99- so it sorts AFTER Oracle's cloud.cfg.d/90_ol.cfg
# (which sets default_user: opc and would otherwise win).
cat >/etc/cloud/cloud.cfg.d/99-ec2-user.cfg <<'CFG'
system_info:
  default_user: {name: ec2-user, gecos: "EC2 Default User", sudo: ["ALL=(ALL) NOPASSWD:ALL"], groups: [adm, systemd-journal, wheel], shell: /bin/bash}
CFG

systemctl enable amazon-ssm-agent   # installed at launch by user_data; enable for the baked image
dnf -y update                       # latest errata (the base ships a few behind)

cloud-init clean --logs 2>/dev/null || true   # re-seed on first boot -> creates ec2-user
: > /etc/machine-id || true

echo "FINALIZE-OL10 OK: $(rpm -q amazon-ssm-agent); default_user=ec2-user; $(cat /etc/oracle-release)"
