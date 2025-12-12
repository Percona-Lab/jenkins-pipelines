# AGENTS.md - PMM Pipelines

Extends: [../AGENTS.md](../AGENTS.md) | [../vars/AGENTS.md](../vars/AGENTS.md)

## TL;DR (read this first)

- **Main pipelines live in** `pmm/v3/*.groovy` (42 scripts).
- **PMM-specific shared library** lives in `pmm/v3/vars/` and is loaded via `v3lib@master` (`libraryPath: 'pmm/v3/'`).
- **OpenShift cluster jobs are JJB-managed**: parameters live in `pmm/openshift/openshift-cluster-*.yml` and code in `pmm/openshift/openshift_cluster_*.groovy`.
- **High-cost resources** used by PMM jobs: AWS spot instances (staging), EKS clusters, DigitalOcean droplets (OVF), OpenShift clusters. Always verify cleanup paths before/after edits.
- **Downstream contracts matter**: many PMM scripts `build job:` other Jenkins jobs; don’t rename/remove parameters or job names without checking Jenkins configs first.

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins instance | `pmm` |
| URL | https://pmm.cd.percona.com |
| Common job prefixes | `pmm3-*`, `openshift-cluster-*`, `aws-staging-stop*` |
| Primary script roots | `pmm/v3/`, `pmm/openshift/` |

## Dynamics Snapshot (repo scan + git)

Derived from a local scan of `pmm/**/*.groovy` (job-to-job calls + cron) and `git log --since='12 months ago' -- pmm` (churn):

- **Largest orchestrators (fan-out)**: `pmm/v3/pmm3-release-candidate.groovy`, `pmm/v3/pmm3-ui-tests-nightly.groovy` (each triggers ~8 downstream jobs).
- **Most-shared dependency (fan-in)**: `aws-staging-stop` is invoked by 7 different PMM jobs → treat its parameter contract as stable.
- **Resource creation is centralized**:
  - AWS spot *create* happens only in `pmm/v3/vars/runSpotInstance.groovy` (used by staging start pipelines).
  - EKS *create* happens only in `pmm/v3/pmm3-ha-eks.groovy` (cleanup is `pmm/v3/pmm3-ha-eks-cleanup.groovy`).
  - DigitalOcean droplet create/delete happens only in `pmm/v3/pmm3-ovf-staging-start.groovy` (daily cleanup in `pmm/v3/pmm3-ovf-staging-stop.groovy`).
  - OpenShift cluster create/destroy happens in `pmm/openshift/openshift_cluster_{create,destroy}.groovy` (params are in `pmm/openshift/openshift-cluster-*.yml`).
- **Job name aliases exist** (downstream `build job:` names don’t always match script filenames). Common ones:
  - `aws-staging-start-pmm3` → likely `pmm3-aws-staging-start`
  - `pmm-ui-tests` → likely `pmm3-ui-tests`
  - `pmm-*-staging-start/stop`, `pmm-*-upgrade-tests` → likely `pmm3-...` equivalents
  - Always confirm via `~/bin/jenkins job pmm config <job> -f yaml` (script-path is the source of truth).
- **Highest churn files (last 12 months)**: `pmm/v3/pmm3-ui-tests-nightly.groovy`, `pmm/v3/pmm3-aws-staging-start.groovy`, `pmm/v3/pmm3-client-autobuild-{amd,arm}.groovy`, `pmm/v3/pmm3-package-testing.groovy`, `pmm/v3/pmm3-submodules.groovy`, `pmm/v3/vars/setupPMM3Client.groovy`.

## Job Dependency Graph (LLM-Optimized)

Data source: Jenkins CLI (`~/bin/jenkins job pmm`) + codebase scan. Last updated: 2024-12.

### Node Metrics (Jenkins-verified)

| Job | Builds | Tier | Cat | Fan-In | Fan-Out | Cron | Risk |
|-----|--------|------|-----|--------|---------|------|------|
| pmm3-submodules-rewind | 77246 | 0 | trigger | 1 | 0 | H/15 | LOW |
| aws-staging-stop | 75294 | 2 | infra | 7 | 0 | - | CRITICAL |
| pmm3-aws-staging-start | 15527 | 2 | infra | 6 | 0 | - | CRITICAL |
| pmm3-ui-tests-nightly | 1639 | 0 | orch | 0 | 9 | 0 0 | MEDIUM |
| pmm3-server-autobuild | 1622 | 1 | build | 2 | 1 | up | MEDIUM |
| pmm3-ami | 1609 | 1 | build | 1 | 1 | up | LOW |
| pmm3-ovf | 1516 | 1 | build | 1 | 0 | up | LOW |
| pmm3-client-autobuild | 1481 | 1 | build | 1 | 2 | up | MEDIUM |
| openshift-cluster-create | 73 | 2 | infra | 2 | 0 | - | HIGH |
| pmm3-release-candidate | 56 | 0 | orch | 0 | 8 | man | MEDIUM |
| pmm3-ha-eks | 22 | 2 | infra | 0 | 0 | man | LOW |

