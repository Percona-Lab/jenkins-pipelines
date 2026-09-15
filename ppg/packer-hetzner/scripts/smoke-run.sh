#!/usr/bin/env bash
# Fresh-boot smoke gate for a candidate snapshot, shared by the justfile
# `smoke` recipe and the workflow "Smoke test" step so the local and CI gates
# cannot drift. Runs the native-Packer smoke build (smoke/smoke.pkr.hcl) and
# gates the candidate delete on the SMOKE-PROVISIONER-STARTED sentinel the
# template echoes as its first provisioner command:
#
#   fail WITH the sentinel:    SSH was up and the provisioner ran, so the
#                              candidate genuinely fails boot/install ->
#                              delete it, exit 1
#   fail WITHOUT the sentinel: transient infrastructure (server create failed,
#                              CAX stock-out, API 5xx, SSH timeout) -> KEEP
#                              the candidate for a retry, exit 1
#   pass:                      label the candidate smoke=passed (retried,
#                              non-fatal) so a later transient promote failure
#                              leaves it distinguishable from debris, exit 0
#
# Promotion stays the caller's separate retried step (scripts/promote.sh).
#
# The snapshot ID is untrusted input: a fail-closed identity guard (the
# promote.sh pattern) describes the image before the boot and again right
# before the delete, and refuses both unless every label of the expected
# candidate combo matches.
#
# Usage (HCLOUD_TOKEN in env, run from ppg/packer-hetzner):
#   smoke-run.sh <snapshot_id> <os_major> <arch> [prod|test] [os] [location]
set -euo pipefail

usage="usage: smoke-run.sh <snapshot_id> <os_major> <arch> [prod|test] [os] [location]"
snapshot_id="${1:?${usage}}"
os_major="${2:?${usage}}"
arch="${3:?${usage}}"
factory_env="${4:-prod}"
os_name="${5:-rocky}"
location="${6:-fsn1}"

case "${factory_env}" in
  prod)
    role_candidate="ppg-candidate"
    source_expected="factory"
    ;;
  test)
    role_candidate="ppg-test-candidate"
    source_expected="factory-test"
    ;;
  *)
    echo "${usage}" >&2
    exit 1
    ;;
esac

# Fail-closed identity guard, following the promote.sh pattern: describe the
# image and require every label of the expected candidate combo before any
# hcloud mutation. Runs BEFORE the boot and again immediately before the
# delete, so a copied or mistyped ID (a promoted package-test snapshot, a
# rollback, an OL base) is never booted by the smoke, let alone deleted by it.
verify_candidate_identity() {
  local stage="$1"
  local image_json
  image_json=$(hcloud image describe "${snapshot_id}" -o json)

  local image_role image_env image_source image_os image_major image_arch
  read -r image_role image_env image_source image_os image_major image_arch \
    <<< "$(python3 -c '
import json, sys
labels = json.load(sys.stdin).get("labels") or {}
keys = ("role", "factory_env", "source", "os", "os_major", "arch")
print(" ".join(labels.get(key) or "-" for key in keys))
' <<< "${image_json}")"

  local mismatch=""
  local check name want got

  for check in \
    "role|${role_candidate}|${image_role}" \
    "factory_env|${factory_env}|${image_env}" \
    "source|${source_expected}|${image_source}" \
    "os|${os_name}|${image_os}" \
    "os_major|${os_major}|${image_major}" \
    "arch|${arch}|${image_arch}"; do

    IFS='|' read -r name want got <<< "${check}"
    [[ "${got}" == "-" ]] && got=""

    if [[ "${got}" != "${want}" ]]; then
      mismatch+=" ${name}=${got:-<missing>} (want ${want})"
    fi
  done

  if [[ -n "${mismatch}" ]]; then
    echo "FATAL: ${snapshot_id} is not the expected ${factory_env} candidate (${stage} check):${mismatch} (no hcloud mutation)" >&2
    return 1
  fi
}

sentinel_started="SMOKE-PROVISIONER-STARTED"
sentinel_completed="SMOKE-PROVISIONER-COMPLETED"
smoke_log="$(mktemp -t ppg-smoke-XXXXXX.log)"
trap 'rm -f "${smoke_log}"' EXIT

echo "smoke candidate ${snapshot_id} (${os_name}${os_major} ${arch}, env=${factory_env})"

verify_candidate_identity "pre-boot"

smoke_status=0
( cd smoke && packer init . >/dev/null && \
  packer build -color=false \
    -var "candidate_image=${snapshot_id}" -var "os=${os_name}" \
    -var "os_major=${os_major}" -var "arch=${arch}" \
    -var "location=${location}" . ) 2>&1 | tee "${smoke_log}" \
  || smoke_status=$?

if [[ "${smoke_status}" -ne 0 ]]; then

  # Delete only when the provisioner began and did not finish: that is a
  # genuine boot/install failure. Failures before the provisioner (server
  # create, SSH) and after it completed (packer teardown, API) keep the
  # candidate. Residual window: a mid-provision transient (an SSH drop) is
  # indistinguishable from an install hang and also deletes. A re-bake is
  # the cheap retry for both.
  if grep -qF "${sentinel_started}" "${smoke_log}" \
    && ! grep -qF "${sentinel_completed}" "${smoke_log}"; then

    # Re-verify identity immediately before the destructive call: the pre-boot
    # check bounds the window, this one closes it (a promote racing this run,
    # or any label change since the boot, refuses the delete).
    if verify_candidate_identity "pre-delete"; then
      echo "smoke (boot+install) failed after the provisioner started; deleting candidate ${snapshot_id}"
      hcloud image delete "${snapshot_id}"
    else
      echo "candidate ${snapshot_id} kept: identity guard refused the delete" >&2
    fi

  else
    echo "transient failure outside the smoke provisioner, candidate ${snapshot_id} kept for retry"
  fi

  exit 1
fi

# Mark the pass on the image BEFORE the caller promotes: a transient promote
# failure then leaves a candidate the prune sweep can tell apart from debris.
# Non-fatal on label failure: the smoke PASSED, and promote.sh sets
# smoke=passed again as part of the role flip.
for attempt in 1 2 3; do
  if hcloud image add-label --overwrite "${snapshot_id}" "smoke=passed"; then
    break
  fi
  if [[ "${attempt}" -eq 3 ]]; then
    echo "WARNING: could not label ${snapshot_id} smoke=passed after 3 attempts (continuing, promote.sh sets it too)" >&2
  else
    echo "smoke=passed label attempt ${attempt} failed; retry in $((attempt * 5))s"
    sleep $((attempt * 5))
  fi
done

echo "smoke passed for ${snapshot_id}"
