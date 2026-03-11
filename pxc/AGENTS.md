# AGENTS.md - PXC Pipelines

Extends: [../AGENTS.md](../AGENTS.md) | [../vars/AGENTS.md](../vars/AGENTS.md)

## TL;DR (read this first)

- **Main pipelines live in** `pxc/jenkins/*.groovy` (24 scripts).
- **Version coverage**: PXC 5.6 (legacy), 5.7 (EOL), 8.0 (active), 8.4 LTS, 8.x Innovation.
- **Hetzner is DEFAULT for PXC 5.7**: `pxc57-pipeline.groovy` uses Hetzner by default, AWS as fallback.
- **No cron triggers**: All jobs are manually triggered or upstream-triggered.
- **Multi-node Galera testing**: Always cleanup with `moleculeParallelPostDestroy()`.
- **PRO/FIPS builds**: Separate pipelines for enterprise features (`pxc-package-testing-pro.groovy`).
- **Shared library**: All pipelines use `lib@master` from repo root `vars/`.

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins instance | `pxc` |
| URL | https://pxc.cd.percona.com |
| Total jobs | ~213 |
| Common job prefixes | `pxc*`, `proxysql*`, `pdpxc*` |
| Primary script root | `pxc/jenkins/` |
| Default credential | `pxc-staging` (AWS) |
| AWS region | `us-east-2` |
| Hetzner support | PXC 5.7 pipeline only (default) |

## Dynamics Snapshot (repo scan + git)

Derived from a local scan of `pxc/**/*.groovy` and `git log --since='12 months ago' -- pxc`:

- **Highest churn files (last 12 months)**: `pxc-package-testing.groovy` (10 commits), `pxc-package-testing-pro.groovy` (9 commits), `percona-xtradb-cluster-8.0.groovy` (6 commits), `pxc57-pipeline.groovy` (5 commits).
- **Package testing is most active**: Molecule-based testing pipelines see the most updates.
- **Hetzner migration ongoing**: PXC 5.7 defaults to Hetzner, other versions still on AWS.
- **PRO builds maturing**: FIPS/enterprise package testing actively maintained.

## Job Dependency Graph (LLM-Optimized)

Data source: Codebase scan of `build job:` calls. Last updated: 2025-12.

### Node Metrics

| Job | Lines | Tier | Cat | Fan-Out | Notes |
|-----|-------|------|-----|---------|-------|
| percona-xtradb-cluster-8.0 | ~800 | 1 | build | 0 | Main build, FIPS support |
| percona-xtradb-cluster-8.0-arm | ~600 | 1 | build | 0 | ARM64 build |
| percona-xtradb-cluster-5.7 | ~500 | 1 | build | 0 | EOL, private repo |
| pxc-package-testing | ~400 | 2 | test | 0 | Molecule testing |
| pxc-package-testing-parallel | ~150 | 0 | orch | 3 | Triggers package tests |
| pxc80-pipeline | ~350 | 2 | test | 0 | CI/test pipeline |
| pxc57-pipeline | ~300 | 2 | test | 0 | Hetzner default |
| get-pxc-branches-8.0 | ~100 | 0 | trigger | 1 | Auto-triggers builds |
| pxc80-pipeline-parallel-mtr | ~450 | 0 | orch | 1 | Parallel MTR |

Legend: Tier: 0=entry/orchestrator, 1=build, 2=test | Cat: orch=orchestrator, build=primary build, test=testing, trigger=polling

### Explicit Edges (from `build job:` calls)

```
get-pxc-branches-8.0 → pxc80-autobuild-RELEASE

pxc80-pipeline-parallel-mtr → pxc-8.0-pipeline-parallel-mtr

pxc-package-testing-parallel → pxc-package-testing (install, upgrade, kmip actions)
pxc-package-testing-parallel → pxc-package-testing-pro (PRO builds)
```

### Stability Tiers

**CRITICAL** (downstream dependencies):
- Artifact paths in `percona-xtradb-cluster-8.0.groovy` - consumed by PDPXC, cloud operators
- `pxc-package-testing` parameter contract - called by orchestrators

