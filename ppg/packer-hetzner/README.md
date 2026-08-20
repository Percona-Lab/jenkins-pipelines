# PPG EL snapshot factory (Rocky, Alma, and Oracle Linux on Hetzner Cloud)

Builds the Rocky Linux, AlmaLinux, and Oracle Linux package-test target
snapshots that PG release testing runs against on Hetzner Cloud. Sibling of
the AWS AMI factory
([../packer](../packer)): same structure (refresh + seed + fresh-boot smoke +
separate retried promote), same scripts driven by both the `justfile` and the
GitHub Actions workflow (`.github/workflows/ppg-hcloud-factory.yml`).

## Why this exists

Hetzner ships official, current system images for every combo this factory
covers: `rocky-8/9/10` and `alma-8/9/10`, x86_64 **and** arm64. So unlike the
AWS factory there is no import/re-image machinery: every lineage seeds
directly from the official image (`-var seed=true`), then refreshes weekly
from its own newest promoted snapshot.

Oracle Linux has no Hetzner system image. Its lineage roots are base
snapshots (`role=ppg-base`, `source=bootstrap`) that
`bootstrap/bootstrap-base.sh` uploads one-time per combo from Oracle's
official KVM qcow2 (checksum-verified, written via
[hcloud-upload-image](https://github.com/apricote/hcloud-upload-image)'s
rescue-mode flow, then boot-checked). An `os=oraclelinux` seed sources the
newest matching base instead of an official image name. From there the OL
lineage refreshes exactly like Rocky/Alma. OL9 x86_64 is proven end to end.
OL8/OL10 bases are wired but unbootstrapped, and arm64 waits on CAX stock,
so `oraclelinux` stays OUT of the justfile `os_list` (the CI seed/validate
matrix) until its base coverage matches.

## Matrix

Full matrix: {rocky, almalinux, oraclelinux} x {8, 9, 10} x {x86_64, arm64}.
The workflow's default (weekly/push) matrix carries only the combos with a
promoted lineage today, so no leg is guaranteed red: rocky and almalinux
8/9/10 x86_64 plus oraclelinux 9 x86_64. The OL8/OL10 x86_64 rows sit
commented in the workflow until their `ppg-base` bootstrap + seed land, and
the arm64 rows return when CAX stock allows seeding (zero stock in
fsn1/nbg1/hel1 since 2026-08-05). Builders: `cpx32` (x86_64) / `cax21`
(arm64), location `fsn1` by default, overridable to `nbg1`/`hel1` (CAX exists
only in those three).

## Label contract

Snapshots have no API name. The identity string
`PPG-<Rocky|Alma|OL><major>-<arch>-<UTCstamp>` (bases:
`PPG-Base-OL<major>-<arch>-<UTCstamp>`) lands in the image description, and
everything else is labels:

| Label | Values | Meaning |
|-------|--------|---------|
| `role` | `ppg-candidate` -> `ppg-package-test`, or `ppg-base` | Candidate at build time. The smoke pass + promote flips it to the promoted role the consumers and the refresh lineage select. `ppg-base` marks a bootstrap-uploaded OL lineage root (never promoted, never consumed directly). |
| `os` / `os_major` / `arch` | `rocky\|almalinux\|oraclelinux` / `8\|9\|10` / `x86_64\|arm64` | The combo. |
| `source` | `factory` | Factory-produced (env=test: `factory-test`, bootstrap-uploaded bases: `bootstrap`). |
| `factory_env` | `prod\|test` | Namespace: env=test bakes carry `role=ppg-test-candidate` -> `ppg-test-package-test` and are never selected by production. |
| `factory_run` | UTC stamp | Build correlation. |
| `base_image` / `base_mode` | e.g. `rocky-9` / `seed\|lineage` | Lineage provenance: the official image family the chain descends from, and whether this bake sourced it directly (seed) or via the chain. The hcloud builder has no source-image interpolation, so there is no per-parent ID label (the parent is the previously promoted snapshot of the same combo). |
| `smoke` | `passed` | Set by the smoke gate right after a pass (before promotion, so a candidate whose promote failed transiently stays distinguishable from debris) and re-asserted by promote. Never set at build time. |

## Consumer lookup rule

"Latest" is the newest promoted snapshot by creation date, selected by labels,
never by pinned ID:

```bash
hcloud image list -t snapshot \
  -l 'role=ppg-package-test,os=rocky,os_major=9,arch=arm64' \
  -s created:desc -o noheader -o columns=id | head -1
```

`just latest 9 arm64 rocky` runs exactly this.

## Usage

```bash
export HCLOUD_TOKEN=...   # project-scoped factory token, check/fmt/validate need none
just check                # fmt-check + validate all combos + drift guards (no API)
just seed 9 x86_64        # one-time lineage root for ONE combo (bake + smoke + promote)
just ci-seed              # dispatch the GHA workflow to seed ALL 12 combos (env=prod)
bootstrap/bootstrap-base.sh          # one-time OL base upload + boot check (OS_MAJOR=9)
just seed 9 x86_64 prod oraclelinux  # OL lineage root: seeds from that ppg-base snapshot
just bake 9 x86_64        # refresh one combo: build + smoke + promote
just all                  # every major x arch for one os (default rocky)
just all prod almalinux   # same for almalinux
just test 9 x86_64        # isolated env=test bake (never consumed)
just list                 # current factory snapshots (prod|test)
just prune                # retention sweep, lists only (`just prune prod 1` deletes)
```

Each build snapshots a candidate (`role=ppg-candidate`), then the native-Packer
smoke (`smoke/smoke.pkr.hcl`, a `skip_create_snapshot` build) fresh-boots it:
cloud-init must re-initialise (proving the bake's cleanup worked and Hetzner
can re-inject SSH keys), identity must match, and a real
`percona-release enable-only ppg-17` + `dnf install percona-postgresql17-server`
must succeed. The smoke template ONLY validates. `scripts/smoke-run.sh` (shared
by the justfile and the workflow) gates what happens next on the
`SMOKE-PROVISIONER-STARTED` sentinel the provisioner echoes first: a failure
AFTER the sentinel is a genuine boot/install failure and deletes the candidate,
a failure WITHOUT it (server create failed, CAX stock-out, API 5xx, SSH
timeout) keeps the candidate for a retry. On a pass the candidate is labeled
`smoke=passed` immediately, then **promotion runs as a separate, retried step**
(`scripts/promote.sh`, which refuses a snapshot whose role or `factory_env`
does not match the requested env), so a transient label-API failure can never
delete a smoke-passed snapshot. The refresh lineage selector additionally
requires `source=factory` + `smoke=passed`, so a manually labeled or
test-leaked snapshot can never become a parent. Login user: `root` for every
os. Rocky/Alma ship that natively.
On OL, Hetzner's vendor-data makes root cloud-init's default user but
Oracle's stock `disable_root: true` stubs the injected key, so the refresh
template passes `disable_root: false` user-data for the build boot and
`provision.sh` bakes that override into the lineage (the pristine `ppg-base`
snapshot itself needs the user-data line to be reachable, see
`bootstrap/bootstrap-base.sh`).

## Retention and cleanup

Hetzner has no `deprecate_at`, so `scripts/prune.sh` is the PRIMARY retention:
keep the newest 4 promoted snapshots per combo (justfile `keep_last`) plus a
two-tier stale-candidate sweep (unlabeled candidates older than 7 days are
debris from a run that died between build and smoke/promote/delete, and
`smoke=passed` candidates age out only after 21 days: promote-retry material
is spared, not immortal). Fail-safe: lists
by default, deletes only on an explicit `apply=1`, selects exclusively by the
factory's own label contract (never touches what it cannot identify), and
never deletes the newest N promoted per combo. The weekly scheduled bake
self-prunes: the workflow's `prune` job runs `prune.sh prod 1 4` after a green
scheduled bake (manual dispatches never prune).

Leaked servers and SSH keys are the janitor's job
(`scripts/janitor.sh` + `.github/workflows/ppg-hcloud-janitor.yml`): the
hourly cron APPLIES with a conservative 6-hour age floor, a manual dispatch
defaults to list-only, and anything listed or deleted posts a Slack alert.

## Token

One project-scoped Hetzner API token, exported as `HCLOUD_TOKEN` locally. The
workflows keep no Hetzner credential in GitHub: each token-using job assumes
a GitHub-OIDC AWS role whose trust is pinned to master (exact-subject
`StringEquals`, no wildcards) and reads the token from the
`/ppg/hcloud-factory-token` SSM SecureString parameter (both defined in
percona-cd-platform `terraform/iam-gha-ppg-hcloud-factory.tf`), so a
branch-ref dispatch fails at AssumeRole. The token never appears in files or
output. `check`/`fmt`/`validate` run tokenless (the plugin insists on a
non-empty token even offline, so `check.sh` exports an inert placeholder).

## Deviations from the AWS factory

- Plain SSH as root, no Session Manager and no baked agents: Hetzner injects
  the temp key at server create, `ssh_clear_authorized_keys` strips it.
- Promotion flips a label on the same image ID (`hcloud image add-label
  --overwrite`). AWS re-tags an immutable AMI. Same fail-safe ordering.
- No `deprecate_at`: retention is `prune.sh` (see above).
- Lineage provenance is `base_image`+`base_mode` labels, not parent-image IDs
  (no source-image interpolation in the hcloud builder).
- SELinux gate asserts NOT disabled (vs enforcing on AWS): the mode Hetzner's
  official images ship is unverified. Tighten once the seeds prove enforcing.
