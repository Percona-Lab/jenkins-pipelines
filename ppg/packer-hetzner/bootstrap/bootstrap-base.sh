#!/usr/bin/env bash
# OL base-snapshot bootstrap - upload Oracle's official OL cloud image to
# Hetzner Cloud as the ppg-base snapshot the refresh template's oraclelinux
# seed sources (one-time per major x arch, OL has no Hetzner system image).
# Sibling of the AWS factory's scripts/bootstrap-ol10.sh (../../packer).
#
# Flow:  download official qcow2 (checksum-verified) -> hcloud-upload-image
#        (rescue-mode dd via a temp server) -> role=ppg-base snapshot
#        -> boot check (fresh cpx32 server from the snapshot, identity +
#        cloud-init + dnf asserts) -> delete the check server + key.
#
# Login user on Hetzner (verified empirically on OL9U8, 2026-08-05): ROOT,
# but only with user-data `disable_root: false` at server create. Hetzner's
# vendor-data (embedded in the metadata DataSourceHetzner consumes) resolves
# cloud-init's default user to root, overriding the image's cloud-user. The
# image's stock `disable_root: true` then wraps root's injected key in a
# "Please login as..." command stub, leaving NO ssh-able account at all on a
# pristine boot. The one-line user-data un-stubs root. The snapshot itself
# stays pristine Oracle. The refresh template's oraclelinux seed passes the
# same user-data and bakes the override in for the rest of the lineage.
#
# Per-major source: x86_64 KVM templates from
# yum.oracle.com/oracle-linux-templates.html (bump build bNNN + sha as Oracle
# republishes). Only OL9 is boot-verified on Hetzner so far. OL8/OL10 entries
# carry Oracle's published checksums but their first upload must be watched.
# arm64 is NOT wired up: CAX server types have zero stock in fsn1/nbg1/hel1
# (verified 2026-08-05), so an arm64 base could not be boot-checked. Add the
# aarch64 -kvm-cloud- sources once CAX stock returns.
#
#   Usage:  HCLOUD_TOKEN in env, then
#           ./bootstrap-base.sh            # OL9 x86_64
#           OS_MAJOR=8 ./bootstrap-base.sh
#
# Idempotent: an existing ppg-base snapshot for the combo skips the download/
# upload (FORCE=1 re-uploads without deleting the old base - prune manually).
# The boot check always runs against the newest base. On a boot-check failure
# the snapshot is KEPT for forensics. The printed id makes manual deletion a
# one-liner. Every server/key this script creates is deleted on exit.
set -euo pipefail

# Linux-only: the body relies on GNU stat -c/sha256sum and bash >= 4.4
# empty-array expansion ("${upload_compression[@]}" under set -u), all of
# which break on the macOS/BSD userland.
[[ "$(uname -s)" = "Linux" ]] || {
  echo "bootstrap-base.sh must run on Linux (GNU stat/sha256sum, bash >= 4.4); got $(uname -s)" >&2
  exit 1
}

: "${HCLOUD_TOKEN:?export the project-scoped factory token}"

OS_MAJOR="${OS_MAJOR:-9}"
ARCH="${ARCH:-x86_64}"
FORCE="${FORCE:-0}"

# Upload server: its DISK size becomes the snapshot's min-disk floor for every
# future consumer, so use the smallest type the 37 GiB image fits, cx23 (40GB,
# in stock in nbg1 as of 2026-08-05), with cpx22/fsn1 as the 80GB fallback.
UPLOAD_SERVER_TYPE="${UPLOAD_SERVER_TYPE:-cx23}"
UPLOAD_LOCATION="${UPLOAD_LOCATION:-nbg1}"

# Boot check server: same family the factory builds with. Gen-1 cpx31/cpx41
# are out of stock in fsn1/nbg1/hel1, so cpx32 is the current x86 tier.
CHECK_SERVER_TYPE="${CHECK_SERVER_TYPE:-cpx32}"
CHECK_LOCATION="${CHECK_LOCATION:-fsn1}"

