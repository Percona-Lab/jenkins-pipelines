#!/usr/bin/env bash
# Post-promote AMI prune - keep the newest KEEP_N promoted bases per
# (os_major, arch) combo, deregister the rest with their snapshots.
# Shared by the GHA workflow (auto-prune after each promote) and the
# justfile `prune-superseded` recipe. deprecate_at only MARKS an AMI,
# it never deletes, so without this the inventory grows every bake.
#
#   Usage: prune-superseded.sh ROLE OS_MAJOR ARCH KEEP_N APPLY REGION [PROFILE]
#     APPLY=1 deregisters. Anything else lists only (dry run).
#   Env:   OS_NAME (default oraclelinux)
#          PROMOTED_AMI - refuse to prune unless it is the newest match
#
# Fail-safe: only exact role-tag matches with the right NATIVE architecture
# are candidates (demoted `*-superseded` roles and mistagged AMIs are never
# seen), the newest KEEP_N always survive, and a describe failure aborts
# rather than treating images as absent. Ordering is computed locally from a
# plain projection because the CLI applies --query per page; the only loops
# are per-AMI mutations, which have no bulk API.
set -euo pipefail

# Native throttle handling on every call; warn-and-continue below is the
# after-retries fallback, not the first line of defense.
export AWS_RETRY_MODE="${AWS_RETRY_MODE:-standard}"
export AWS_MAX_ATTEMPTS="${AWS_MAX_ATTEMPTS:-8}"

err() {
  echo "$*" >&2
}

# Report lines go to stdout AND the workflow step summary when present.
summary() {
  echo "$*"

  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "$*" >> "${GITHUB_STEP_SUMMARY}"
  fi
}

# Arguments + preflight: refuse to run half-armed.
ROLE="${1:?ROLE}"
OS_MAJOR="${2:?OS_MAJOR}"
ARCH="${3:?ARCH}"
KEEP_N="${4:?KEEP_N}"
APPLY="${5:?APPLY (1=delete, else list)}"
REGION="${6:?REGION}"
OS_NAME="${OS_NAME:-oraclelinux}"

PROFILE_ARGS=()
[[ -n "${7:-}" ]] && PROFILE_ARGS=(--profile "$7")

command -v aws >/dev/null || { err "missing required tool: aws"; exit 1; }

# KEEP_N=0 would deregister the live base the consumer resolves to. The
# integer check also makes KEEP_N safe to interpolate into --query below.
if ! [[ "${KEEP_N}" =~ ^[0-9]+$ ]] || (( KEEP_N < 1 )); then
  err "KEEP_N must be a positive integer, got '${KEEP_N}'"
  exit 1
fi

# The EC2 native Architecture token is arm64; accept the aarch64 vocabulary too.
NATIVE_ARCH="${ARCH/aarch64/arm64}"

# Candidate listing: ONE plain-projection fetch, sorted locally. The CLI
# applies --query independently to each page of a paginated response, so a
# server-side sort or length() is only page-local and can misorder the list
# (deleting the wrong rollback) or emit multiple counts. ISO-8601 CreationDate
# sorts lexicographically, so a plain `sort -r` of the aggregated rows is the
# global newest-first order. --include-deprecated keeps AMIs past deprecate_at
# visible so they are pruned too. Native Architecture rides along so a
# mistagged AMI is excluded AND counted from the same data.
candidate_rows=$(aws ec2 describe-images "${PROFILE_ARGS[@]}" --region "${REGION}" \
  --owners self --include-deprecated --output text \
  --filters Name=tag:role,Values="${ROLE}" Name=tag:os,Values="${OS_NAME}" \
            Name=tag:os_major,Values="${OS_MAJOR}" Name=tag:arch,Values="${ARCH}" \
            Name=state,Values=available \
  --query 'Images[].[CreationDate, ImageId, Architecture]') \
  || { err "FATAL: describe-images failed for OL${OS_MAJOR} ${ARCH} (refusing to prune blind)"; exit 1; }

sorted_rows=$(sort -r <<< "${candidate_rows}")

mapfile -t ami_ids < <(awk -v native_arch="${NATIVE_ARCH}" '$3 == native_arch {print $2}' <<< "${sorted_rows}")

