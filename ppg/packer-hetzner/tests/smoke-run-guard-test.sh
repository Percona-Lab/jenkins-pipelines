#!/bin/bash
# Stubbed behavior test for the fail-closed identity guard in
# scripts/smoke-run.sh: a wrong or relabeled snapshot ID must never be booted
# or deleted, while a genuine boot/install failure on a real candidate still
# deletes it. Runs against tests/stubs (fake hcloud + packer), so no token and
# no API. Executed by scripts/check.sh.
set -euo pipefail

err() {
  echo "$*" >&2
}

factory_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly factory_root

test_root=$(mktemp -d -t ppg-smoke-guard-XXXXXX)
trap 'rm -rf "${test_root}"' EXIT

candidate_json='{"id": 42, "labels": {"role": "ppg-candidate", "factory_env": "prod", "source": "factory", "os": "rocky", "os_major": "9", "arch": "x86_64"}}'
promoted_json='{"id": 42, "labels": {"role": "ppg-package-test", "factory_env": "prod", "source": "factory", "os": "rocky", "os_major": "9", "arch": "x86_64", "smoke": "passed"}}'
readonly candidate_json promoted_json

# Fresh per-scenario stub state: call log, fixture dir, packer transcript.
scenario_setup() {
  local name="$1"
  scenario_dir="${test_root}/${name}"
  mkdir -p "${scenario_dir}/fixtures"
  export STUB_CALL_LOG="${scenario_dir}/calls.log"
  export STUB_FIXTURE_DIR="${scenario_dir}/fixtures"
  export STUB_PACKER_OUTPUT="${scenario_dir}/packer-output.txt"
  : > "${STUB_CALL_LOG}"
}

# Run smoke-run.sh with the stubs first in PATH; never aborts the test on a
# nonzero exit (the exit code is itself an assertion target).
run_smoke() {
  smoke_rc=0
  smoke_output=$(cd "${factory_root}" \
    && PATH="${factory_root}/tests/stubs:${PATH}" \
      bash scripts/smoke-run.sh "$@" 2>&1) || smoke_rc=$?
}

assert_eq() {
  local what="$1" want="$2" got="$3"

  if [[ "${got}" != "${want}" ]]; then
    err "FAIL ${scenario}: ${what}: want '${want}', got '${got}'"
    err "--- output ---"
    err "${smoke_output}"
    err "--- calls ---"
    cat "${STUB_CALL_LOG}" >&2
    exit 1
  fi
}

assert_output_has() {
  assert_eq "output contains '$1'" "yes" \
    "$(grep -qF -- "$1" <<< "${smoke_output}" && echo yes || echo no)"
}

assert_calls_count() {
  local pattern="$1" want="$2"
  assert_eq "calls matching '${pattern}'" "${want}" \
    "$(grep -c -- "${pattern}" "${STUB_CALL_LOG}" || true)"
}

main() {
  scenario="wrong-identity-refuses-boot"
  scenario_setup "${scenario}"
  echo "${promoted_json}" > "${STUB_FIXTURE_DIR}/image-42.json"
  export STUB_PACKER_EXIT=0
  : > "${STUB_PACKER_OUTPUT}"
  run_smoke 42 9 x86_64 prod rocky
  assert_eq "exit code" 1 "${smoke_rc}"
  assert_output_has "FATAL: 42 is not the expected prod candidate (pre-boot"
  assert_calls_count "^packer build" 0
  assert_calls_count "^image delete" 0
  echo "PASS ${scenario}"

  scenario="genuine-failure-deletes-candidate"
  scenario_setup "${scenario}"
  echo "${candidate_json}" > "${STUB_FIXTURE_DIR}/image-42.json"
  export STUB_PACKER_EXIT=1
  echo "SMOKE-PROVISIONER-STARTED" > "${STUB_PACKER_OUTPUT}"
  run_smoke 42 9 x86_64 prod rocky
  assert_eq "exit code" 1 "${smoke_rc}"
  assert_calls_count "^image describe 42" 2
  assert_calls_count "^image delete 42" 1
  assert_eq "delete is the last hcloud call" "image delete 42" \
    "$(tail -n 1 "${STUB_CALL_LOG}")"
  echo "PASS ${scenario}"

  scenario="relabeled-mid-run-refuses-delete"
  scenario_setup "${scenario}"
  echo "${candidate_json}" > "${STUB_FIXTURE_DIR}/image-42.json"
  echo "${promoted_json}" > "${STUB_FIXTURE_DIR}/image-42.2.json"
  export STUB_PACKER_EXIT=1
  echo "SMOKE-PROVISIONER-STARTED" > "${STUB_PACKER_OUTPUT}"
  run_smoke 42 9 x86_64 prod rocky
  assert_eq "exit code" 1 "${smoke_rc}"
  assert_output_has "kept: identity guard refused the delete"
  assert_calls_count "^image delete" 0
  echo "PASS ${scenario}"

  scenario="transient-failure-keeps-candidate"
  scenario_setup "${scenario}"
  echo "${candidate_json}" > "${STUB_FIXTURE_DIR}/image-42.json"
  export STUB_PACKER_EXIT=1
  echo "Error: could not create server: rate limit exceeded" \
    > "${STUB_PACKER_OUTPUT}"
  run_smoke 42 9 x86_64 prod rocky
  assert_eq "exit code" 1 "${smoke_rc}"
  assert_output_has "kept for retry"
  assert_calls_count "^image describe 42" 1
  assert_calls_count "^image delete" 0
  echo "PASS ${scenario}"

  scenario="pass-labels-and-keeps"
  scenario_setup "${scenario}"
  echo "${candidate_json}" > "${STUB_FIXTURE_DIR}/image-42.json"
  export STUB_PACKER_EXIT=0
  printf '%s\n%s\n' "SMOKE-PROVISIONER-STARTED" "SMOKE-PROVISIONER-COMPLETED" \
    > "${STUB_PACKER_OUTPUT}"
  run_smoke 42 9 x86_64 prod rocky
  assert_eq "exit code" 0 "${smoke_rc}"
  assert_calls_count "^image add-label --overwrite 42 smoke=passed" 1
  assert_calls_count "^image delete" 0
  echo "PASS ${scenario}"

  echo "smoke-run guard test OK"
}

main "$@"
