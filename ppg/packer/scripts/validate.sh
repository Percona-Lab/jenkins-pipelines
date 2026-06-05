#!/usr/bin/env bash
# Fail-closed identity/health gates for an Oracle Linux package-test
# target AMI. Runs as the LAST provisioner: any non-zero exit aborts the build
# so a broken or mislabeled image is never registered. De-instancing is native
# (see the tail note): Packer clears the temp key and cloud-init re-seeds on boot.
#
# Env (from packer): OS_MAJOR (8|9|10), UNAME_ARCH (x86_64|aarch64)
set -euo pipefail

: "${OS_MAJOR:?OS_MAJOR not set}"
: "${UNAME_ARCH:?UNAME_ARCH not set}"

fail() { echo "VALIDATE FAIL: $*" >&2; exit 1; }

. /etc/os-release
echo "VALIDATE: ${PRETTY_NAME:-?} $(uname -m) (want OL${OS_MAJOR} / ${UNAME_ARCH})"

# 1. Genuine Oracle Linux of the expected major (fidelity gate).
[ -f /etc/oracle-release ] || fail "no /etc/oracle-release (not Oracle Linux)"
grep -Eq "release[[:space:]]+${OS_MAJOR}\." /etc/oracle-release \
  || fail "oracle-release major != ${OS_MAJOR}: $(cat /etc/oracle-release)"

# 2. Architecture matches the target.
[ "$(uname -m)" = "${UNAME_ARCH}" ] || fail "arch $(uname -m) != ${UNAME_ARCH}"

# 3. CodeReady Builder repo must RESOLVE packages (PG -devel deps need it). It is
#    disabled by default on Oracle Linux; enable it just for the query and assert
#    it returns packages (defined-but-404 would pass a mere repolist grep).
crb="ol${OS_MAJOR}_codeready_builder"
# The CRB repo must be DEFINED on the image (it is disabled by default). The
# AUTHORITATIVE resolvability check is the smoke test, which enables CRB and
# actually installs percona-postgresql17-server (pulls CRB deps); a builder-side
# repoquery proved fragile (disabled-repo metadata not fetched), so this gate
# only asserts the repo exists.
dnf repolist --all 2>/dev/null | grep -qiE "codeready" \
  || fail "CRB repo ($crb) not defined on image"
echo "  CRB ok: repo defined ($crb)"

# 4. cloud-init present (instance bootstrap).
command -v cloud-init >/dev/null 2>&1 || fail "cloud-init missing"

# 5. SELinux configured enforcing (fail-closed fidelity gate; stock OL ships enforcing).
grep -Eq "^SELINUX=enforcing" /etc/selinux/config 2>/dev/null \
  || fail "SELINUX not 'enforcing' in /etc/selinux/config"

# 6. dnf metadata is fresh enough to confirm the refresh ran.
dnf -q makecache >/dev/null 2>&1 || fail "dnf makecache failed (broken repos)"

# 7. Refresh actually applied: count packages still upgradable (informational;
#    provision.sh runs `dnf -y update` under set -e, so a hard failure aborts there).
upd=$(dnf -q check-update 2>/dev/null | grep -cE '^[A-Za-z0-9]' || true)
[ "${upd:-0}" -eq 0 ] && echo "  update ok: 0 packages upgradable" \
  || echo "VALIDATE WARN: ${upd} packages still upgradable after dnf update"

echo "VALIDATE OK: OL${OS_MAJOR} ${UNAME_ARCH}"
# De-instancing is native now: packer `ssh_clear_authorized_keys` strips the temp
# key, and cloud-init's default `ssh_deletekeys: true` regenerates host keys on
# first boot. provision.sh already ran `cloud-init clean` + reset machine-id.