# hcloud-upload-image: pinned exact + checksum-verified (supply-chain, same
# rationale as the packer plugin pin in ../refresh.pkr.hcl).
UPLOADER_VERSION="${UPLOADER_VERSION:-1.5.0}"

case "${ARCH}" in
  x86_64)
    # The upload passes --server-type (implies x86). Nothing else to derive.
    ;;
  arm64)
    echo "arm64 bases are not wired up yet: CAX (arm64) server types have zero" >&2
    echo "stock in fsn1/nbg1/hel1, so the boot check cannot run. Re-attempt" >&2
    echo "when CAX stock returns (and add the aarch64 -kvm-cloud- source here)." >&2
    exit 1
    ;;
  *)
    echo "ARCH must be x86_64 or arm64 (got '${ARCH}')" >&2
    exit 1
    ;;
esac

# Oracle's published per-image SHA256 (the kvm-sha256 column of the templates
# page). The qcow2 must stay under the rescue system's ~960MB tmpfs cap, or
# the upload needs a local `qemu-img convert -O raw` + --compression instead.
case "${OS_MAJOR}" in
  8)
    src_url="https://yum.oracle.com/templates/OracleLinux/OL8/u10/x86_64/OL8U10_x86_64-kvm-b287.qcow2"
    src_sha256="cf9eb243b7390311f1e2896e3e6849241e521e6592c8be9907956e3a6cee1f0c"
    ;;
  9)
    src_url="https://yum.oracle.com/templates/OracleLinux/OL9/u8/x86_64/OL9U8_x86_64-kvm-b293.qcow2"
    src_sha256="b12103391327abee8090686759c0d62dac9a7af2bf0f45fdf6b0d085a0fbb52b"
    ;;
  10)
    src_url="https://yum.oracle.com/templates/OracleLinux/OL10/u1/x86_64/OL10U1_x86_64-kvm-b291.qcow2"
    src_sha256="8e59326c4bf7cfa58a6cac404db8ed583fe3a5f4c460e2b73c64988785bb4f0f"
    ;;
  *)
    echo "OS_MAJOR must be 8, 9, or 10 (got '${OS_MAJOR}')" >&2
    exit 1
    ;;
esac
src_file="$(basename "${src_url}")"

WORK="${WORK:-${TMPDIR:-/tmp}/ppg-ol-bootstrap}"
mkdir -p "${WORK}/bin"
stamp="$(date -u +%Y%m%d-%H%M%S)"
base_selector="role=ppg-base,os=oraclelinux,os_major=${OS_MAJOR},arch=${ARCH}"
log_step() { echo -e "\n=== $* ==="; }

# --- ephemeral check resources: ALWAYS deleted, even on failure -------------
check_server_name="ppg-bootstrap-check-ol${OS_MAJOR}-${ARCH//_/-}"
check_key_name="ppg-bootstrap-check-${stamp}"
check_key_file="${WORK}/check-key-${stamp}"
cleanup() {
  set +e
  hcloud server delete "${check_server_name}" >/dev/null 2>&1 \
    && echo "cleanup: deleted check server ${check_server_name}"
  hcloud ssh-key delete "${check_key_name}" >/dev/null 2>&1 \
    && echo "cleanup: deleted ssh key ${check_key_name}"
  rm -f "${check_key_file}" "${check_key_file}.pub"
}
trap cleanup EXIT

newest_base() {
  hcloud image list -t snapshot -l "${base_selector}" -s created:desc \
    -o noheader -o columns=id | head -1
}

# 1. Reuse an existing base for this combo unless FORCE=1. Never re-download,
#    never delete the previous base (prune superseded bases manually).
snapshot_id="$(newest_base)"
if [[ -n "${snapshot_id}" && "${FORCE}" != 1 ]]; then
  log_step "reuse existing ppg-base snapshot ${snapshot_id} (FORCE=1 re-uploads)"
