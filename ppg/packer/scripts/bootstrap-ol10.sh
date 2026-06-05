#!/usr/bin/env bash
# OL10 bootstrap - import Oracle's official OL10 cloud image, then
# Packer-finalize it into the consumable lineage-root base (one-time per arch;
# OL10 is greenfield - no base to refresh from). Validated both arches 2026-06-04.
#
# Flow:  download official image -> streamOptimized VMDK -> S3 -> import-snapshot
#        -> register RAW base -> `packer build finalize-ol10.pkr.hcl`
# The import + register are AWS CLI (Packer cannot import-snapshot); the FINALIZE
# (ec2-user + amazon-ssm-agent + dnf update) and the AMI/snapshot TAGS are owned by
# Packer (bootstrap/finalize-ol10.pkr.hcl) - consistent + reproducible with the
# refresh template. Promote with: just bootstrap-ol10-verify <candidate-ami> <arch>.
#
# Per-arch source (Oracle publishes a DIFFERENT image per arch):
#   arm64  : OL10U1_aarch64-kvm-cloud-*.qcow2  -> UEFI       (VM Import rejects arm64,
#                                                            so import-snapshot a VMDK)
#   x86_64 : OL10U1_x86_64-aws-*.vmdk          -> Legacy BIOS (AWS-purpose-built image)
# Bump the build (bNNN) as Oracle republishes: yum.oracle.com/oracle-linux-templates.html
#
#   Usage:  ARCH=arm64 ./bootstrap-ol10.sh    |    ARCH=x86_64 ./bootstrap-ol10.sh
# Prereqs (one-time, via `just bootstrap-ol10-prep`): import bucket + ppg-ol10-vmimport role.
#
# Idempotent + resumable: a re-run detects an already-promoted base (or a reusable raw/
# candidate AMI, or the in-S3 source / a completed import) and skips the finished steps; it
# will NOT re-download the 37GB image. FORCE=1 rebuilds the AMI lineage from the existing S3
# source (still no re-download; delete the S3 object first to refresh the Oracle source).
set -euo pipefail

ARCH="${ARCH:?set ARCH=arm64|x86_64}"
REGION="${REGION:-eu-central-1}"
BUCKET="${BUCKET:-ppg-ami-factory-import-$(aws sts get-caller-identity --query Account --output text 2>/dev/null)}"  # in $REGION; justfile passes this, default derives the account
VMIMPORT_ROLE="${VMIMPORT_ROLE:-ppg-ol10-vmimport}"        # scoped to $BUCKET
# Factory identity (overridable; the justfile passes these so it stays the single source of truth).
BILLING_TAG="${BILLING_TAG:-ppg-ami-factory}"
OS_NAME="${OS_NAME:-oraclelinux}"
ROLE_PROMOTED="${ROLE_PROMOTED:-ppg-package-test}"      # what verify promotes to + the consumer selects
ROLE_CANDIDATE="${ROLE_CANDIDATE:-ppg-ol10-candidate}"  # MUST match finalize-ol10.pkr.hcl candidate_role (prod)
ROLE_RAW="${ROLE_RAW:-ppg-ol10-raw}"                    # tag on the intermediate raw base (this script owns it)
WORK="${WORK:-$(pwd)/ol10-import}"; mkdir -p "$WORK"
HERE="$(cd "$(dirname "$0")/.." && pwd)"                   # ppg/packer (finalize lives in bootstrap/)
TS="$(date -u +%Y%m%d-%H%M%S)"; say(){ echo -e "\n=== $* ==="; }

case "$ARCH" in
  arm64)  EC2ARCH=arm64;  BOOTMODE=uefi
          SRC_URL="${SRC_URL:-https://yum.oracle.com/templates/OracleLinux/OL10/u1/aarch64/OL10U1_aarch64-kvm-cloud-b154.qcow2}" ;;
  x86_64) EC2ARCH=x86_64; BOOTMODE=legacy-bios
          SRC_URL="${SRC_URL:-https://yum.oracle.com/templates/OracleLinux/OL10/u1/x86_64/OL10U1_x86_64-aws-b275.vmdk}" ;;
  *) echo "ARCH must be arm64 or x86_64" >&2; exit 1 ;;
