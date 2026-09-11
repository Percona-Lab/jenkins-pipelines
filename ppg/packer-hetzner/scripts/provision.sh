#!/usr/bin/env bash
# Rocky Linux / AlmaLinux / Oracle Linux package-test target refresh
# provisioner (Hetzner Cloud). Minimal by design: refresh packages and keep
# the image close to a vanilla EL target so package tests stay faithful.
# De-instancing of the temp SSH key is native (Packer
# ssh_clear_authorized_keys). This script resets cloud-init + machine-id below
# so a server created from the snapshot re-initialises. Runs as root (Hetzner
# default login), no AWS agents to bake.
#
# Env (from packer): OS (rocky|almalinux|oraclelinux)
set -euxo pipefail

# Core refresh: absorb all pending errata.
dnf -y update

# Baseline tooling molecule/ansible drivers expect. These ship in the Hetzner
# system images, so this is normally a no-op. Fail loudly if the base genuinely
# lacks them.
dnf -y install python3 cloud-init

# Oracle Linux only: keep root ssh-able on every boot from this lineage.
# Hetzner's vendor-data makes root cloud-init's default user, and Oracle's
# stock cloud.cfg ships `disable_root: true`, which stubs the injected key
# (the pristine ppg-base boot needs user-data to unlock, passed by the
# refresh template). Baking the override means smoke, consumer, and refresh
# boots need no user-data. Rocky/Alma ship root login natively (no override
# needed).
if [[ "${OS:-}" = "oraclelinux" ]]; then
  printf 'disable_root: false\n' > /etc/cloud/cloud.cfg.d/95_hetzner_root.cfg
fi

dnf clean all
rm -rf /var/cache/dnf

# Reset cloud-init + machine-id so a server created from the snapshot
# re-initialises: fresh instance ID, regenerated host keys, and the SSH key
# Hetzner passes at create time gets applied. The temp build key is cleared
# natively by Packer (ssh_clear_authorized_keys) after the build.
cloud-init clean --logs
rm -rf /var/lib/cloud/instances
: > /etc/machine-id

echo "PROVISION OK: $(. /etc/os-release; echo "${PRETTY_NAME}") $(uname -m)"