mismatched_arch=$(awk -v native_arch="${NATIVE_ARCH}" '$2 != "" && $3 != native_arch' <<< "${sorted_rows}" | grep -c . || true)

if (( mismatched_arch > 0 )); then
  summary "::warning::OL${OS_MAJOR} ${ARCH}: ${mismatched_arch} AMI(s) tagged arch=${ARCH} but native Architecture differs, excluded from pruning"
fi

# Guards. An empty estate right after a promote is an invariant failure when
# PROMOTED_AMI is known (the just-promoted AMI must be visible): warn loudly
# and fail so filter drift can never silently disable pruning. Without
# PROMOTED_AMI (ad-hoc runs) empty stays reportable but benign.
if [[ "${#ami_ids[@]}" -eq 0 ]]; then
  if [[ -n "${PROMOTED_AMI:-}" ]]; then
    summary "::warning::OL${OS_MAJOR} ${ARCH}: just-promoted ${PROMOTED_AMI} is not visible in the candidate list (filter drift or eventual consistency), refusing to treat empty as success"
    exit 1
  fi

  summary "OL${OS_MAJOR} ${ARCH}: no ${ROLE} AMIs found (unexpected after a promote)"
  exit 0
fi

if [[ -n "${PROMOTED_AMI:-}" ]] && [[ "${ami_ids[0]}" != "${PROMOTED_AMI}" ]]; then
  err "FATAL: newest visible AMI ${ami_ids[0]} != just-promoted ${PROMOTED_AMI} (eventual consistency?), refusing to prune"
  exit 1
fi

if [[ "${#ami_ids[@]}" -le "${KEEP_N}" ]]; then
  summary "OL${OS_MAJOR} ${ARCH}: ${#ami_ids[@]} AMI(s) <= keep ${KEEP_N}, nothing to prune"
  exit 0
fi

summary "OL${OS_MAJOR} ${ARCH}: ${#ami_ids[@]} ${ROLE} AMIs, keep newest ${KEEP_N}"

# Keep/prune split by array slice; the newest KEEP_N are untouchable.
keep_ids=("${ami_ids[@]:0:${KEEP_N}}")
prune_ids=("${ami_ids[@]:${KEEP_N}}")

for ami in "${keep_ids[@]}"; do
  echo "    KEEP ${ami}"
done

# Prune loop: warn-and-continue. One failed call (after native retries) must
# not abort the remaining AMIs (or, via the caller loop, the remaining
# combos). Snapshot cleanup is native (--delete-associated-snapshots). Any
# non-success snapshot result is surfaced instead of silently orphaning.
prune_failures=0
pruned_count=0

for ami in "${prune_ids[@]}"; do
  if [[ "${APPLY}" != 1 ]]; then
    echo "    would deregister ${ami} (superseded)"
    continue
  fi

  if ! snapshot_failures=$(aws ec2 deregister-image "${PROFILE_ARGS[@]}" --region "${REGION}" \
      --image-id "${ami}" --delete-associated-snapshots --output text \
      --query "DeleteSnapshotResults[?ReturnCode != 'success'].[SnapshotId, ReturnCode]"); then
    summary "::warning::OL${OS_MAJOR} ${ARCH}: deregister ${ami} failed, continuing"
    prune_failures=$((prune_failures + 1))
    continue
  fi

  echo "    deregistered ${ami} (superseded)"
  pruned_count=$((pruned_count + 1))

  while IFS= read -r snapshot_result; do
    [[ -z "${snapshot_result}" ]] && continue
    summary "::warning::OL${OS_MAJOR} ${ARCH}: snapshot cleanup for ${ami}: ${snapshot_result}"
    prune_failures=$((prune_failures + 1))
  done <<< "${snapshot_failures}"
done

# Report: counts always, a loud warning when the combo is still over keep
# (the signal that the filter drifted or failures piled up).
residual=$(( ${#ami_ids[@]} - pruned_count ))

if [[ "${APPLY}" == 1 ]]; then
  summary "OL${OS_MAJOR} ${ARCH}: pruned ${pruned_count}, failures ${prune_failures}, remaining ${residual} (keep ${KEEP_N})"

  if (( residual > KEEP_N )); then
    summary "::warning::OL${OS_MAJOR} ${ARCH}: still ${residual} AMIs after pruning (over keep ${KEEP_N})"
  fi
fi