esac
SRC="$(basename "$SRC_URL")"; VMDK="ol10-${ARCH}.vmdk"

FORCE="${FORCE:-0}"   # FORCE=1 rebuilds the AMI lineage (raw -> candidate) from the EXISTING
                      # S3 source + snapshot; it never re-downloads the 37GB image. To refresh
                      # the Oracle source, delete s3://$BUCKET/$VMDK (and bump bNNN) first.

# Idempotency helpers: newest self-owned, available OL10 image for THIS arch.
# Return an ImageId or empty string. A describe FAILURE (throttle, expired creds) is
# NOT silently treated as "absent" -- that would bypass the guards below and re-trigger
# the 37GB download/import. It retries, then returns non-zero so the callers' `|| exit 1`
# aborts (fail-closed). An empty result means the image is genuinely absent.
_describe_newest() {  # $@ = --filters ...
  local out a
  for a in 1 2 3; do
    if out=$(aws ec2 describe-images --region "$REGION" --owners self "$@" \
              --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text); then
      [ "$out" = None ] && out=""
      printf '%s' "$out"; return 0
    fi
    sleep $((a * 3))
  done
  echo "FATAL: describe-images failed after 3 attempts (refusing to treat as absent)" >&2
  return 1
}
img_by_tag()  { _describe_newest --filters Name=tag:os_major,Values=10 Name=tag:arch,Values="$ARCH" Name=tag:role,Values="$1" Name=state,Values=available; }
img_by_name() { _describe_newest --filters Name=name,Values="$1" Name=architecture,Values="$EC2ARCH" Name=state,Values=available; }

RAW=""; BASE=""

# 0. Strongest guard: a promoted prod base already exists -> nothing to do. This protects a
#    small-disk host from a re-run that would otherwise attempt the 37GB download below.
PROMOTED=$(img_by_tag "$ROLE_PROMOTED") || exit 1
if [ -n "$PROMOTED" ] && [ "$FORCE" != 1 ]; then
  cat <<EOF

OL10 $ARCH base already built + promoted: $PROMOTED (role=$ROLE_PROMOTED). Nothing to do.
The refresh path (oracle-linux.pkr.hcl, os_major already includes 10) takes over from here.
Re-run with FORCE=1 to rebuild the AMIs from the existing S3 source (no 37GB re-download).
EOF
  exit 0
fi

# 1. Reuse an already-finalized (not-yet-promoted) candidate -> skip download/import/finalize.
CAND=$(img_by_tag "$ROLE_CANDIDATE") || exit 1
if [ -n "$CAND" ] && [ "$FORCE" != 1 ]; then
  say "reuse existing finalized candidate $CAND (skip download/import/finalize)"
  BASE="$CAND"
