#!/usr/bin/env bash
# Fail-closed identity/health gates for an Oracle Linux / Rocky Linux
# package-test target AMI. Runs as the LAST provisioner: any non-zero exit
# aborts the build so a broken or mislabeled image is never registered.
# De-instancing is native (see the tail note): Packer clears the temp key and
# cloud-init re-seeds on boot.
#
# Env (from packer): OS (oraclelinux|rocky, defaulting to oraclelinux so the OL10
# bootstrap/reimage callers stay unchanged), OS_MAJOR (8|9|10), UNAME_ARCH
# (x86_64|aarch64)
set -euo pipefail

: "${OS_MAJOR:?OS_MAJOR not set}"
: "${UNAME_ARCH:?UNAME_ARCH not set}"
: "${OS:=oraclelinux}"

fail() { echo "VALIDATE FAIL: $*" >&2; exit 1; }

. /etc/os-release
echo "VALIDATE: ${PRETTY_NAME:-?} $(uname -m) (want ${OS} ${OS_MAJOR} / ${UNAME_ARCH})"

# 1. Genuine OS of the expected major (fidelity gate). Keyed on /etc/os-release
#    ID + VERSION_ID (sourced above): distro release-file names cannot tell EL
#    distros apart once more are added (every clone ships /etc/redhat-release).
case "${OS}" in
  oraclelinux) expected_id=ol ;;
  rocky)       expected_id=rocky ;;
  *)           fail "unknown OS '${OS}' (want oraclelinux|rocky)" ;;
esac

[[ "${ID:-}" = "${expected_id}" ]] || fail "os-release ID '${ID:-}' != '${expected_id}'"
os_major_found="${VERSION_ID:-}"
os_major_found="${os_major_found%%.*}"
[[ "${os_major_found}" = "${OS_MAJOR}" ]] \
  || fail "os-release major '${os_major_found}' != '${OS_MAJOR}' (VERSION_ID='${VERSION_ID:-}')"

# 2. Architecture matches the target.
[[ "$(uname -m)" = "${UNAME_ARCH}" ]] || fail "arch $(uname -m) != ${UNAME_ARCH}"

# 3. The CRB repo must be DEFINED on the image (disabled by default, and PG
#    -devel deps need it). One mapping per distro/major, one anchored grep. The
#    AUTHORITATIVE resolvability check is the smoke test, which enables it and
#    actually installs percona-postgresql17-server. A builder-side repoquery
#    proved fragile (disabled-repo metadata not fetched), so this gate only
#    asserts the repo exists.
case "${OS}/${OS_MAJOR}" in
  oraclelinux/*) crb="ol${OS_MAJOR}_codeready_builder" ;;
  rocky/8)       crb="powertools" ;;
  rocky/*)       crb="crb" ;;
  *)             fail "no CRB mapping for ${OS}/${OS_MAJOR}" ;;
esac

dnf repolist --all 2>/dev/null | grep -qiE "^${crb}([[:space:]]|$)" \
  || fail "CRB repo (${crb}) not defined on image"
echo "  CRB ok: repo defined (${crb})"

# 4. cloud-init present (instance bootstrap).
command -v cloud-init >/dev/null 2>&1 || fail "cloud-init missing"

# 5. SELinux configured enforcing (fail-closed fidelity gate, stock OL and Rocky ship enforcing).
grep -Eq "^SELINUX=enforcing" /etc/selinux/config 2>/dev/null \
  || fail "SELINUX not 'enforcing' in /etc/selinux/config"

# 6. dnf metadata is fresh enough to confirm the refresh ran.
dnf -q makecache >/dev/null 2>&1 || fail "dnf makecache failed (broken repos)"

# 7. Refresh actually applied: list what is still upgradable (informational;
#    provision.sh runs `dnf -y update` under set -e, so a hard failure aborts
#    there). The Obsoleting Packages section and "Security: ..." advisory
#    notices (installed-vs-running kernel on the never-rebooted builder) are
#    excluded: neither is a pending upgrade, but a bare count included them.
pending=$(dnf -q check-update 2>/dev/null | sed '/^Obsoleting Packages/,$d' | grep -E '^[A-Za-z0-9]' | grep -vE '^Security: ' || true)

if [[ -z "${pending}" ]]; then
  echo "  update ok: 0 packages upgradable"
else
  echo "VALIDATE WARN: packages still upgradable after dnf update:"
  printf '%s\n' "${pending}"
fi

echo "VALIDATE OK: ${OS} ${OS_MAJOR} ${UNAME_ARCH}"
# De-instancing is native now: packer `ssh_clear_authorized_keys` strips the temp
# key, and cloud-init's default `ssh_deletekeys: true` regenerates host keys on
# first boot. provision.sh already ran `cloud-init clean` + reset machine-id.
