#!/usr/bin/env bash
# Promote a smoke-passed candidate snapshot: flip its role label to the
# promoted role the consumers and the refresh lineage selector pick up, and
# record smoke=passed. `add-label --overwrite` replaces the candidate role
# value in place, so no remove-label pass is needed.
#
# Retried: a transient label-API failure must never leave a smoke-passed
# snapshot unpromoted, and this script NEVER deletes a snapshot (deleting a
# failed candidate is the caller's job, before promotion is ever attempted).
#
# Usage (HCLOUD_TOKEN in env): promote.sh <snapshot_id> [prod|test]
set -euo pipefail

snapshot_id="${1:?usage: promote.sh <snapshot_id> [prod|test]}"
factory_env="${2:-prod}"

case "${factory_env}" in
  prod)
    role_promoted="ppg-package-test"
    role_candidate="ppg-candidate"
    ;;
  test)
    role_promoted="ppg-test-package-test"
    role_candidate="ppg-test-candidate"
    ;;
  *)
    echo "usage: promote.sh <snapshot_id> [prod|test]" >&2
    exit 1
    ;;
esac

# Fail-closed identity guard, BEFORE any label change: promote only an image
# that is a candidate of the requested env. A wrong role or a factory_env
# mismatch aborts with no changes, because promoting across envs would leak a
# test bake into the production selector (and vice versa).
image_json=$(hcloud image describe "${snapshot_id}" -o json)
read -r image_role image_env <<< "$(python3 -c '
import json, sys
labels = json.load(sys.stdin).get("labels") or {}
print(labels.get("role", "-"), labels.get("factory_env", "-"))
' <<< "${image_json}")"
image_role="${image_role/#-/}"
image_env="${image_env/#-/}"
mismatch=""

if [[ "${image_role}" != "${role_candidate}" ]]; then
  mismatch="${mismatch} role=${image_role:-<missing>} (want ${role_candidate})"
fi

if [[ "${image_env}" != "${factory_env}" ]]; then
  mismatch="${mismatch} factory_env=${image_env:-<missing>} (want ${factory_env})"
fi

if [[ -n "${mismatch}" ]]; then
  echo "FATAL: refusing to promote ${snapshot_id} for env=${factory_env}:${mismatch} (no labels changed)" >&2
  exit 1
fi

for attempt in 1 2 3 4 5; do
  if hcloud image add-label --overwrite "${snapshot_id}" \
    "role=${role_promoted}" "smoke=passed"; then
    echo "promoted ${snapshot_id} -> role=${role_promoted}"
    exit 0
  fi
  if [[ "${attempt}" -lt 5 ]]; then
    echo "promote attempt ${attempt} failed; retry in $((attempt * 5))s"
    sleep $((attempt * 5))
  fi
done

echo "FATAL: promote ${snapshot_id} -> role=${role_promoted} failed after retries (snapshot left intact for manual promote)" >&2
exit 1
