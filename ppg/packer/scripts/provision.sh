#!/usr/bin/env bash
# Oracle Linux package-test target refresh provisioner.
# Minimal by design: refresh packages and keep the image close to a vanilla
# Oracle Linux target so package tests stay faithful. De-instancing of the temp
# SSH key is native (Packer ssh_clear_authorized_keys); this script resets
# cloud-init + machine-id below so a launched instance re-initialises.
set -euxo pipefail

# /boot hygiene, before the update so the kernel transaction has room. Rocky
# images ship dracut-config-rescue, and the machine-id reset below gives every
# chained bake a fresh machine id, so each kernel-installing bake added one
# more ~90MB rescue pair (dracut's rescue hook keys its state by machine id
# and never cleans other ids'). Five pairs filled the fixed 936MB /boot and
# kernel updates started failing mid-transaction. CI images never boot the
# rescue entry: stop generating it, drop accumulated pairs, and purge loader
# entries that point at removed kernels. Every step no-ops on images without
# the rescue package or without a separate /boot (Oracle Linux).
if rpm -q dracut-config-rescue >/dev/null 2>&1; then
  dnf -y remove dracut-config-rescue
fi

rm -f /boot/vmlinuz-0-rescue-* /boot/initramfs-0-rescue-* /boot/.vmlinuz-0-rescue-*.hmac
rm -f /boot/loader/entries/*-0-rescue*.conf

for entry in /boot/loader/entries/*.conf; do
  [[ -e "${entry}" ]] || continue
  image="$(sed -n 's/^linux //p' "${entry}")"
  if [[ -n "${image}" && ! -e "/boot${image}" ]]; then
    rm -f "${entry}"
  fi
done

# The update needs old+new kernel on /boot at once, plus the ~80MB initramfs
# dracut writes after rpm's disk check. Fail here, with a clear message,
# rather than mid-transaction.
boot_free_mb="$(df -BM --output=avail /boot | tail -1 | tr -dc '0-9')"
if (( boot_free_mb < 200 )); then
  echo "PROVISION FAIL: /boot has ${boot_free_mb}MB free, need >= 200MB for a kernel update" >&2
  exit 1
fi

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
