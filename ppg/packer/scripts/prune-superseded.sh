#!/usr/bin/env bash
# Keep the newest N AMIs for one (os_major, arch) combo of a given role; deregister
# the rest and delete their snapshots. Shared by the GHA workflow (auto-prune after
# each promote) and the justfile `prune-superseded` recipe, so there is one
# implementation. deprecate_at only MARKS an AMI deprecated, it never deletes, so
# without this the inventory grows unbounded as the factory rebakes.
#
#   Usage: prune-superseded.sh ROLE OS_MAJOR ARCH KEEP_N APPLY REGION [PROFILE]
#     APPLY=1 deregisters; anything else lists only (dry run).
#
# Fail-safe by construction: keeps the newest KEEP_N (so the base the consumer +
# the refresh source_ami_filter select is never removed), no-ops when <= KEEP_N
# exist, and a describe failure aborts rather than silently treating images as
# absent. --include-deprecated so AMIs past deprecate_at are still seen and pruned.
set -euo pipefail

ROLE="${1:?ROLE}"; OS_MAJOR="${2:?OS_MAJOR}"; ARCH="${3:?ARCH}"
KEEP_N="${4:?KEEP_N}"; APPLY="${5:?APPLY (1=delete, else list)}"; REGION="${6:?REGION}"
PROFILE_ARGS=(); [ -n "${7:-}" ] && PROFILE_ARGS=(--profile "$7")
OS_NAME="${OS_NAME:-oraclelinux}"

# Newest-first list of this combo's promoted AMIs (id + creation date for logging).
ids=$(aws ec2 describe-images "${PROFILE_ARGS[@]}" --region "$REGION" --owners self --include-deprecated \
  --filters Name=tag:role,Values="$ROLE" Name=tag:os,Values="$OS_NAME" \
            Name=tag:os_major,Values="$OS_MAJOR" Name=tag:arch,Values="$ARCH" Name=state,Values=available \
  --query 'reverse(sort_by(Images,&CreationDate))[].ImageId' --output text) \
  || { echo "FATAL: describe-images failed for OL$OS_MAJOR $ARCH (refusing to prune blind)" >&2; exit 1; }

# `read -a` splits on IFS; aws text output is tab/space separated, "None" when empty.
read -r -a arr <<< "$ids"
[ "${#arr[@]}" -eq 0 ] || [ "${arr[0]}" = None ] && { echo "  OL$OS_MAJOR $ARCH: no $ROLE AMIs"; exit 0; }
if [ "${#arr[@]}" -le "$KEEP_N" ]; then
  echo "  OL$OS_MAJOR $ARCH: ${#arr[@]} AMI(s) <= keep $KEEP_N, nothing to prune"; exit 0
fi

echo "  OL$OS_MAJOR $ARCH: ${#arr[@]} $ROLE AMIs, keep newest $KEEP_N:"
for i in "${!arr[@]}"; do
  ami="${arr[$i]}"
  if [ "$i" -lt "$KEEP_N" ]; then echo "    KEEP $ami"; continue; fi
  if [ "$APPLY" = 1 ]; then
    echo "    deregister $ami (superseded)"
    snaps=$(aws ec2 describe-images "${PROFILE_ARGS[@]}" --region "$REGION" --image-ids "$ami" \
      --query 'Images[0].BlockDeviceMappings[].Ebs.SnapshotId' --output text) || { echo "    WARN describe $ami failed, skip" >&2; continue; }
    aws ec2 deregister-image "${PROFILE_ARGS[@]}" --region "$REGION" --image-id "$ami"
    for s in $snaps; do [ "$s" = None ] && continue; aws ec2 delete-snapshot "${PROFILE_ARGS[@]}" --region "$REGION" --snapshot-id "$s"; done
  else
    echo "    would deregister $ami (superseded)"
  fi
done
