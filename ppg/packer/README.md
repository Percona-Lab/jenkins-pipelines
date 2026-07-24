# PPG EL AMI factory (Oracle Linux + Rocky Linux)

Builds the Oracle Linux and Rocky Linux package-test target AMIs that PG release
testing runs against, so we stop hand-maintaining them (launch base,
`dnf update`, snapshot).

## Why this exists

**Oracle Linux**: there is no off-the-shelf AWS image that is all of: genuine
Oracle Linux, OL8/OL9/OL10, x86_64 **and** arm64, free of software fees, and
maintained.

- Oracle stopped publishing official AWS AMIs. The newest public Oracle images
  (owner `131827586825`) are OL8.9 / OL9.3 from Feb 2024 and were all deprecated
  on 2026-02-20. There is no official OL8.10, OL9.7, or OL10 AMI on AWS.
- Marketplace vendors (ProComputers, SupportedImages) add a per-hour software
  fee, require a per-account subscription that blocks unattended fleet launches,
  and are x86_64-only.
- AlmaLinux is free + current + both-arch, but it is an EL clone, fine as
  supplemental smoke coverage, not a replacement for the Oracle Linux gate.

**Rocky Linux**: official community AMIs exist (owner `792107900819`, no fees,
no product codes), but Rocky 8 publishing is patchy. The June 2026 `8.10-20260625`
refresh reached the community owner only in us-east-1, and eu-central-1 (where
this factory bakes) got it only as a Marketplace image whose product code would
propagate into every derived AMI. Under the community owner in eu-central-1 the
newest Rocky 8 AMI remains `8.10-20240528`, AWS-deprecated since 2026-05-31, so
the previously hand-pinned Rocky 8 AMI aged two years of errata into every
molecule run. The
factory seeds each Rocky lineage once from the official AMI, then refreshes it
weekly like OL, which keeps Rocky 8 current even though upstream no longer
ships AWS images for it. The official 10 GiB roots restore fine onto the factory's
`var.volume_size` (EBS only refuses to shrink), so Rocky needs no
bootstrap/re-image machinery.

So we bake our own, on a schedule, in the CI build account (eu-central-1). The
same Packer templates + scripts run whether driven locally (`justfile`) or by the
GitHub Actions workflow. Builds connect over AWS Session Manager (no inbound SSH)
and authenticate via GitHub OIDC (no static keys).

## Build paths

| Path | Covers | Mechanism |
|------|--------|-----------|
| **Refresh** (`refresh.pkr.hcl`) | OL + Rocky 8/9/10 (x86_64 + arm64) | `amazon-ebs`: launch the latest self-owned base of the same os+major+arch, `dnf update`, validate (fail-closed), snapshot. Each build's output is the next build's source. |
| **Seed** (`refresh.pkr.hcl -var seed=true`) | Rocky lineage roots, one-time per combo | Same bake, but sourced from the official Rocky community AMI (`include_deprecated` covers the frozen Rocky 8). Promoted seeds become the refresh lineage. |
| **Bootstrap** (`scripts/bootstrap-ol10.sh`) | OL10 lineage root, one-time per arch | Import Oracle's official OL10 cloud image (arm64 `*-kvm-cloud-*.qcow2`, x86_64 `*-aws-*.vmdk`) via `aws ec2 import-snapshot`, register the raw base, then `packer build bootstrap/finalize-ol10.pkr.hcl` (adds `ec2-user` + `amazon-ssm-agent` + `dnf update`). Needed because Oracle ships no OL10 AWS AMI. Promotes to a `prebase` role: Oracle's full-size root cannot launch on `var.volume_size`, so `reimage-ol10` shrinks it before it becomes the consumed base. Idempotent. |
| **Re-image** (`scripts/reimage-ol10.sh`) | OL10 root shrink, one-time per arch | Copy the current base's root onto a fresh `var.volume_size` GiB volume on a builder, snapshot + register, then `reimage-ol10-verify` boot-tests two sizes + smoke + size-gate + promote. Needed because EBS cannot restore a volume below its source snapshot and XFS cannot shrink in place. Full internals (`dd` /boot, LVM-to-plain, the gates): [docs/reimage.md](docs/reimage.md). |

## Usage

```bash
export AWS_PROFILE=percona-dev-admin
just check                              # fmt-check + validate every combo (no AWS)
just bake 9 x86_64                      # build + smoke + promote one OL combo
just bake 9 x86_64 prod rocky           # same for a seeded Rocky combo
just seed-rocky 9 x86_64 prod           # one-time Rocky lineage root for ONE combo (bake + smoke + promote)
just ci-seed-rocky                      # dispatch the GHA workflow to seed ALL six Rocky combos (env=prod)
just all                                # build + promote every OL major x arch
just all prod rocky                     # same for Rocky (after seeding)
just bootstrap-ol10-prep                # one-time: import bucket + vmimport role + boot-test SG/key
just bootstrap-ol10 x86_64              # one-time OL10 lineage root for an arch
just bootstrap-ol10-verify <ami> x86_64 # boot-validate a candidate then promote it (prebase)
just reimage-ol10 x86_64                # shrink the OL10 base root to var.volume_size (lineage shrink)
just reimage-ol10-verify <ami> x86_64   # boot-test two sizes + smoke + size-gate + promote
```

