#!/usr/bin/env bash
# Oracle Linux package-test target refresh provisioner.
# Minimal by design: refresh packages and keep the image close to a vanilla
# Oracle Linux target so package tests stay faithful. De-instancing of the temp
# SSH key is native (Packer ssh_clear_authorized_keys); this script resets
# cloud-init + machine-id below so a launched instance re-initialises.
set -euxo pipefail

# Core refresh: this is what the manual process did by hand.
dnf -y update

# Baseline tooling molecule/ansible drivers expect. These ship in the OL cloud
# image, so this is normally a no-op; fail loudly if the base genuinely lacks them.
dnf -y install python3 cloud-init

# Bake the SSM agent into the image (Oracle Linux does not ship it). The builder
# already got it via user_data; install-if-missing makes provision.sh self-
# sufficient. The baked image needs it so the smoke test (aws ssm send-command)
# and the next refresh bake (session_manager) can connect. REGION/SSM_ARCH come
# from packer; fall back to the global RPM if the regional one is unavailable.
if ! rpm -q amazon-ssm-agent >/dev/null 2>&1; then
  dnf -y install "https://s3.${REGION}.amazonaws.com/amazon-ssm-${REGION}/latest/linux_${SSM_ARCH}/amazon-ssm-agent.rpm" \
    || dnf -y install "https://s3.amazonaws.com/ec2-downloads-windows/SSMAgent/latest/linux_${SSM_ARCH}/amazon-ssm-agent.rpm"
fi
systemctl enable amazon-ssm-agent

dnf clean all
rm -rf /var/cache/dnf

# Reset cloud-init + machine-id so a launched instance re-initialises. The temp
# SSH key is cleared natively by Packer (ssh_clear_authorized_keys) after the build.
cloud-init clean --logs || true
rm -rf /var/lib/cloud/instances/* || true
: > /etc/machine-id || true

echo "PROVISION OK: $(. /etc/os-release; echo "$PRETTY_NAME") $(uname -m)"
