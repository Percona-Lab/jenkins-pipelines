#!/usr/bin/env bash
# Janitor for LEAKED Hetzner Cloud resources from the PPG factory and the
# molecule package-test runs: servers a crashed bake/smoke/test left behind,
# and the per-run SSH keys the molecule create playbook uploads.
#
# Selection is exclusively by the label contracts these producers set, so a
# resource this janitor cannot positively identify is never touched:
#   servers:  role=ppg-factory-builder (refresh.pkr.hcl), role=ppg-smoke
#             (smoke/smoke.pkr.hcl), role=ppg-bootstrap-check
#             (bootstrap/bootstrap-base.sh), role=ppg-molecule-test
#             (ppg-testing playbooks/create-hetzner.yml)
#   ssh keys: the same four roles. Packer's temporary per-build keys carry
#             role=ppg-factory-builder / role=ppg-smoke via ssh_keys_labels in
#             the two templates; the bootstrap and molecule producers label
#             the keys they upload themselves.
# ppg_run (the molecule per-run label) is never a selector: it is a generic-
# looking key another producer could plausibly use, and role=ppg-molecule-test
# already covers everything that carries it.
#
# Age-bound: anything younger than the threshold is treated as a live run and
# skipped (bakes finish in ~30 min, but long molecule suites can run past 2h,
# hence the conservative 6h default), so the sweep never races in-flight work.
# Resources without a created timestamp are never touched.
#
# Fail-safe: LISTS by default and deletes only on an exact apply=1.
#
# Usage (HCLOUD_TOKEN in env): janitor.sh [apply] [max_age_hours]
#   apply:         0 list-only (default) | 1 delete
#   max_age_hours: age threshold in hours (default 6)
# Exit codes: 0 clean list or fully successful apply (prints deleted=<n> and
#             delete_failures=<n>), 3 list mode found leaks (prints
#             leaked=<n>), 1 apply had failed deletes, else a real failure
set -euo pipefail

readonly SERVER_SELECTORS=(
  'role=ppg-factory-builder'
  'role=ppg-smoke'
  'role=ppg-bootstrap-check'
  'role=ppg-molecule-test'
)
readonly SSH_KEY_SELECTORS=(
  'role=ppg-factory-builder'
  'role=ppg-smoke'
  'role=ppg-bootstrap-check'
  'role=ppg-molecule-test'
)

apply="${1:-0}"
max_age_hours="${2:-6}"
leaked_count=0
delete_failures=0

[[ "${max_age_hours}" =~ ^[0-9]+$ ]] \
  || { echo "max_age_hours must be an integer (got '${max_age_hours}')" >&2; exit 1; }

# stdin: hcloud JSON array. stdout: "id<TAB>name<TAB>age" for entries older
# than the threshold. Entries without a created timestamp are dropped (never
# delete what cannot be age-classified).
filter_older_than() {
  python3 -c '
import datetime, json, sys
threshold = datetime.timedelta(hours=int(sys.argv[1]))
now = datetime.datetime.now(datetime.timezone.utc)
for item in json.load(sys.stdin) or []:
    created_raw = item.get("created")
    if not created_raw:
        continue
    created = datetime.datetime.fromisoformat(created_raw.replace("Z", "+00:00"))
    age = now - created
    if age > threshold:
        age_hours = age.total_seconds() / 3600
        print("\t".join([str(item["id"]), item.get("name") or "-", "%.1fh" % age_hours]))
' "${max_age_hours}"
}

# Delete on an exact apply=1 only. Any other value lists without deleting.
# The delete itself is guarded: one failed delete (API blip, a protected
# resource) must not abort the sweep before the remaining leaks are attempted.
# Failures are counted and fail the run at the end.
remove_resource() {
  local kind="$1" resource_id="$2" reason="$3"
  leaked_count=$((leaked_count + 1))
  if [[ "${apply}" = "1" ]]; then
    echo "  delete ${kind} ${resource_id} (${reason})"
    if ! hcloud "${kind}" delete "${resource_id}"; then
      delete_failures=$((delete_failures + 1))
      echo "  FAILED to delete ${kind} ${resource_id} (continuing with the rest)" >&2
    fi
  else
    echo "  would delete ${kind} ${resource_id} (${reason})"
  fi
}

# kind is the hcloud subcommand (server | ssh-key). The remaining args are
# label selectors that positively identify our own resources. Ids are
# deduplicated before acting, so selectors that ever come to overlap cannot
# double-count or double-delete.
sweep() {
  local kind="$1"
  shift
  local -A seen=()
  local selector listing filtered resource_id name age
  echo "${kind} sweep:"
  for selector in "$@"; do
    # Both captures are their own statements so a failing hcloud (or filter)
    # kills the script under set -e: a swallowed listing failure would report
    # a clean sweep over resources that were never actually listed.
    listing=$(hcloud "${kind}" list -l "${selector}" -o json)
    filtered=$(printf '%s' "${listing}" | filter_older_than)
    while IFS=$'\t' read -r resource_id name age; do
      [[ -z "${resource_id}" || -n "${seen[${resource_id}]:-}" ]] && continue
      seen["${resource_id}"]=1
      remove_resource "${kind}" "${resource_id}" "${name}, ${selector}, age ${age}"
    done <<< "${filtered}"
  done
}

main() {
  echo "janitor (apply=${apply}; lists unless apply=1; threshold ${max_age_hours}h)"
  sweep server "${SERVER_SELECTORS[@]}"
  sweep ssh-key "${SSH_KEY_SELECTORS[@]}"

  if [[ "${apply}" = "1" ]]; then
    echo "deleted=$((leaked_count - delete_failures))"
    echo "delete_failures=${delete_failures}"
    echo "janitor done (apply=${apply})"
    # Every delete was ATTEMPTED. Any failure still fails the run so the
    # workflow alerts instead of reporting a clean apply.
    [[ "${delete_failures}" -eq 0 ]] || exit 1
    exit 0
  fi

  echo "leaked=${leaked_count}"
  echo "janitor done (apply=${apply})"
  # Exit 3 signals "leaks listed" distinctly from both a clean run (0) and a
  # real failure (anything else), so the workflow can alert on leaks without
  # treating a dirty list as a broken sweep.
  [[ "${leaked_count}" -eq 0 ]] || exit 3
}

main "$@"