**HIGH** (internal fan-in):
- `pxc57-pipeline.groovy` - Hetzner infrastructure testing
- `pxc80-pipeline.groovy` - Main CI entry point

## Where Things Live (directory map)

```
pxc/
  jenkins/                           # Pipeline scripts (24 groovy files)
    percona-xtradb-cluster-*.groovy  # Core build pipelines (3 versions)
    pxc*-pipeline*.groovy            # CI/test pipelines (5 files)
    pxc-package-testing*.groovy      # Package testing (4 files)
    proxysql-package-testing*.groovy # ProxySQL testing (3 files)
    qa-pxc*-pipeline.groovy          # QA framework tests (4 files)
    get-pxc-branches-8.0.groovy      # Branch detection + auto-trigger
    pxc80-ami.groovy                 # AWS AMI builds
    pxc80-azure.groovy               # Azure image builds
    pxc-rhel-tarballs-86-89.groovy   # RHEL 8.6-8.9 tarballs
    pxc-keyring-test-pkgs.groovy     # Keyring/encryption tests
    pxc-binary-tarball-pro.groovy    # PRO binary tarballs
    *.yml                            # Job configuration YAML
  docker/                            # Docker build/test scripts (18 files)
    install-deps-*                   # Dependency installation
    run-build-*                      # Build execution
    run-test-*                       # Test execution
  local/                             # Local build scripts (18 files)
    build-binary-*                   # Binary builds
    checkout*                        # Git checkout helpers
    test-binary-*                    # Binary tests
    test-qa-framework-*              # QA framework tests
  pxc-site-check.groovy              # Website validation (root level)
  *.yml                              # Job definition YAML files (30+ files)
  AGENTS.md                          # This file
```

## "What file do I edit?" (fast index)

### Core Build Pipelines

- **PXC 8.0 build (main)**: `pxc/jenkins/percona-xtradb-cluster-8.0.groovy`
  - Builds RPMs, DEBs, tarballs, Docker images
  - Supports FIPS mode via `FIPSMODE` parameter
  - Supports 8.0, 8.4 LTS, 8.x Innovation via `PXC_REPO` parameter
- **PXC 8.0 ARM64 build**: `pxc/jenkins/percona-xtradb-cluster-8.0-arm.groovy`
- **PXC 5.7 build (EOL)**: `pxc/jenkins/percona-xtradb-cluster-5.7.groovy` (uses private repo)

### CI/Test Pipelines

- **PXC 8.0 CI**: `pxc/jenkins/pxc80-pipeline.groovy`
- **PXC 5.7 CI (Hetzner default)**: `pxc/jenkins/pxc57-pipeline.groovy`
- **PXC 5.6 CI (legacy)**: `pxc/jenkins/pxc56-pipeline.groovy`
- **Parallel MTR**: `pxc/jenkins/pxc80-pipeline-parallel-mtr.groovy`

### Package Testing

- **Molecule test runner**: `pxc/jenkins/pxc-package-testing.groovy`
  - Supports: install, upgrade, kmip, kms actions
  - OS coverage: Ubuntu, Debian, RHEL, Oracle Linux, Amazon Linux
- **Parallel orchestrator**: `pxc/jenkins/pxc-package-testing-parallel.groovy`
- **PRO package testing**: `pxc/jenkins/pxc-package-testing-pro.groovy`
- **PRO binary tarballs**: `pxc/jenkins/pxc-binary-tarball-pro.groovy`
- **Keyring tests**: `pxc/jenkins/pxc-keyring-test-pkgs.groovy`

### ProxySQL Testing

- **Package testing**: `pxc/jenkins/proxysql-package-testing.groovy`
- **Molecule testing**: `pxc/jenkins/proxysql-package-testing-molecule.groovy`
- **Full test suite**: `pxc/jenkins/proxysql-package-testing-all.groovy`

### QA Framework