else
  # 2. Download + verify Oracle's image (reuse a previously verified file).
  log_step "download ${src_file} + verify Oracle's published sha256"
  if [[ ! -f "${WORK}/${src_file}" ]]; then
    curl -fsSL -o "${WORK}/${src_file}" "${src_url}"
  fi
  echo "${src_sha256}  ${WORK}/${src_file}" | sha256sum -c -

  # Rescue-tmpfs cap: a qcow2 over ~900MB cannot be written directly (the
  # rescue ramdisk holds the whole qcow2 before dd). Convert to raw + xz
  # locally and the tool stream-decompresses straight to disk instead. The
  # raw intermediate is sparse (xz reads the virtual size but writes small).
  upload_file="${WORK}/${src_file}"
  upload_format="qcow2"
  upload_compression=()
  qcow_bytes=$(stat -c %s "${WORK}/${src_file}")
  if (( qcow_bytes > 900 * 1024 * 1024 )); then
    log_step "qcow2 is $((qcow_bytes / 1024 / 1024))MB (over the rescue cap); convert to raw + xz"
    command -v qemu-img >/dev/null || { echo "FATAL: qemu-img is required for the raw+xz path" >&2; exit 1; }
    raw_file="${WORK}/${src_file%.qcow2}.raw"
    if [[ ! -f "${raw_file}.xz" ]]; then
      qemu-img convert -O raw "${WORK}/${src_file}" "${raw_file}"
      xz -T0 -3 -f "${raw_file}"
    fi
    upload_file="${raw_file}.xz"
    upload_format="raw"
    upload_compression=(--compression xz)
  fi

  # 3. Ensure the pinned hcloud-upload-image host binary. The actual write
  #    happens remotely in rescue mode, so local arch only picks the binary.
  log_step "ensure hcloud-upload-image v${UPLOADER_VERSION}"
  uploader="hcloud-upload-image"
  if ! command -v "${uploader}" >/dev/null 2>&1; then
    uploader="${WORK}/bin/hcloud-upload-image"
    if [[ ! -x "${uploader}" ]]; then
      host_os="$(uname -s)"                              # Linux | Darwin
      host_arch="$(uname -m)"                            # x86_64 | aarch64
      if [[ "${host_arch}" == "aarch64" ]]; then
        host_arch=arm64
      fi
      release_base="https://github.com/apricote/hcloud-upload-image/releases/download/v${UPLOADER_VERSION}"
      tarball="hcloud-upload-image_${host_os}_${host_arch}.tar.gz"
      curl -fsSL -o "${WORK}/${tarball}" "${release_base}/${tarball}"
      curl -fsSL -o "${WORK}/uploader-checksums.txt" \
        "${release_base}/hcloud-upload-image_${UPLOADER_VERSION}_checksums.txt"
      (cd "${WORK}" && grep "${tarball}" uploader-checksums.txt | sha256sum -c -)
      tar -xzf "${WORK}/${tarball}" -C "${WORK}/bin" hcloud-upload-image
    fi
  fi
  "${uploader}" version

  # 4. Upload as the ppg-base snapshot (rescue-mode dd through a temp server
  #    the tool creates and deletes, ~5 min). Label contract per ../README.md.
  log_step "upload -> ppg-base snapshot (${UPLOAD_SERVER_TYPE}/${UPLOAD_LOCATION})"
  "${uploader}" upload \
    --image-path "${upload_file}" \
    --format "${upload_format}" \
    "${upload_compression[@]}" \
    --server-type "${UPLOAD_SERVER_TYPE}" \
    --location "${UPLOAD_LOCATION}" \
    --description "PPG-Base-OL${OS_MAJOR}-${ARCH}-${stamp}" \
    --labels "role=ppg-base,os=oraclelinux,os_major=${OS_MAJOR},arch=${ARCH},source=bootstrap"
  snapshot_id="$(newest_base)"
  [[ -n "${snapshot_id}" ]] || { echo "FATAL: upload reported success but no ${base_selector} snapshot found" >&2; exit 1; }
  echo "uploaded base snapshot: ${snapshot_id}"
fi

