#!/bin/bash
# Linux amd64 Jenkins agent setup for the molecule + qemu harness.
# Downloads only the amd64 cloud images.
#
# For Linux aarch64: use ./setup-aarch64.sh
# For macOS Apple Silicon: use ./setup-arm.sh
#
# SUDO-FREE: the chaos-amd agent user has no sudo, so this script no longer
# installs OS packages or configures apt-cacher-ng. Those must be baked into
# the node image once (see "Node pre-provisioning" below). This script runs as
# the agent user and only builds the Python venv + downloads cloud images under
# $HOME-owned paths.
#
# Node pre-provisioning (one-time, by an admin with root):
#   apt-get install -y qemu-system-x86 qemu-utils python3 python3-venv \
#       python3-pip genisoimage wget git apt-cacher-ng
#   systemctl enable --now apt-cacher-ng
#   # Optional apt-cacher-ng tuning for parallel-VM package caching, appended
#   # to /etc/apt-cacher-ng/acng.conf:
#   #   Remap-percona: http://repo.percona.com ; https://repo.percona.com
#   #   PassThroughPattern: .*
#   # then: systemctl reload apt-cacher-ng

set -e

[ "$(uname)" = "Linux" ] || { echo "setup.sh is for Linux. On macOS use ./setup-arm.sh"; exit 1; }

VENV_DIR="${HOME}/.venv/molecule_qemu"
# Cache cloud images in a fixed, HOME-independent location so repeat runs
# always find the already-converted .raw files and skip re-downloading.
# Override with QEMU_IMAGES_DIR if the agent home differs. NOTE: the molecule
# scenarios (package-testing molecule/ps/chaos/molecule/<os>/molecule.yml) must
# point their image: paths at this SAME directory, or the VMs won't find them.
IMG_DIR="${QEMU_IMAGES_DIR:-/opt/jenkins-agent/qemu-images}"

# Preflight: the node must be pre-provisioned (we have no sudo to install).
# Fail loudly and early rather than deep inside molecule with a cryptic error.
echo "→ Checking required tools are present (node must be pre-provisioned)"
missing=""
for cmd in qemu-system-x86_64 qemu-img python3 genisoimage wget git; do
    command -v "${cmd}" >/dev/null 2>&1 || missing="${missing} ${cmd}"
done
if [ -n "${missing}" ]; then
    echo "ERROR: missing required tools:${missing}" >&2
    echo "The agent user has no sudo; install these on the node image first." >&2
    echo "See 'Node pre-provisioning' at the top of this script." >&2
    exit 1
fi

echo "→ Python venv at ${VENV_DIR}"
python3 -m venv "${VENV_DIR}"
# shellcheck disable=SC1091
source "${VENV_DIR}/bin/activate"
pip install --upgrade pip
pip install "molecule>=25.6.0,<26" "molecule-plugins>=23.7.0,<26" "ansible-core>=2.17,<2.18"
ansible-galaxy collection install ansible.posix community.general

if [ ! -f "${HOME}/.ssh/id_rsa" ]; then
    echo "→ Generating SSH key"
    ssh-keygen -t rsa -b 4096 -f "${HOME}/.ssh/id_rsa" -N ""
fi

echo "→ Preparing ${IMG_DIR}"
mkdir -p "${IMG_DIR}"
cd "${IMG_DIR}"

IMAGES=(
  "https://cloud.debian.org/images/cloud/bullseye/latest/debian-11-genericcloud-amd64.qcow2 debian-11-genericcloud-amd64"
  "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.qcow2 debian-12-genericcloud-amd64"
  "https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-amd64.qcow2 debian-13-genericcloud-amd64"
  "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img jammy-server-cloudimg-amd64"
  "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img noble-server-cloudimg-amd64"
  "https://download.rockylinux.org/pub/rocky/9/images/x86_64/Rocky-9-GenericCloud.latest.x86_64.qcow2 Rocky-9-GenericCloud.latest.x86_64"
  "https://download.rockylinux.org/pub/rocky/10/images/x86_64/Rocky-10-GenericCloud-Base.latest.x86_64.qcow2 Rocky-10-GenericCloud.latest.x86_64"
  "https://yum.oracle.com/templates/OracleLinux/OL9/u7/x86_64/OL9U7_x86_64-kvm-b269.qcow2 oraclelinux-9-amd64"
  "https://yum.oracle.com/templates/OracleLinux/OL10/u1/x86_64/OL10U1_x86_64-kvm-b270.qcow2 oraclelinux-10-amd64"
  "https://cdn.amazonlinux.com/al2023/os-images/2023.11.20260511.1/kvm/al2023-kvm-2023.11.20260511.1-kernel-6.1-x86_64.xfs.gpt.qcow2 al2023-amd64"
)

echo "→ Downloading and converting amd64 cloud images into ${IMG_DIR}"
# Minimum plausible size (100 MB) for a converted raw image. A smaller file is
# a leftover from an interrupted download/convert and must be re-fetched.
MIN_RAW_BYTES=$((100 * 1024 * 1024))
for entry in "${IMAGES[@]}"; do
    # shellcheck disable=SC2086
    set -- $entry
    url=$1
    base=$2
    src="${base}.qcow2"
    raw="${base}.raw"

    # Drop a truncated/partial .raw so the guard below re-fetches it.
    if [ -f "${raw}" ]; then
        raw_size=$(stat -c%s "${raw}" 2>/dev/null || echo 0)
        if [ "${raw_size}" -lt "${MIN_RAW_BYTES}" ]; then
            echo "  ${raw} looks truncated (${raw_size} bytes) - re-fetching"
            rm -f "${raw}" "${src}"
        fi
    fi

    if [ -f "${raw}" ]; then
        echo "  already present, skipping: ${IMG_DIR}/${raw}"
        continue
    fi

    if [ ! -f "${src}" ]; then
        echo "  downloading ${url}"
        wget -q --show-progress "${url}" -O "${src}"
    fi
    echo "  converting ${src} -> ${raw}"
    qemu-img convert -f qcow2 -O raw "${src}" "${raw}"
    rm -f "${src}"
done