Each build registers `OL<major>-<arch>-<UTCstamp>` / `Rocky<major>-<arch>-<UTCstamp>`
tagged `os=oraclelinux|rocky`,
`os_major`, `arch`, `source=factory` with **`role=ppg-candidate`**, then the
native-Packer smoke (`smoke/smoke.pkr.hcl`) fresh-boot-tests it. The smoke
template ONLY validates; **promotion to `role=ppg-package-test` is a separate,
retried step** in the `justfile`/workflow that runs after a passing smoke, so a
transient tag-API failure can never deregister a smoke-passed AMI. A real
boot/install failure deregisters the candidate + its snapshots.
The jobs and the next build's source filter select only `role=ppg-package-test`,
so a non-booting image is never selectable and AMI IDs are never pinned.
Login user: `ec2-user` on OL, `rocky` on Rocky (the images keep their distro's
cloud-init default user, matching what the molecule scenarios already use). "Latest"
is the newest `role=ppg-package-test` AMI by `CreationDate` (no SSM parameter).

## Housekeeping (all via `just`, fail-safe)

Cleanup is recipes too. Every prune recipe **lists by default** and deregisters
only on an explicit `1`; the guard **never deletes a promoted prod base** and
fail-closes on an AMI it cannot positively classify.

```bash
just list                # current factory AMIs (prod|test)
just prune-superseded    # older prod dups (keeps newest per combo); add 1 to delete
just prune-test          # isolated env=test AMIs;                   add 1 to delete
just prune-stale         # raw/candidate intermediates + orphans;    add 1 to delete
just prune-all           # prune-test + prune-stale
just teardown-temp       # temp builder/vmimport/key/SG;             add 1 to delete
just ci-validate         # trigger the GHA workflow matrix (env=test)
just ci-seed-rocky       # dispatch all six Rocky seeds (env=prod; every combo must be seeded once)
```

## Job AMI lookup (replaces hardcoded IDs)

`vars/moleculeEnvPPG.groovy` resolves each Oracle target at pipeline time
(fail-closed: the job aborts if no base AMI is found). A follow-up PR switches
the pg.cd jobs' Rocky targets to the same lookup with `Name=tag:os,Values=rocky`:

```bash
ami_ol9_x86_64=$(aws ec2 describe-images --region eu-central-1 --owners self \
  --filters Name=tag:role,Values=ppg-package-test Name=tag:os,Values=oraclelinux \
            Name=tag:os_major,Values=9 Name=tag:arch,Values=x86_64 \
            Name=state,Values=available \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)
```

## Validation gates (fail-closed)

`scripts/validate.sh` runs as the last provisioner; any failure aborts the build
so a broken/mislabeled image is never registered:

1. `/etc/os-release` `ID` + `VERSION_ID` major match the target (fidelity, release-file names cannot tell EL distros apart).
2. `uname -m` matches the target arch.
3. The CRB repo is defined: `ol<major>_codeready_builder` (OL), `powertools`
   (Rocky 8) or `crb` (Rocky 9/10). PG -devel deps resolve from it.
4. `cloud-init` present.
5. SELinux `enforcing` in `/etc/selinux/config` (fail-closed, stock OL and Rocky ship enforcing).
6. `dnf makecache` succeeds (repos healthy).

De-instancing is native: Packer's `ssh_clear_authorized_keys` strips the temp key,
cloud-init regenerates host keys on first boot, and `provision.sh` already reset
cloud-init + machine-id.

## Promotion (fresh-boot smoke)

`validate.sh` runs on the *builder* before snapshot; it cannot prove the
*resulting* AMI boots. The native-Packer smoke (`smoke/smoke.pkr.hcl`, a
`skip_create_ami` build) closes that gap: it launches the candidate, waits for
2/2 status, connects over Session Manager (proving cloud-init re-injected access
after the bake stripped keys), asserts the OS major/arch, and installs
`percona-release` + a PPG server package. The smoke build ONLY validates; the
caller (`justfile`/workflow) then promotes to `role=ppg-package-test` in a
**separate retried step**, and deregisters the candidate + its snapshots ONLY on
a real boot/install failure (never on a transient promote-tag failure).

## OL10 status

OL10 is live: both lineage-root bases (x86_64 + arm64) are built and promoted
`role=ppg-package-test`, and the refresh template's `os_major` validation
includes `10`, so OL10 refreshes on the same schedule as OL8/OL9. The bases are
re-imaged to `var.volume_size` so they launch on the same root size as OL8/OL9
instead of Oracle's larger imported root ([docs/reimage.md](docs/reimage.md)); the
refresh sustains that size. The one-time bootstrap imported Oracle's official cloud
images via `import-snapshot` (arm64 is
not eligible for `import-image`, so the VMDK is imported as a snapshot and
registered with `--boot-mode uefi`; x86_64 uses Oracle's `-aws-` VMDK with
`--boot-mode legacy-bios`). x86_64 OL10 requires the `x86-64-v3` microarch, which
every current EC2 family provides.