Legend: Tier: 0=entry, 1=build, 2=infra, 3=test | Cat: orch=orchestrator, up=upstream-triggered, man=manual

### Explicit Edges (56 edges from `build job:` calls)

```
pmm-submodules → pmm3-submodules
pmm3-ami → pmm3-ami-test
pmm3-ami-test → pmm3-ami-staging-start
pmm3-ami-test → pmm3-ami-staging-stop
pmm3-ami-upgrade-tests → aws-staging-start-pmm3
pmm3-ami-upgrade-tests → aws-staging-stop
pmm3-ami-upgrade-tests → pmm-ami-staging-start
pmm3-ami-upgrade-tests → pmm-ami-staging-stop
pmm3-ami-upgrade-tests-matrix → pmm-ami-upgrade-tests
pmm3-cli-tests → aws-staging-start-pmm3
pmm3-cli-tests → aws-staging-stop
pmm3-client-autobuild → pmm3-client-autobuild-amd
pmm3-client-autobuild → pmm3-client-autobuild-arm
pmm3-openshift-helm-tests → openshift-cluster-create
pmm3-openshift-helm-tests → openshift-cluster-destroy
pmm3-ovf-image-test → pmm-ui-tests
pmm3-ovf-upgrade-tests → aws-staging-start-pmm3
pmm3-ovf-upgrade-tests → aws-staging-stop
pmm3-ovf-upgrade-tests → pmm-ovf-staging-start
pmm3-ovf-upgrade-tests → pmm-ovf-staging-stop
pmm3-ovf-upgrade-tests-matrix → pmm-ovf-upgrade-tests
pmm3-package-testing → aws-staging-stop
pmm3-package-testing → pmm3-aws-staging-start
pmm3-package-tests-matrix → pmm3-aws-staging-start
pmm3-package-tests-matrix → pmm3-package-testing-arm
pmm3-release → pmm3-image-scanning
pmm3-release-candidate → pmm3-ami
pmm3-release-candidate → pmm3-client-autobuild
pmm3-release-candidate → pmm3-image-scanning
pmm3-release-candidate → pmm3-ovf
pmm3-release-candidate → pmm3-rewind-submodules-fb
pmm3-release-candidate → pmm3-server-autobuild
pmm3-release-candidate → pmm3-submodules-rewind
pmm3-release-candidate → pmm3-watchtower-autobuild
pmm3-release-tests → package-testing
pmm3-release-tests → pmm-upgrade-tests
pmm3-server-autobuild → pmm3-api-tests
pmm3-submodules → pmm3-api-tests
pmm3-submodules → pmm3-watchtower-autobuild
pmm3-testsuite → aws-staging-start-pmm3
pmm3-testsuite → aws-staging-stop
pmm3-ui-tests-matrix → pmm3-ui-tests
pmm3-ui-tests-nightly → aws-staging-stop
pmm3-ui-tests-nightly → openshift-cluster-create
pmm3-ui-tests-nightly → openshift-cluster-destroy
pmm3-ui-tests-nightly → pmm-ovf-staging-stop
pmm3-ui-tests-nightly → pmm3-ami-staging-start
pmm3-ui-tests-nightly → pmm3-ami-staging-stop
pmm3-ui-tests-nightly → pmm3-aws-staging-start
pmm3-ui-tests-nightly → pmm3-ovf-staging-start
pmm3-ui-tests-nightly-gssapi → aws-staging-stop
pmm3-ui-tests-nightly-gssapi → pmm-ovf-staging-stop
pmm3-ui-tests-nightly-gssapi → pmm3-ami-staging-stop
pmm3-ui-tests-nightly-gssapi → pmm3-aws-staging-start
pmm3-upgrade-tests → pmm3-upgrade-test-runner
pmm3-upgrade-tests-matrix → pmm3-upgrade-tests
```