- **QA PXC 8.0**: `pxc/jenkins/qa-pxc80-pipeline.groovy`
- **QA PXC 5.7**: `pxc/jenkins/qa-pxc57-pipeline.groovy`
- **QA PXC 5.6**: `pxc/jenkins/qa-pxc56-pipeline.groovy`
- **Cross-version (5.7→8.0)**: `pxc/jenkins/qa_pxc_57_80_test-pipeline.groovy`

### Cloud Images

- **AWS AMI**: `pxc/jenkins/pxc80-ami.groovy`
- **Azure**: `pxc/jenkins/pxc80-azure.groovy`

### Branch Detection

- **8.0 release detection**: `pxc/jenkins/get-pxc-branches-8.0.groovy` → triggers `pxc80-autobuild-RELEASE`

State stored in: `s3://percona-jenkins-artifactory/pxc/branch_commit_id_80.properties`

## Shared Libraries

### Global shared library (`lib@master`)

All PXC pipelines use:

```groovy
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
```

This exposes helpers from repo root `vars/` (see `vars/AGENTS.md`). Common ones used by PXC jobs:

| Function | Purpose | Used In |
|----------|---------|---------|
| `pushArtifactFolder()` | Upload build artifacts to S3 | All build pipelines |
| `popArtifactFolder()` | Download artifacts from S3 | All build pipelines |
| `uploadRPMfromAWS()` | Upload RPMs to repo | All build pipelines |
| `uploadDEBfromAWS()` | Upload DEBs to repo | All build pipelines |
| `signRPM()` | GPG sign RPMs | All build pipelines |
| `signDEB()` | GPG sign DEBs | All build pipelines |
| `sync2ProdAutoBuild()` | Sync to production repos | All build pipelines |
| `sync2PrivateProdAutoBuild()` | Sync PRO/EOL builds | PRO and 5.7 pipelines |
| `slackNotify()` | Send Slack notifications | All major pipelines |
| `installMoleculeBookworm()` | Install Molecule on Debian 12 | Package testing jobs |
| `moleculeParallelPostDestroy()` | Cleanup Molecule instances | Package testing jobs |

## Agents / Node Labels (used by PXC jobs)

| Label | Purpose | Used In |
|-------|---------|---------|
| `docker-32gb` | Standard Docker (32GB) | Main builds, CI pipelines |
| `docker-32gb-aarch64` | ARM64 Docker | ARM builds |
| `docker` | Basic Docker | Some CI pipelines |
| `micro-amazon` | Lightweight Amazon Linux | Pipeline orchestration |
| `min-bookworm-x64` | Debian 12 (Molecule) | Package testing |
| `min-focal-x64` | Ubuntu 20.04 | Docker build stage |
| `min-jammy-x64` | Ubuntu 22.04 | Upload stage |
| `min-centos-7-x64` | CentOS 7 | AMI builds, keyring tests |

### Hetzner Labels (PXC 5.7 only)

```groovy
// pxc57-pipeline.groovy - Hetzner is DEFAULT
if (params.CLOUD == 'Hetzner') {
    LABEL = 'docker-x64'
    MICRO_LABEL = 'launcher-x64'
} else {
    LABEL = 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
}
```

| Label | Purpose |
|-------|---------|
| `docker-x64` | Hetzner Docker agent |
| `launcher-x64` | Hetzner launcher agent |

## Credentials (IDs you'll see in PXC pipelines)

| Credential ID | Type | Purpose | Used In |
|---------------|------|---------|---------|
| `GITHUB_API_TOKEN` | String | Private repo access, API calls | Main builds |
| `c42456e5-c28d-4962-b32c-b75d161bff27` | AWS | pxc-staging (EC2/S3) | CI pipelines, QA tests |
| `7e252458-7ef8-4d0e-a4d5-5773edcbfa5e` | AWS | Molecule EC2 provisioning | Package testing |
| `MOLECULE_AWS_PRIVATE_KEY` | SSHKey | Molecule EC2 SSH access | Package testing, keyring tests |
| `hub.docker.com` | UsernamePassword | Docker Hub push | PXC 8.0 build |
| `repo.ci.percona.com` | SSHKey | Package upload SSH | PXC 5.7 build |
| `PS_PRIVATE_REPO_ACCESS` | UsernamePassword | EOL repo access | Package testing, ProxySQL |
| `AWS_STASH` | AWS | S3 state storage | Branch detection |
| `re-cd-aws` | AWS | AMI builds | pxc80-ami.groovy |