else
  # 2. Ensure a raw base: reuse an existing OL10-<arch>-raw-* AMI, else import + register.
  RAW=$(img_by_name "OL10-${ARCH}-raw-*") || exit 1
  if [ -n "$RAW" ] && [ "$FORCE" != 1 ]; then
    say "reuse existing raw base $RAW (skip download/convert/upload/import/register)"
  else
    # 2a. Ensure the streamOptimized VMDK is in S3 (NEVER re-download 37GB if it already is).
    if aws s3 ls "s3://$BUCKET/$VMDK" --region "$REGION" >/dev/null 2>&1; then
      say "S3 source present: s3://$BUCKET/$VMDK (skip download/convert/upload)"
    else
      say "download $SRC"
      [ -f "$WORK/$VMDK" ] || [ -f "$WORK/$SRC" ] || curl -fsSL -o "$WORK/$SRC" "$SRC_URL"
      say "convert -> $VMDK (streamOptimized; RAW's full virtual size would be a huge upload)"
      [ -f "$WORK/$VMDK" ] || qemu-img convert -O vmdk -o subformat=streamOptimized "$WORK/$SRC" "$WORK/$VMDK"
      say "upload to s3://$BUCKET/$VMDK"
      aws s3 cp "$WORK/$VMDK" "s3://$BUCKET/$VMDK" --region "$REGION"
    fi

    # 2b. Ensure a completed snapshot: reuse a prior completed import for this source, else import.
    say "import-snapshot (reuse a prior completed import for this source if present)"
    SNAP=$(aws ec2 describe-import-snapshot-tasks --region "$REGION" \
      --query "ImportSnapshotTasks[?SnapshotTaskDetail.Status=='completed' && Description=='PPG OL10 $ARCH base ($SRC)'].SnapshotTaskDetail.SnapshotId | [0]" \
      --output text) || { echo "FATAL: describe-import-snapshot-tasks failed (refusing to import a duplicate)" >&2; exit 1; }
    [ "$SNAP" = None ] && SNAP=""
    if [ -n "$SNAP" ]; then
      echo "  reuse snapshot $SNAP from a prior completed import"
    else
      TASK=$(aws ec2 import-snapshot --region "$REGION" --description "PPG OL10 $ARCH base ($SRC)" \
        --role-name "$VMIMPORT_ROLE" --disk-container "Format=VMDK,UserBucket={S3Bucket=$BUCKET,S3Key=$VMDK}" \
        --query ImportTaskId --output text)
      echo "  task=$TASK ; polling..."
      while :; do
        read -r ST SNAP < <(aws ec2 describe-import-snapshot-tasks --region "$REGION" --import-task-ids "$TASK" \
          --query 'ImportSnapshotTasks[0].SnapshotTaskDetail.[Status,SnapshotId]' --output text)
        echo "  $ST $SNAP"; [ "$ST" = completed ] && break
        case "$ST" in deleted|deleting|error) echo "import FAILED"; exit 1 ;; esac; sleep 30
      done
    fi

    # 2c. Register the raw base + tag it (the idempotency key for step 2 on the next run).
    say "register RAW base (--architecture $EC2ARCH --boot-mode $BOOTMODE)"
    RAW=$(aws ec2 register-image --region "$REGION" --name "OL10-${ARCH}-raw-${TS}" \
      --architecture "$EC2ARCH" --boot-mode "$BOOTMODE" --ena-support --virtualization-type hvm \
      --root-device-name /dev/sda1 \
      --block-device-mappings "DeviceName=/dev/sda1,Ebs={SnapshotId=$SNAP,VolumeType=gp3,DeleteOnTermination=true}" \
      --query ImageId --output text)
    aws ec2 create-tags --region "$REGION" --resources "$RAW" "$SNAP" --tags \
      Key=os,Value="$OS_NAME" Key=os_major,Value=10 Key=arch,Value="$ARCH" Key=role,Value="$ROLE_RAW" \
      Key=iit-billing-tag,Value="$BILLING_TAG"
    echo "  raw base=$RAW"
  fi

  # 3. Packer finalize (ec2-user + amazon-ssm-agent + dnf update; Packer owns the AMI/snapshot tags).
  say "Packer finalize (role=ppg-ol10-candidate until promotion)"
  ( cd "$HERE/bootstrap" && packer init . >/dev/null && \
    packer build -color=false -var raw_ami="$RAW" -var arch="$ARCH" -var region="$REGION" . )
  BASE=$(python3 -c "import json;print(json.load(open('$HERE/bootstrap/manifest.json'))['builds'][-1]['artifact_id'].split(':')[-1])" 2>/dev/null || echo "")
fi

cat <<EOF

BOOTSTRAP-OL10 ($ARCH) COMPLETE.
  raw base (intermediate): ${RAW:-<reused candidate; no new raw>}
  candidate base:          ${BASE:-<see packer output above>}  (role=$ROLE_CANDIDATE, Packer-tagged)
NEXT: just bootstrap-ol10-verify ${BASE:-<candidate-ami>} $ARCH   # boot-validate + promote to role=$ROLE_PROMOTED
Then OL10 refreshes here like OL8/9 (oracle-linux.pkr.hcl os_major already includes 10).
EOF