### Upstream Triggers (auto-cascade on success)

```
pmm3-submodules-rewind →(upstream)→ pmm3-server-autobuild
pmm3-submodules-rewind →(upstream)→ pmm3-client-autobuild
pmm3-submodules-rewind →(upstream)→ pmm3-watchtower-autobuild
pmm3-server-autobuild →(upstream)→ pmm3-api-tests
pmm3-server-autobuild →(upstream)→ pmm3-ami
pmm3-server-autobuild →(upstream)→ pmm3-ovf
pmm3-server-autobuild →(upstream)→ pmm3-ui-tests-matrix
pmm3-server-autobuild →(upstream)→ pmm3-testsuite
```

### Stability Tiers

**CRITICAL** (fan-in >= 6, 75k+ builds): Parameter changes break everything
- `aws-staging-stop`: 7 callers, 75294 builds
- `pmm3-aws-staging-start`: 6 callers, 15527 builds

**HIGH** (fan-in >= 3): Verify all callers before changes
- `aws-staging-start-pmm3`: 4 callers (alias)
- `pmm3-ami-staging-stop`: 3 callers
- `pmm-ovf-staging-stop`: 3 callers

### Job Aliases (code → Jenkins)

| Code Reference | Actual Jenkins Job |
|----------------|-------------------|
| aws-staging-start-pmm3 | aws-staging-start OR pmm3-aws-staging-start |
| pmm-ovf-staging-stop | pmm3-ovf-staging-stop |
| pmm-ovf-staging-start | pmm3-ovf-staging-start |
| pmm-ami-staging-start | pmm3-ami-staging-start |
| pmm-ui-tests | pmm3-ui-tests |

## Where Things Live (directory map)

```
pmm/
  v3/                          # PMM v3 pipelines (most work happens here)
    *.groovy                   # Pipeline scripts
    vars/                      # PMM-only shared library steps (loaded via v3lib@master)
  openshift/                   # OpenShift cluster lifecycle (create/destroy/list)
    openshift_cluster_*.groovy  # Pipeline code
    openshift-cluster-*.yml     # Jenkins Job Builder configs (params + script-path)
  infrastructure/              # Infra helper jobs
  aws-staging-stop*.groovy      # AWS staging VM cleanup jobs
  pmm-submodules.groovy         # PR/branch wrapper for submodules workflows
```

## “What file do I edit?” (fast index)

### Release + build pipelines

- **Release RC orchestrator**: `pmm/v3/pmm3-release-candidate.groovy`
- **Release (GA) pipeline**: `pmm/v3/pmm3-release.groovy`
- **Server build (RPMs + Docker)**: `pmm/v3/pmm3-server-autobuild.groovy`
  - Triggers `pmm3-api-tests` and (conditionally) `percona/pmm` devcontainer GH workflow.
- **Client build orchestrator (multi-arch + manifest)**: `pmm/v3/pmm3-client-autobuild.groovy`
- **Client build (amd64)**: `pmm/v3/pmm3-client-autobuild-amd.groovy`
- **Client build (arm64)**: `pmm/v3/pmm3-client-autobuild-arm.groovy`
- **Submodules nightly/PR workflows**:
  - Wrapper (for PR/branch jobs): `pmm/pmm-submodules.groovy`
  - Auto-rewind `v3` submodules (cron): `pmm/v3/pmm3-submodules-rewind.groovy`
  - Feature-branch rewind: `pmm/v3/pmm3-rewind-submodules-fb.groovy`
  - Feature-build pipeline: `pmm/v3/pmm3-submodules.groovy`

### Staging environments (cost control)

- **AWS staging VM create (spot, adds/removes Jenkins node dynamically)**: `pmm/v3/pmm3-aws-staging-start.groovy`
- **AWS staging VM stop (interactive)**: `pmm/aws-staging-stop.groovy`
- **AWS staging VM stop (cron TTL cleanup)**: `pmm/aws-staging-stop-robot.groovy`

- **AMI staging create**: `pmm/v3/pmm3-ami-staging-start.groovy` (uses `us-east-1` in AWS CLI calls)
- **AMI staging stop**: `pmm/v3/pmm3-ami-staging-stop.groovy`

- **OVF staging create (DigitalOcean + VirtualBox; AWS doesn’t support nested virt)**: `pmm/v3/pmm3-ovf-staging-start.groovy`
- **OVF staging stop/cleanup (cron)**: `pmm/v3/pmm3-ovf-staging-stop.groovy`
  - Implementation: `resources/pmm/do_remove_droplets.py`