## Scheduled Jobs (cron) in `pxc/`

**No explicit `cron()` triggers in pxc/ pipeline scripts.**

All pipelines are triggered:
- Manually via Jenkins UI
- Upstream via `build job:` calls
- Branch detection (`get-pxc-branches-8.0.groovy`) stores state in S3 and triggers builds when new releases appear

## Version Matrix

| Version | Status | ARM64 | Hetzner | Notes |
|---------|--------|-------|---------|-------|
| 8.4 LTS | Active | Yes | No | Via `pxc-84-lts` repo |
| 8.x Innovation | Active | Yes | No | Via `pxc-8x-innovation` repo |
| 8.0 | Active (Primary) | Yes | No | Main development |
| 5.7 | EOL/Maintenance | No | **Yes (default)** | Private repo |
| 5.6 | Legacy/Deprecated | No | No | Minimal maintenance |

### PXC_REPO Parameter Options

```groovy
// percona-xtradb-cluster-8.0.groovy
choice(name: 'PXC_REPO', choices: ['pxc-80', 'pxc-8x-innovation', 'pxc-84-lts'])
```

### Product-to-test Options

```groovy
// pxc-package-testing.groovy
choices: ['pxc84', 'pxc80', 'pxc57', 'pxc-innovation-lts']
```

## Operating Systems Tested

**x86_64**:
- Ubuntu: Noble (24.04), Jammy (22.04), Focal (20.04)
- Debian: 13 (Trixie - 8.4 only), 12 (Bookworm), 11 (Bullseye)
- RHEL/Oracle Linux: 8, 9, 10 (8.4 only)
- Amazon Linux: 2023

**ARM64**:
- Ubuntu: Noble, Jammy
- Debian: 12, 13
- RHEL/Oracle Linux: 8, 9, 10
- Amazon Linux: 2023

## Jenkins CLI Quick Reference

```bash
# List all jobs
~/bin/jenkins job pxc list

# Filter by pattern
~/bin/jenkins job pxc list | grep pxc80
~/bin/jenkins job pxc list | grep package-testing

# Get job status
~/bin/jenkins status pxc/pxc-package-testing
~/bin/jenkins status pxc/pxc-5.7-pipeline-hetzner

# Get job parameters
~/bin/jenkins params pxc/pxc80-autobuild-RELEASE

# Trigger a build
~/bin/jenkins build pxc/pxc-package-testing -p product_to_test=pxc80

# View logs
~/bin/jenkins logs pxc/pxc-package-testing
~/bin/jenkins logs pxc/pxc-package-testing -f  # follow mode
```

## Fast Navigation (grep recipes)

```bash
# What calls what (build graph)?
rg -n "build\s+job:" pxc/jenkins

# Find Hetzner-related code
rg -n "Hetzner|CLOUD" pxc/jenkins

# Credentials usage
rg -n "credentialsId:" pxc/jenkins

# All node labels
rg -o "label ['\"]([^'\"]+)['\"]" pxc --no-filename | sort | uniq -c | sort -rn

# Find Molecule usage
rg -n "molecule|Molecule" pxc/jenkins
```

## Git History (fast)

```bash
# Recent PXC changes
git log --oneline --max-count 50 -- pxc

# Most PXC work is Jira-ticketed
git log --oneline -- pxc | rg -n 'PXC-' | head

# Churn hotspots
git log --since='12 months ago' --name-only --pretty=format: HEAD -- pxc \
  | sort | uniq -c | sort -rn | head

# Recent context (subjects only)
git log --since='12 months ago' --pretty=format:'%h %cd %s' --date=short -- pxc | head -n 50

# Follow a file across renames
git log --follow -p -- pxc/jenkins/pxc-package-testing.groovy
```

