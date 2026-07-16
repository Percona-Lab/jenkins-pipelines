#!/usr/bin/env bash
# Post-promote AMI prune - keep the newest KEEP_N promoted bases per
# (os, os_major, arch) combo, deregister the rest with their snapshots.
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
    # ::warning::/::error:: are stdout annotation directives; the step summary
    # renders markdown, so strip the directive prefix there.
    local line="$*"
    line="${line#::warning::}"
    line="${line#::error::}"
    echo "${line}" >> "${GITHUB_STEP_SUMMARY}"
  fi
}

# Arguments + preflight: refuse to run half-armed.
ROLE="${1:?ROLE}"
OS_MAJOR="${2:?OS_MAJOR}"
ARCH="${3:?ARCH}"
KEEP_N="${4:?KEEP_N}"
APPLY="${5:?APPLY (1=delete, else list)}"
REGION="${6:?REGION}"
# APPLY=1 must name its OS explicitly: a silent oraclelinux default on a rocky
# cleanup would deregister the wrong OS's rollback generation.
if [[ "${APPLY}" == 1 && -z "${OS_NAME:-}" ]]; then
  err "OS_NAME must be set explicitly (oraclelinux|rocky) when APPLY=1"
  exit 1
fi

OS_NAME="${OS_NAME:-oraclelinux}"

# Exact-match whitelist: a wildcard or comma list would widen the tag:os filter
# and prune across operating systems.
case "${OS_NAME}" in
  oraclelinux|rocky) ;;
  *)
    err "OS_NAME must be oraclelinux or rocky, got '${OS_NAME}'"
    exit 1
    ;;
esac

PROFILE_ARGS=()
[[ -n "${7:-}" ]] && PROFILE_ARGS=(--profile "$7")

command -v aws >/dev/null || { err "missing required tool: aws"; exit 1; }

# KEEP_N=0 would deregister the live base the consumer resolves to, and a
# leading zero would trip Bash's octal arithmetic below. The anchored check
# also makes KEEP_N safe to interpolate into the array slice.
if ! [[ "${KEEP_N}" =~ ^[1-9][0-9]*$ ]]; then
  err "KEEP_N must be a positive integer without leading zeros, got '${KEEP_N}'"
  exit 1
fi

# The EC2 native Architecture token is arm64; accept the aarch64 vocabulary too.
NATIVE_ARCH="${ARCH/aarch64/arm64}"

# Human-facing combo label; oraclelinux and rocky share majors and arches, so
# every message names the full (os, os_major, arch) combo.
COMBO="${OS_NAME} ${OS_MAJOR} ${ARCH}"

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
            Name=tag:os_major,Values="${OS_MAJOR}" Name=tag:arch,Values="${NATIVE_ARCH}" \
            Name=state,Values=available \
  --query 'Images[].[CreationDate, ImageId, Architecture]') \
  || { summary "::error::${COMBO}: describe-images failed, refusing to prune blind"; exit 1; }

sorted_rows=$(sort -r <<< "${candidate_rows}")

mapfile -t ami_ids < <(awk -v native_arch="${NATIVE_ARCH}" '$3 == native_arch {print $2}' <<< "${sorted_rows}")

mismatched_arch=$(awk -v native_arch="${NATIVE_ARCH}" '$2 != "" && $3 != native_arch' <<< "${sorted_rows}" | grep -c . || true)

if (( mismatched_arch > 0 )); then
  summary "::warning::${COMBO}: ${mismatched_arch} AMI(s) tagged arch=${NATIVE_ARCH} but native Architecture differs, excluded from pruning"
fi

# Guards. An empty estate right after a promote is an invariant failure when
# PROMOTED_AMI is known (the just-promoted AMI must be visible): warn loudly
# and fail so filter drift can never silently disable pruning. Without
# PROMOTED_AMI (ad-hoc runs) empty stays reportable but benign.
if [[ "${#ami_ids[@]}" -eq 0 ]]; then
  if [[ -n "${PROMOTED_AMI:-}" ]]; then
    summary "::warning::${COMBO}: just-promoted ${PROMOTED_AMI} is not visible in the candidate list (filter drift or eventual consistency), refusing to treat empty as success"
    exit 1
  fi

  summary "${COMBO}: no ${ROLE} AMIs found"
  exit 0
fi

if [[ -n "${PROMOTED_AMI:-}" ]] && [[ "${ami_ids[0]}" != "${PROMOTED_AMI}" ]]; then
  summary "::error::${COMBO}: newest visible AMI ${ami_ids[0]} != just-promoted ${PROMOTED_AMI} (eventual consistency or arch mistag?), refusing to prune"
  exit 1
fi

if [[ "${#ami_ids[@]}" -le "${KEEP_N}" ]]; then
  summary "${COMBO}: ${#ami_ids[@]} AMI(s) <= keep ${KEEP_N}, nothing to prune"
  exit 0
fi

summary "${COMBO}: ${#ami_ids[@]} ${ROLE} AMIs, keep newest ${KEEP_N}"

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

  deregister_err=$(mktemp)

  if ! snapshot_failures=$(aws ec2 deregister-image "${PROFILE_ARGS[@]}" --region "${REGION}" \
      --image-id "${ami}" --delete-associated-snapshots --output text \
      --query "DeleteSnapshotResults[?ReturnCode != 'success'].[SnapshotId, ReturnCode]" 2>"${deregister_err}"); then
    # A concurrent run (workflow leg + ad-hoc justfile) may have removed the AMI
    # already; that is the desired end state, not a failure. Mirrors _deregister.
    if grep -qiE "InvalidAMIID.NotFound|InvalidAMIID.Unavailable|does not exist" "${deregister_err}"; then
      echo "    ${ami} already gone"
      rm -f "${deregister_err}"
      pruned_count=$((pruned_count + 1))
      continue
    fi

    summary "::warning::${COMBO}: deregister ${ami} failed, continuing"
    cat "${deregister_err}" >&2
    rm -f "${deregister_err}"
    prune_failures=$((prune_failures + 1))
    continue
  fi

  rm -f "${deregister_err}"

  echo "    deregistered ${ami} (superseded)"
  pruned_count=$((pruned_count + 1))

  while IFS= read -r snapshot_result; do
    [[ -z "${snapshot_result}" ]] && continue
    summary "::warning::${COMBO}: snapshot cleanup for ${ami}: ${snapshot_result}"
    prune_failures=$((prune_failures + 1))
  done <<< "${snapshot_failures}"
done

# Report: counts always, a loud warning when the combo is still over keep
# (the signal that the filter drifted or failures piled up).
residual=$(( ${#ami_ids[@]} - pruned_count ))

if [[ "${APPLY}" == 1 ]]; then
  summary "${COMBO}: pruned ${pruned_count}, failures ${prune_failures}, remaining ${residual} (keep ${KEEP_N})"

  if (( residual > KEEP_N )); then
    summary "::warning::${COMBO}: still ${residual} AMIs after pruning (over keep ${KEEP_N})"
  fi
fi
