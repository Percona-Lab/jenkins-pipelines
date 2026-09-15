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
#          PINS_FILE - the consumer's static pins on master (required when
#                      APPLY=1, defaults to ../../vars/moleculeEnvPPG.groovy
#                      for listing); this combo's pin and everything newer
#                      survive, and no pinned id of any combo is ever pruned
#          PROTECT_FILES - colon-separated pins files of open refresh PRs;
#                      an id named there is never pruned either
#
# Fail-safe: only exact role-tag matches with the right NATIVE architecture
# are candidates (demoted `*-superseded` roles and mistagged AMIs are never
# seen), the newest KEEP_N always survive, the consumer's pin for the combo and
# every AMI newer than it survive (the pins are static and lag the newest bake
# until the weekly refresh PR merges, and an open refresh PR may already name
# a newer image), and a describe failure, an unparseable pins file, or a pin
# that is not a candidate aborts rather than treating images as absent. An apply run exits non-zero when any
# prune failed or the combo is still over KEEP_N, so the GHA step and the
# justfile combo counter see the failure. Ordering is computed locally from a
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

# APPLY=1 must name the pins file explicitly: the working-tree default is fine
# for listing, but a checkout ahead of master would raise the floor above
# master's pin and let it be deregistered.
if [[ "${APPLY}" == 1 && -z "${PINS_FILE:-}" ]]; then
  err "PINS_FILE must be set explicitly (master's vars/moleculeEnvPPG.groovy) when APPLY=1"
  exit 1
fi

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
# global newest-first order. The owner always sees its deprecated AMIs, so
# --include-deprecated changes nothing here and is kept only to state the
# intent: images past deprecate_at are pruned too. Native Architecture rides
# along so a mistagged AMI is excluded AND counted from the same data.
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

# The promote tag can lag the describe by a few seconds. Re-list a bounded
# number of times before treating a not-yet-visible promote as an error.
if [[ -n "${PROMOTED_AMI:-}" ]]; then
  for attempt in 1 2 3 4 5 6; do
    [[ "${ami_ids[0]}" == "${PROMOTED_AMI}" ]] && break
    echo "    just-promoted ${PROMOTED_AMI} not yet the newest visible match (attempt ${attempt}), waiting 10s"
    sleep 10
    candidate_rows=$(aws ec2 describe-images "${PROFILE_ARGS[@]}" --region "${REGION}" \
      --owners self --include-deprecated --output text \
      --filters Name=tag:role,Values="${ROLE}" Name=tag:os,Values="${OS_NAME}" \
                Name=tag:os_major,Values="${OS_MAJOR}" Name=tag:arch,Values="${NATIVE_ARCH}" \
                Name=state,Values=available \
      --query 'Images[].[CreationDate, ImageId, Architecture]') \
      || { summary "::error::${COMBO}: describe-images failed, refusing to prune blind"; exit 1; }
    sorted_rows=$(sort -r <<< "${candidate_rows}")
    mapfile -t ami_ids < <(awk -v native_arch="${NATIVE_ARCH}" '$3 == native_arch {print $2}' <<< "${sorted_rows}")
  done
  if [[ "${#ami_ids[@]}" -eq 0 || "${ami_ids[0]}" != "${PROMOTED_AMI}" ]]; then
    summary "::error::${COMBO}: newest visible AMI ${ami_ids[0]:-none} != just-promoted ${PROMOTED_AMI} (eventual consistency or arch mistag?), refusing to prune"
    exit 1
  fi
fi

if [[ "${#ami_ids[@]}" -le "${KEEP_N}" ]]; then
  summary "${COMBO}: ${#ami_ids[@]} AMI(s) <= keep ${KEEP_N}, nothing to prune"
  exit 0
fi

summary "${COMBO}: ${#ami_ids[@]} ${ROLE} AMIs, keep newest ${KEEP_N}"

# Retention floor. For the production role the consumer's pin for THIS combo
# is the floor: the pin itself and every AMI newer than it survive, because
# any newer image is a candidate for the next pin (an open refresh PR may
# already name one). Only images older than the pin are superseded. Each of
# the 12 OL/Rocky pins must parse exactly once, otherwise the file moved or is
# ambiguous (the consumer shell takes the last duplicate) and pruning blind
# could delete the live images. A pin that is not among the candidates
# (deregistered, retagged) makes the floor unknowable, so that combo refuses.
# No pinned id of any combo and no id named by an open refresh PR
# (PROTECT_FILES) is ever pruned, whichever combo it shows up in. The test
# role has no consumer pins and keeps plain KEEP_N retention.
PINS_FILE="${PINS_FILE:-../../vars/moleculeEnvPPG.groovy}"
floor="${KEEP_N}"
pin_index=-1
combo_pin=""
declare -A never_prune