Recent structural changes (from `git log -- pxc`):
- Added Debian 13 (Trixie) and Amazon Linux 2023 support.
- Added RHEL 10 support for PXC 8.4.
- PXC 5.7 Hetzner support added (PXC-4760).
- Active Choice plugin adoption for parameter selection.
- PRO package testing actively maintained.

### Key Jira Tickets (last 12 months)

| Ticket | Description |
|--------|-------------|
| [PXC-4760](https://perconadev.atlassian.net/browse/PXC-4760) | Add Hetzner support for PXC 5.7 Jenkins jobs |
| [PKG-668](https://perconadev.atlassian.net/browse/PKG-668) | Package testing updates |
| [PKG-475](https://perconadev.atlassian.net/browse/PKG-475) | Private repo support for PRO builds |

### Key Jobs (from Jenkins CLI)

| Job | Builds | Last Run | Status |
|-----|--------|----------|--------|
| `pxc-package-testing` | 2745 | 2025-12-10 | Success |
| `pxc-5.7-pipeline-hetzner` | 79 | 2025-10-20 | Hetzner default |
| `pxc80-autobuild-RELEASE` | 10 | 2024-02-22 | Auto-triggered |

## Local Validation

```bash
# Groovy syntax check
groovy -e "new GroovyShell().parse(new File('pxc/jenkins/pxc80-pipeline.groovy'))"

# Note: Pipelines import Jenkins classes and may not parse in plain Groovy.

# Molecule test (from package-testing repo)
cd molecule/pxc && molecule test -s ubuntu-jammy
```

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Inconsistent wsrep settings | Galera cluster won't form | Keep `wsrep_*` params aligned |
| Skipping multi-node cleanup | Leaves 3-node cluster running | `moleculeParallelPostDestroy()` |
| Wrong `pxc_strict_mode` | SST/IST failures | Match build/test settings |
| Forgetting ProxySQL tests | Breaks load balancer validation | Include in test matrix |
| Using AWS for PXC 5.7 | Hetzner is default now | Set `CLOUD='AWS'` explicitly |

## Notes / Known "gotchas"

- **Hetzner is DEFAULT for PXC 5.7**: The `pxc57-pipeline.groovy` uses Hetzner by default. Set `CLOUD='AWS'` to use AWS.
- **Multi-node Galera testing**: Always use `moleculeParallelPostDestroy()` for cleanup—orphaned nodes are expensive.
- **FIPS mode changes package names**: When `FIPSMODE=YES`, package names change. Downstream jobs must handle both.
- **Branch detection stores state in S3**: If builds aren't triggering, check `s3://percona-jenkins-artifactory/pxc/branch_commit_id_80.properties`.
- **PXC 5.6 is legacy**: Minimal maintenance, may not work with newer tooling.
- **Cross-version testing**: `qa_pxc_57_80_test-pipeline.groovy` tests 5.7→8.0 upgrades.

## Related

- [pdpxc/AGENTS.md](../pdpxc/AGENTS.md) - Percona Distribution for PXC (bundles PXC)
- [proxysql/AGENTS.md](../proxysql/AGENTS.md) - ProxySQL (load balancing for PXC)
- [ps/AGENTS.md](../ps/AGENTS.md) - Percona Server (PXC based on PS)
- [pxb/AGENTS.md](../pxb/AGENTS.md) - XtraBackup (SST and backups for PXC)
- [cloud/AGENTS.md](../cloud/AGENTS.md) - PXC Operator (deploys PXC on K8s)
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared library helpers

## Related GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-xtradb-cluster](https://github.com/percona/percona-xtradb-cluster) | PXC source code |
| [percona/galera](https://github.com/percona/galera) | Galera library |
| [Percona-QA/package-testing](https://github.com/Percona-QA/package-testing) | Molecule test scenarios |
| [percona/proxysql-admin-tool](https://github.com/percona/proxysql-admin-tool) | ProxySQL admin tool |
