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

# An entry's "linux" path is relative to the filesystem that holds /boot: a
# separate /boot partition gives "/vmlinuz-<ver>", /boot on the root filesystem
# (the Oracle Linux cloud images) gives "/boot/vmlinuz-<ver>". Check both forms,
# or every entry on a root-filesystem /boot image is deleted and the image only
# boots again if this same bake happens to install a new kernel.
for entry in /boot/loader/entries/*.conf; do
  [[ -e "${entry}" ]] || continue
  image="$(sed -n 's/^linux //p' "${entry}")"
  if [[ -n "${image}" && ! -e "/boot${image}" && ! -e "${image}" ]]; then
    rm -f "${entry}"
  fi
done

# kdump writes a crash-dump initramfs for the running kernel at boot whenever
# that kernel has none, so each chained bake adds one more ~35MB image that no
# package owns. Retiring the kernel later removes its own files but leaves
# that image, and ten of them tripped the headroom gate on Rocky 10. Package
# tests never take a crash dump: turn the service off where it is on (the unit
# ships in kdump-utils on EL10 and in kexec-tools on EL8/9, or is absent) and
# drop the images.
kdump_state="$(systemctl is-enabled kdump.service 2>/dev/null || true)"
if [[ "${kdump_state}" == enabled* ]]; then
  systemctl disable --now kdump.service
fi

rm -f /boot/initramfs-*kdump.img

# The update needs old+new kernel on /boot at once, plus the ~80MB initramfs
# dracut writes after rpm's disk check. Fail here, with a clear message,
# rather than mid-transaction. XFS frees the blocks of the files removed above
# in the background, so re-read a few times before giving up.
boot_free_mb=0
for attempt in 1 2 3 4 5 6; do
  boot_free_mb="$(df -BM --output=avail /boot | tail -1 | tr -dc '0-9')"
  (( boot_free_mb >= 200 )) && break
  echo "/boot has ${boot_free_mb}MB free after cleanup, waiting for XFS to release blocks (attempt ${attempt})"
  sync
  sleep 2
done
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
# Truncate the machine-id, never delete it: an empty file is not a systemd
# first boot, so no preset-all runs on the consumer and kdump.service stays off.
cloud-init clean --logs || true
rm -rf /var/lib/cloud/instances/* || true
: > /etc/machine-id || true

echo "PROVISION OK: $(. /etc/os-release; echo "$PRETTY_NAME") $(uname -m)"
