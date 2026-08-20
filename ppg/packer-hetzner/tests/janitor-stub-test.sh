#!/bin/bash
# Stubbed behavior test for scripts/janitor.sh's SSH-key sweep: old keys
# carrying the factory/smoke role labels (Packer's ssh_keys_labels) are listed
# and deleted, young keys are skipped as live runs, and a key matching no
# selector is never even queried. Runs against tests/stubs (fake hcloud), so
# no token and no API. Executed by scripts/check.sh.
set -euo pipefail

err() {
  echo "$*" >&2
}

factory_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly factory_root

test_root=$(mktemp -d -t ppg-janitor-stub-XXXXXX)
trap 'rm -rf "${test_root}"' EXIT

export STUB_CALL_LOG="${test_root}/calls.log"
export STUB_FIXTURE_DIR="${test_root}/fixtures"
mkdir -p "${STUB_FIXTURE_DIR}"

# hcloud-shaped created timestamps relative to now, so the fixtures age
# correctly no matter when the test runs.
created_at() {
  python3 -c '
import datetime, sys
delta = datetime.timedelta(hours=int(sys.argv[1]))
print((datetime.datetime.now(datetime.timezone.utc) - delta).isoformat())
' "$1"
}

# Factory-builder keys: one leaked (10h), one from a live run (1h). Smoke
# keys: one leaked (8h). Key 999 carries no selector-matching role, so no
# fixture returns it and the janitor must never touch it.
cat > "${STUB_FIXTURE_DIR}/ssh-keys-role_ppg-factory-builder.json" <<EOF
[
  {"id": 101, "name": "packer-leaked-factory", "created": "$(created_at 10)"},
  {"id": 102, "name": "packer-live-factory", "created": "$(created_at 1)"}
]
EOF
cat > "${STUB_FIXTURE_DIR}/ssh-keys-role_ppg-smoke.json" <<EOF
[
  {"id": 103, "name": "packer-leaked-smoke", "created": "$(created_at 8)"}
]
EOF

run_janitor() {
  janitor_rc=0
  janitor_output=$(cd "${factory_root}" \
    && PATH="${factory_root}/tests/stubs:${PATH}" \
      bash scripts/janitor.sh "$@" 2>&1) || janitor_rc=$?
}

assert_eq() {
  local what="$1" want="$2" got="$3"

  if [[ "${got}" != "${want}" ]]; then
    err "FAIL ${mode}: ${what}: want '${want}', got '${got}'"
    err "--- output ---"
    err "${janitor_output}"
    err "--- calls ---"
    cat "${STUB_CALL_LOG}" >&2
    exit 1
  fi
}

assert_output_has() {
  assert_eq "output contains '$1'" "yes" \
    "$(grep -qF -- "$1" <<< "${janitor_output}" && echo yes || echo no)"
}

assert_calls_count() {
  local pattern="$1" want="$2"
  assert_eq "calls matching '${pattern}'" "${want}" \
    "$(grep -c -- "${pattern}" "${STUB_CALL_LOG}" || true)"
}

main() {
  mode="list"
  : > "${STUB_CALL_LOG}"
  run_janitor
  assert_eq "exit code" 3 "${janitor_rc}"
  assert_output_has "would delete ssh-key 101"
  assert_output_has "would delete ssh-key 103"
  assert_output_has "leaked=2"
  assert_calls_count "delete" 0
  echo "PASS janitor ${mode} mode"

  mode="apply"
  : > "${STUB_CALL_LOG}"
  run_janitor 1
  assert_eq "exit code" 0 "${janitor_rc}"
  assert_output_has "deleted=2"
  assert_output_has "delete_failures=0"
  assert_calls_count "^ssh-key delete 101$" 1
  assert_calls_count "^ssh-key delete 103$" 1
  assert_calls_count "delete 102" 0
  assert_calls_count "999" 0
  echo "PASS janitor ${mode} mode"

  # The sweep must query EXACTLY the contracted selectors: a lost selector
  # here is an invisible leak class in production.
  mode="selector-contract"
  assert_eq "ssh-key selectors queried" \
    "role=ppg-bootstrap-check role=ppg-factory-builder role=ppg-molecule-test role=ppg-smoke" \
    "$(awk '/^ssh-key list -l /{print $4}' "${STUB_CALL_LOG}" \
      | sort -u | xargs)"
  echo "PASS janitor ${mode}"

  echo "janitor stub test OK"
}

main "$@"