### Tests

- **UI tests (docker-compose local)**: `pmm/v3/pmm3-ui-tests.groovy`
- **UI tests nightly orchestrator**: `pmm/v3/pmm3-ui-tests-nightly.groovy`
- **UI tests nightly (GSSAPI)**: `pmm/v3/pmm3-ui-tests-nightly-gssapi.groovy`
- **Upgrade test matrix**: `pmm/v3/pmm3-upgrade-tests.groovy` → runs `pmm3-upgrade-test-runner`
- **Upgrade test runner**: `pmm/v3/pmm3-upgrade-test-runner.groovy`
- **Package testing**:
  - Orchestrator: `pmm/v3/pmm3-package-tests-matrix.groovy`
  - Runner: `pmm/v3/pmm3-package-testing.groovy` (fans out to multiple `min-*-arm64` agents)

### Kubernetes

- **EKS HA infra bring-up** (creates cluster, archives kubeconfig; does not delete on success): `pmm/v3/pmm3-ha-eks.groovy`
- **EKS HA cleanup** (cron + manual delete): `pmm/v3/pmm3-ha-eks-cleanup.groovy`

### OpenShift clusters (JJB-managed)

- **Job configs (parameters + script-path)**:
  - `pmm/openshift/openshift-cluster-create.yml`
  - `pmm/openshift/openshift-cluster-destroy.yml`
  - `pmm/openshift/openshift-cluster-list.yml`
- **Pipeline code**:
  - `pmm/openshift/openshift_cluster_create.groovy`
  - `pmm/openshift/openshift_cluster_destroy.groovy`
  - `pmm/openshift/openshift_cluster_list.groovy`
- **Shared helpers used by those pipelines** (global library): `vars/openshiftCluster.groovy`, `vars/openshiftS3.groovy`, `vars/openshiftTools.groovy`, `vars/awsCertificates.groovy`

### Infra

- **Build rpmbuild container images** (used by client build scripts): `pmm/infrastructure/rpm-build-3.groovy`

## Downstream Build Graphs (what calls what)

These are extracted from `build job:` usage in `pmm/` scripts. Some downstream job names are *legacy aliases* (they may not match a script filename); always confirm the real `script-path` in Jenkins before renaming anything.

**Release candidate (top-level orchestration)**: `pmm/v3/pmm3-release-candidate.groovy`
```
pmm3-release-candidate
├── pmm3-submodules-rewind
├── pmm3-rewind-submodules-fb
├── pmm3-server-autobuild → pmm3-api-tests
├── pmm3-client-autobuild → pmm3-client-autobuild-amd + pmm3-client-autobuild-arm
├── pmm3-watchtower-autobuild
├── pmm3-ami → pmm3-ami-test → pmm3-ami-staging-start/stop
├── pmm3-ovf
└── pmm3-image-scanning
```

**UI tests nightly orchestration**: `pmm/v3/pmm3-ui-tests-nightly.groovy`
```
pmm3-ui-tests-nightly
├── pmm3-aws-staging-start  → aws-staging-stop
├── pmm3-ovf-staging-start  → pmm-ovf-staging-stop   (legacy job name)
├── pmm3-ami-staging-start  → pmm3-ami-staging-stop
└── openshift-cluster-create → openshift-cluster-destroy
```

## Shared Libraries (this is the #1 source of confusion)

### Global shared library (`lib@master`)

Most PMM pipelines start with:

```groovy
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
```

This exposes helpers from repo root `vars/` (see `vars/AGENTS.md`). Common ones used by PMM jobs include: `pmmVersion()`, `runPython()`, `installDocker()`, `uploadRPM()`, `uploadDEB()`, `signRPM()`, `signDEB()`, `sync2ProdPMMClientRepo()`, `waitForContainer()`.

### PMM v3 shared library (`v3lib@master`) — `pmm/v3/vars/*`

Some PMM pipelines also load:

```groovy
library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)
```

That exposes PMM-only helper steps from `pmm/v3/vars/`:

- `pmm/v3/vars/getPMMBuildParams.groovy`: `getPMMBuildParams(prefix)` sets `env.VM_NAME`, `env.OWNER`, `env.OWNER_SLACK`; returns `[VM_NAME, OWNER, OWNER_SLACK]`.
- `pmm/v3/vars/getSSHKeys.groovy`: `getSSHKeys()` fetches GitHub public keys for a hard-coded list of users (network access) and returns them as a single string.
- `pmm/v3/vars/runSpotInstance.groovy`: `runSpotInstance(instanceType)` launches a spot instance using `pmm-staging-slave`, tags resources, and sets `env.SPOT_PRICE`, `env.REQUEST_ID`, `env.IP`, `env.AMI_ID`. Requires `env.VM_NAME`, `env.OWNER`, and `env.DAYS` to already be set.
- `pmm/v3/vars/setupPMM3Client.groovy`: `setupPMM3Client(...)` installs/configures `pmm-client` (packages or tarball) and optionally runs `pmm-agent setup`.
- `pmm/v3/vars/uploadPMM3RPM.groovy`: `uploadPMM3RPM()` uploads stashed RPMs to `repo.ci.percona.com`; expects stashes named `rpms` and `uploadPath`.

## Agents / Node Labels (used by PMM jobs)

These come directly from `pmm/**/*.groovy` and are often backed by special-purpose infra. Don’t “simplify” them unless you know the Jenkins node fleet.

- `agent-amd64-ol9`: default build executor for most PMM v3 jobs.
- `agent-arm64-ol9`: arm64 client build (`pmm/v3/pmm3-client-autobuild-arm.groovy`).
- `cli`: long-lived, AWS/DO control-plane tasks (staging start/stop, submodules rewind, etc).
- `docker`: used by `pmm/v3/pmm3-upgrade-tests.groovy` (runs docker-heavy workloads).
- `min-noble-x64`, `min-ol-9-x64`: used for UI tests/release steps that need specific tooling/OS.
- `min-*-arm64`: used by `pmm/v3/pmm3-package-testing.groovy` parallel stages.
- `ovf-do`, `ovf-do-el9`: DigitalOcean droplet workers running OVF/VirtualBox workflows.
- `master`: used in some upload/push stages (example: `pmm/v3/pmm3-client-autobuild-arm.groovy` “Push to public repository” stage).

## Credentials (IDs you’ll see in PMM pipelines)

Common IDs referenced by PMM scripts (do not rename/remove):

- AWS:
  - `pmm-staging-slave` (most AWS actions; staging, EKS, S3 uploads)
  - `AMI/OVF` (ECR public login + some AMI/OVF-related flows)
- SSH:
  - `aws-jenkins`, `aws-jenkins-admin` (SSH to AWS hosts)
  - `repo.ci.percona.com` (package uploads)
  - `GitHub SSH Key` (pmm-submodules rewinds)
- Docker:
  - `hub.docker.com`
- OpenShift:
  - `jenkins-openshift-aws`, `openshift-pull-secret`, `openshift-ssh-key`
- Slack mapping:
  - `JenkinsCI-SlackBot-v2` (used by `slackUserIdFromEmail(...)`)

Many test jobs (UI/upgrade) also populate dozens of secrets via `credentials('...')` in `environment {}`. If you touch those jobs, assume any `credentials(...)` value is sensitive: avoid `set -x`, avoid echoing env, and prefer passing secrets via env vars (no Groovy interpolation).

## Scheduled Jobs (cron) in `pmm/`

If you change any of these, double-check cleanup behavior and notification noise:

- `pmm/aws-staging-stop-robot.groovy`: `cron('H * * * *')` — terminates AWS staging instances based on TTL tag.
- `pmm/v3/pmm3-submodules-rewind.groovy`: `cron('H/15 * * * *')` — rewinds `pmm-submodules` v3 and pushes commits.
- `pmm/v3/pmm3-upgrade-tests.groovy`: `cron('0 3 * * *')` — nightly upgrade matrix.
- `pmm/v3/pmm3-package-testing.groovy`: `cron('0 2 * * *')` — nightly package tests.
- `pmm/v3/pmm3-ui-tests-nightly.groovy`: `cron('0 0 * * *')` — nightly UI orchestration.
- `pmm/v3/pmm3-ui-tests-nightly-gssapi.groovy`: `cron('0 0 * * *')` — nightly UI (GSSAPI).
- `pmm/v3/pmm3-ovf-image-test.groovy`: `cron('0 22 * * *')` — nightly OVF image test.
- `pmm/v3/pmm3-ovf-staging-stop.groovy`: `cron('@daily')` — deletes old DO droplets tagged `jenkins-pmm` (via `resources/pmm/do_remove_droplets.py`).
- `pmm/v3/pmm3-ami-upgrade-tests-matrix.groovy`: `cron('0 1 * * 0')` — weekly AMI upgrade tests matrix (calls legacy job `pmm-ami-upgrade-tests`).
- `pmm/v3/pmm3-ovf-upgrade-tests-matrix.groovy`: `cron('0 1 * * 0')` — weekly OVF upgrade tests matrix (calls legacy job `pmm-ovf-upgrade-tests`).
- `pmm/v3/pmm3-ha-eks-cleanup.groovy`: `cron('H 0,12 * * *')` — EKS cluster cleanup (twice daily) + manual actions.

