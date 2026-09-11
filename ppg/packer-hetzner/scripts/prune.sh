#!/usr/bin/env bash
# Retention for the Hetzner factory snapshots: keep the newest N promoted
# snapshots per os x major x arch combo, and remove stale candidates left by
# runs that died between build and their smoke/promote/delete step. This is
# the PRIMARY retention: Hetzner snapshots have no deprecate_at analog, so
# without this sweep the weekly bakes accumulate unbounded.
#
# Fail-safe: LISTS by default and deletes only on an exact apply=1. Selection
# is exclusively by the factory's own label contract (role + os + os_major +
# arch + source), so a snapshot this factory cannot positively identify is
# never touched, and the newest N promoted snapshots per combo are never
# deleted (they are what the consumers and the refresh lineage select).
#
# Usage (HCLOUD_TOKEN in env): prune.sh [prod|test] [apply] [keep]
#   apply: 0 list-only (default) | 1 delete
#   keep:  promoted snapshots to keep per combo (default 2)
#   STALE_HOURS (env): candidate age threshold in hours (default 24)
set -euo pipefail

factory_env="${1:-prod}"
apply="${2:-0}"
keep="${3:-4}"
stale_hours="${STALE_HOURS:-168}"
unpromoted_stale_hours="${UNPROMOTED_STALE_HOURS:-504}"

case "${factory_env}" in
  prod)
    role_promoted="ppg-package-test"
    role_candidate="ppg-candidate"
    source_label="factory"
    ;;
  test)
    role_promoted="ppg-test-package-test"
    role_candidate="ppg-test-candidate"
    source_label="factory-test"
    ;;
  *)
    echo "usage: prune.sh [prod|test] [apply] [keep]" >&2
    exit 1
    ;;
esac

[[ "${keep}" =~ ^[0-9]+$ && "${keep}" -ge 1 ]] \
  || { echo "keep must be a positive integer (got '${keep}')" >&2; exit 1; }
[[ "${stale_hours}" =~ ^[0-9]+$ ]] \
  || { echo "STALE_HOURS must be an integer (got '${stale_hours}')" >&2; exit 1; }

echo "prune (env=${factory_env}, apply=${apply}; lists unless apply=1; keep=${keep} promoted per combo, candidates stale after ${stale_hours}h)"

# Delete on an exact apply=1 only. Any other value lists without deleting.
remove_snapshot() {
  local snapshot_id="$1" reason="$2"
  if [[ "${apply}" = "1" ]]; then
    echo "  delete ${snapshot_id} (${reason})"
    hcloud image delete "${snapshot_id}"
  else
    echo "  would delete ${snapshot_id} (${reason})"
  fi
}

# Superseded promoted snapshots: per combo, keep the newest ${keep} (server-
# side created:desc sort) and remove the rest. The full-combo selector is the
# "don't delete what you can't identify" guard: only snapshots carrying every
# factory label can ever match.
for os_name in rocky almalinux oraclelinux; do
  for os_major in 8 9 10; do
    for arch in x86_64 arm64; do
      selector="role=${role_promoted},os=${os_name},os_major=${os_major},arch=${arch},source=${source_label}"
      superseded=$(hcloud image list -t snapshot -l "${selector}" -s created:desc \
        -o noheader -o columns=id | tail -n "+$((keep + 1))")
      while read -r snapshot_id; do
        [[ -z "${snapshot_id}" ]] && continue
        remove_snapshot "${snapshot_id}" "${os_name}${os_major} ${arch} superseded promoted"
      done <<< "${superseded}"
    done
  done
done

# Stale candidates, two tiers. A candidate normally lives minutes (build ->
# smoke -> promote or delete), so an unlabeled one older than the short
# threshold is debris from a run that died in between (the age floor keeps
# the sweep from racing an in-flight bake). A smoke-passed candidate whose
# promote failed transiently is promote-retry material and gets the LONG
# threshold instead: spared long enough for a human retry, but not immortal.
list_stale() {
  local selector="$1"
  local threshold_hours="$2"

  hcloud image list -t snapshot -l "${selector}" -o json \
    | python3 -c '
import datetime, json, sys
threshold = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(hours=int(sys.argv[1]))
for image in json.load(sys.stdin) or []:
    created = datetime.datetime.fromisoformat(image["created"].replace("Z", "+00:00"))
    if created < threshold:
        print(image["id"])
' "${threshold_hours}"
}

stale_candidates=$(list_stale "role=${role_candidate},source=${source_label},smoke!=passed" "${stale_hours}")

while read -r snapshot_id; do
  [[ -z "${snapshot_id}" ]] && continue
  remove_snapshot "${snapshot_id}" "stale candidate, older than ${stale_hours}h"
done <<< "${stale_candidates}"

unpromoted_passed=$(list_stale "role=${role_candidate},source=${source_label},smoke=passed" "${unpromoted_stale_hours}")

while read -r snapshot_id; do
  [[ -z "${snapshot_id}" ]] && continue
  remove_snapshot "${snapshot_id}" "smoke-passed but unpromoted for ${unpromoted_stale_hours}h"
done <<< "${unpromoted_passed}"

echo "prune done (env=${factory_env}, apply=${apply})"