# 5. Boot check: fresh server from the base, root SSH via the disable_root
#    user-data (see header), fail-closed identity/health asserts.
log_step "boot check ${snapshot_id} on ${CHECK_SERVER_TYPE}/${CHECK_LOCATION}"
ssh-keygen -q -t ed25519 -f "${check_key_file}" -N ''
# The role label makes a leaked key sweepable by scripts/janitor.sh.
hcloud ssh-key create --name "${check_key_name}" \
  --label role=ppg-bootstrap-check \
  --public-key-from-file "${check_key_file}.pub" >/dev/null
printf '#cloud-config\ndisable_root: false\n' > "${WORK}/check-user-data.yaml"
hcloud server create --name "${check_server_name}" \
  --type "${CHECK_SERVER_TYPE}" --location "${CHECK_LOCATION}" \
  --image "${snapshot_id}" --ssh-key "${check_key_name}" \
  --user-data-from-file "${WORK}/check-user-data.yaml" \
  --label role=ppg-bootstrap-check >/dev/null
check_ip="$(hcloud server ip "${check_server_name}")"

ssh_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
  -o ConnectTimeout=5 -o BatchMode=yes -i "${check_key_file}")
ssh_ready=""
for _ in $(seq 1 60); do
  if ssh "${ssh_opts[@]}" "root@${check_ip}" true 2>/dev/null; then
    ssh_ready=1
    break
  fi
  sleep 5
done
[[ -n "${ssh_ready}" ]] || { echo "BOOT CHECK FAIL: no root SSH on ${check_ip} after 5 min (snapshot ${snapshot_id} kept for forensics)" >&2; exit 1; }

# shellcheck disable=SC2029  # the command string must expand on the remote side
remote() { ssh "${ssh_opts[@]}" "root@${check_ip}" "$@"; }
assert() {
  local label="$1"
  shift
  if "$@"; then
    echo "  ok: ${label}"
  else
    echo "BOOT CHECK FAIL: ${label} (snapshot ${snapshot_id} kept for forensics)" >&2
    exit 1
  fi
}

# shellcheck disable=SC2016  # $ID expands remotely by design
found_id="$(remote '. /etc/os-release; echo "$ID"')"
# shellcheck disable=SC2016  # $VERSION_ID expands remotely by design
found_version="$(remote '. /etc/os-release; echo "$VERSION_ID"')"
found_machine="$(remote uname -m)"
echo "  identity: ID=${found_id} VERSION_ID=${found_version} $(remote uname -mr)"
assert "os-release ID=ol"                 [ "${found_id}" = ol ]
assert "os-release major=${OS_MAJOR}"     [ "${found_version%%.*}" = "${OS_MAJOR}" ]
assert "arch=${ARCH}"                     [ "${found_machine}" = "${ARCH}" ]
assert "cloud-init present"               remote 'command -v cloud-init >/dev/null'
# Judge the cloud-init status TEXT, not the exit code, same rule as the smoke
# template: EL10 images ship cloud-init 24.x, which exits 2 ("degraded done")
# on Hetzner because the IPv4 link-local metadata probe times out even on the
# pristine official images (key injection still works, via the retried
# datasource paths). A boot that reaches done/disabled passes.
ci_status="$(remote 'cloud-init status --wait 2>/dev/null' || true)"
assert "cloud-init done/disabled"         grep -qE 'done|disabled' <<< "${ci_status}"
assert "dnf makecache"                    remote 'dnf -q makecache >/dev/null'
assert "CRB repo defined (ol${OS_MAJOR}_codeready_builder)" \
  remote "dnf -q repolist --all 2>/dev/null | grep -q '^ol${OS_MAJOR}_codeready_builder'"
echo "  context: selinux=$(remote getenforce) kernel=$(remote uname -r)"

cat <<EOF

BOOTSTRAP-BASE (OL${OS_MAJOR} ${ARCH}) COMPLETE.
  base snapshot: ${snapshot_id}  (${base_selector},source=bootstrap)
NEXT: just seed ${OS_MAJOR} ${ARCH} prod oraclelinux   # lineage root: bake + smoke + promote
EOF