if [[ "${ROLE}" == "ppg-package-test" ]]; then
  for var_prefix in ol rocky; do
    for pin_major in 8 9 10; do
      for pin_arch in x86_64 arm64; do
        mapfile -t pin_values < <(grep -oE "^ *export ami_${var_prefix}${pin_major}_${pin_arch}=ami-[0-9a-f]+" "${PINS_FILE}" 2>/dev/null | sed -E 's/.*=//' || true)
        if [[ "${#pin_values[@]}" -ne 1 ]]; then
          summary "::error::${COMBO}: ami_${var_prefix}${pin_major}_${pin_arch} found ${#pin_values[@]} times in ${PINS_FILE}, expected once, refusing to prune"
          exit 1
        fi
        never_prune["${pin_values[0]}"]="pinned on master"
      done
    done
  done

  pin_prefix=rocky
  [[ "${OS_NAME}" == oraclelinux ]] && pin_prefix=ol
  pin_var="ami_${pin_prefix}${OS_MAJOR}_${NATIVE_ARCH}"
  combo_pin=$(grep -oE "^ *export ${pin_var}=ami-[0-9a-f]+" "${PINS_FILE}" | sed -E 's/.*=//' | head -1 || true)

  if [[ -z "${combo_pin}" ]]; then
    summary "::error::${COMBO}: no ${pin_var} pin in ${PINS_FILE}, refusing to prune"
    exit 1
  fi

  for i in "${!ami_ids[@]}"; do
    if [[ "${ami_ids[${i}]}" == "${combo_pin}" ]]; then
      pin_index=${i}
      break
    fi
  done

  if (( pin_index < 0 )); then
    summary "::error::${COMBO}: pinned ${combo_pin} is not among the ${ROLE} candidates (deregistered or retagged?), refusing to prune"
    exit 1
  fi

  # Keep everything down to and including the pin, and never fewer than KEEP_N.
  if (( pin_index + 1 > floor )); then
    floor=$(( pin_index + 1 ))
  fi

  IFS=':' read -r -a protect_files <<< "${PROTECT_FILES:-}"
  for protect_file in "${protect_files[@]}"; do
    [[ -z "${protect_file}" ]] && continue
    if [[ ! -r "${protect_file}" ]]; then
      summary "::error::${COMBO}: PROTECT_FILES entry ${protect_file} is not readable, refusing to prune"
      exit 1
    fi
    while read -r protected_id; do
      [[ -z "${protected_id}" ]] && continue
      never_prune["${protected_id}"]="${never_prune[${protected_id}]:-named by an open refresh PR}"
    done < <(grep -oE '^ *export ami_(ol|rocky)[0-9]+_(x86_64|arm64)=ami-[0-9a-f]+' "${protect_file}" | sed -E 's/.*=//' || true)
  done
fi

keep_ids=("${ami_ids[@]:0:${floor}}")
prune_ids=("${ami_ids[@]:${floor}}")

for i in "${!keep_ids[@]}"; do
  ami="${keep_ids[${i}]}"
  if [[ "${ami}" == "${combo_pin}" ]]; then
    echo "    KEEP ${ami} (pinned in ${PINS_FILE##*/})"
  elif (( pin_index >= 0 && i < pin_index )); then
    echo "    KEEP ${ami} (newer than the pin)"
  else
    echo "    KEEP ${ami} (newest ${KEEP_N})"
  fi
done

# A protected id below the floor means a mis-tag or a stale refresh PR that
# must be closed first: refuse the whole combo rather than prune around it.
for ami in "${prune_ids[@]}"; do
  if [[ -n "${never_prune[${ami}]:-}" ]]; then
    summary "::error::${COMBO}: ${ami} is below the floor but ${never_prune[${ami}]}, refusing to prune this combo"
    exit 1
  fi
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
    [[ -z "${snapshot_result}" || "${snapshot_result}" == "None" ]] && continue
    # skipped (snapshot still used by another AMI) and missing (already gone)
    # are benign outcomes, only error codes count as failures.
    case "${snapshot_result}" in
      *skipped*|*missing*)
        echo "    snapshot for ${ami}: ${snapshot_result}"
        ;;
      *)
        summary "::warning::${COMBO}: snapshot cleanup for ${ami}: ${snapshot_result}"
        prune_failures=$((prune_failures + 1))
        ;;
    esac
  done <<< "${snapshot_failures}"
done

# Report, then return the result in the exit status: the GHA step and the
# justfile combo counter consume the exit code, warnings alone reach neither.
# The if-form matters, a bare `(( ... )) && exit 1` as the final command would
# exit 1 when the condition is false. Dry runs stay exit 0 (pruned_count is 0
# there, so residual exceeds KEEP_N whenever candidates exist).
residual=$(( ${#ami_ids[@]} - pruned_count ))
expected_residual="${#keep_ids[@]}"

if [[ "${APPLY}" == 1 ]]; then
  summary "${COMBO}: pruned ${pruned_count}, failures ${prune_failures}, remaining ${residual} (keep ${KEEP_N} plus pinned, expected ${expected_residual})"

  if (( residual > expected_residual )); then
    summary "::warning::${COMBO}: still ${residual} AMIs after pruning (expected ${expected_residual})"
  fi

  if (( prune_failures > 0 || residual > expected_residual )); then
    exit 1
  fi
fi