## Fast Navigation (grep recipes)

```bash
# What calls what (build graph)?
rg -n \"\\bbuild\\s+job:\" pmm/v3

# Where is a given job name referenced?
rg -n \"'pmm3-[^']+'\" pmm/v3

# Which scripts use v3lib?
rg -n \"identifier: 'v3lib@master'\" pmm/v3

# Credentials usage (by ID)
rg -n \"credentialsId: 'pmm-staging-slave'\" pmm
rg -n \"\\bcredentials\\('\" pmm/v3

# Scheduled jobs
rg -n \"\\bcron\\(\" pmm
```

## Git History (fast)

```bash
# Recent PMM changes
git log --oneline --max-count 50 -- pmm

# Most PMM work is Jira-ticketed
git log --oneline -- pmm | rg -n 'PMM-' | head

# Churn hotspots (helps you pick the right file to open first)
git log --since='12 months ago' --name-only --pretty=format: HEAD -- pmm \
  | sort | uniq -c | sort -rn | head

# Who touched PMM pipelines recently (rough ownership signal)
git shortlog -sne --since='12 months ago' HEAD -- pmm | head

# Follow a file across renames
git log --follow -p -- pmm/v3/pmm3-ui-tests-nightly.groovy
```

Recent structural changes worth knowing (from `git log -- pmm`):

**v2 End-of-Life (PMM-14154, PMM-14558)**:
- v2 pipelines deprecated; main branch restructured for v3 only
- Test repos consolidated to main branches

**OpenShift Enhancements (PMM-14242, PMM-14304, PMM-14287, PMM-14125)**:
- SSL automation: `vars/openshiftSSL.groovy`, `vars/awsCertificates.groovy`
- `PMM_HELM_BRANCH` param for non-released helm charts
- `PMM_IP` env var exposed for downstream jobs
- 87.5% OpenShift test coverage with SCC workaround

**Platform Updates**:
- Added: Debian 13 Trixie, RHEL 10 (PMM-14249, PMM-14208)
- Removed: Ubuntu 20.04 LTS (PMM-14057)
- Re-added: Debian Bullseye (PMM-14221)

**Security & CI**:
- Trivy scanner added alongside Snyk (PMM-14499)
- Some tests migrated from Jenkins to GitHub Actions (PMM-14115)
- Launchable observation mode for test intelligence (PMM-14427)

**EKS HA**: infra + cleanup jobs added (PMM-14346, PMM-14505).

## Related

- [cloud/AGENTS.md](../cloud/AGENTS.md) - K8s operators (PMM HA on EKS tests)
- [ps/AGENTS.md](../ps/AGENTS.md) - Percona Server (PMM monitors PS)
- [pxc/AGENTS.md](../pxc/AGENTS.md) - XtraDB Cluster (PMM monitors PXC)
- [psmdb/AGENTS.md](../psmdb/AGENTS.md) - Percona Server for MongoDB (PMM monitors PSMDB)
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers (runSpotInstance, openshiftSSL)

## Local Validation

```bash
# Groovy syntax check (pick the file you changed)
groovy -e \"new GroovyShell().parse(new File('pmm/v3/pmm3-server-autobuild.groovy'))\"

# Note: some pipelines import Jenkins classes or use `@Library(...)` and won’t parse in plain Groovy.

# Python helper (DigitalOcean cleanup)
python3 -m py_compile resources/pmm/do_remove_droplets.py
```

## Notes / Known “gotchas”

- `pmm/v3/pmm3-aws-staging-start.groovy` programmatically adds/removes Jenkins nodes (`DumbSlave`). If it fails before cleanup, you may need to remove the node manually in Jenkins.
- `pmm/v3/pmm3-ha-eks.groovy` intentionally leaves clusters running on success; `pmm/v3/pmm3-ha-eks-cleanup.groovy` is the safety net. Don’t weaken cleanup.
