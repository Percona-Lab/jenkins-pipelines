# AGENTS.md - PS Pipelines

Extends: [../AGENTS.md](../AGENTS.md) | [../vars/AGENTS.md](../vars/AGENTS.md)

## TL;DR (read this first)

- **Main pipelines live in** `ps/jenkins/*.groovy` (25 scripts).
- **No PS-specific shared library**; all pipelines use `lib@master` from repo root `vars/`.
- **Build pipelines are version-specific**: `percona-server-for-mysql-{5.7,8.0,8.0-arm,9.0}.groovy`.
- **Branch detection triggers builds**: `get-ps-branches-*.groovy` poll for new release tags and auto-trigger builds.
- **Package testing uses Molecule**: `ps-package-testing-molecule.groovy` is the central test runner.
- **High-cost resources**: AWS EC2 instances for builds and Molecule tests. Always verify cleanup in `post.always`.
- **Downstream contracts matter**: PS builds are consumed by PXB, PXC, and PDPS pipelines—don't rename artifact paths.

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins instance | `ps80` |
| URL | https://ps80.cd.percona.com |
| Total jobs | ~138 |
| Common job prefixes | `ps*`, `pdps*`, `package-testing-*` |
| Primary script root | `ps/jenkins/` |
| Default credential | `ps-staging` (AWS) |
| AWS region | `us-east-2` |

## Dynamics Snapshot (repo scan + git)

Derived from a local scan of `ps/**/*.groovy` (job-to-job calls) and `git log --since='12 months ago' -- ps` (churn):

- **Largest build pipelines (by LOC)**: `percona-server-for-mysql-8.0.groovy` (1397 lines), `percona-server-for-mysql-9.0.groovy` (906 lines), `percona-server-for-mysql-8.0-arm.groovy` (893 lines).
- **Most-triggered downstream**: `ps-package-testing-molecule` is invoked by the main 8.0 build on success → treat its parameter contract as stable.
- **Branch detection is automatic**: `get-ps-branches-{5.7,8.0,8.1,8.4,9.x}.groovy` store state in S3 (`branch_commit_id_*.properties`) and trigger builds when new release branches appear.
- **Job name aliases exist** (downstream `build job:` names don't always match script filenames). Common ones:
  - `ps8.0-autobuild-RELEASE` → `percona-server-for-mysql-8.0.groovy`
  - `ps5.7-autobuild-RELEASE` → `percona-server-for-mysql-5.7.groovy`
  - `ps9.0-RELEASE` → `percona-server-for-mysql-9.0.groovy`
  - Always confirm via `~/bin/jenkins job ps80 config <job> -f yaml` (script-path is the source of truth).
- **Highest churn files (last 12 months)**: `ps-package-testing-molecule.groovy` (10 commits), `percona-server-for-mysql-8.0.groovy` (9 commits), `ps-package-testing-molecule-parallel.groovy` (6 commits), `test-ps-innodb-cluster*.groovy` (5 commits each).

## Job Dependency Graph (LLM-Optimized)

Data source: Codebase scan of `build job:` calls. Last updated: 2025-12.

### Node Metrics

| Job | Lines | Tier | Cat | Fan-Out | Notes |
|-----|-------|------|-----|---------|-------|
| percona-server-for-mysql-8.0 | 1397 | 1 | build | 2 | Main build, triggers tests + GH Actions |
| percona-server-for-mysql-9.0 | 906 | 1 | build | 0 | Innovation track |
| percona-server-for-mysql-8.0-arm | 893 | 1 | build | 1 | ARM64, triggers package-testing |
| percona-server-for-mysql-5.7 | 654 | 1 | build | 0 | EOL, private repo |
| ps-package-testing-molecule | 371 | 2 | test | 0 | Central Molecule runner |
| test-ps-innodb-cluster-parallel | 430 | 0 | orch | 1 | InnoDB cluster orchestrator |
| ps-package-testing-molecule-parallel | 192 | 0 | orch | 1 | Package testing orchestrator |
| get-ps-branches-8.0 | 110 | 0 | trigger | 1 | Auto-triggers builds |

Legend: Tier: 0=entry/orchestrator, 1=build, 2=test | Cat: orch=orchestrator, build=primary build, test=testing, trigger=polling

### Explicit Edges (from `build job:` calls)

```
get-ps-branches-5.7 → ps5.7-autobuild-RELEASE
get-ps-branches-8.0 → ps8.0-autobuild-RELEASE
get-ps-branches-8.1 → ps8.0-autobuild-RELEASE
get-ps-branches-8.4 → ps8.0-autobuild-RELEASE
get-ps-branches-9.x → ps9.0-RELEASE

percona-server-for-mysql-8.0 (post-success) → ps-package-testing-molecule
percona-server-for-mysql-8.0 (post-success) → PMM_PS.yaml (GitHub Actions)

percona-server-for-mysql-8.0-arm (post-success) → package-testing-ps-innovation-lts

ps-package-testing-molecule-parallel → ps-package-testing-molecule (5x parallel: install, upgrade, kmip, major_upgrade_to, major_upgrade_from)

test-ps-innodb-cluster-parallel → test-ps-innodb-cluster (20 OS variants)

package-testing-ps-8.0-pro → package-testing-ps-8.0-pro-build
```

### Stability Tiers

**CRITICAL** (downstream dependencies from other products):
- Artifact paths in `percona-server-for-mysql-8.0.groovy` - consumed by PXB, PXC, PDPS
- `ps-package-testing-molecule` parameter contract - called by main build

**HIGH** (internal fan-in >= 3):
- `get-ps-branches-*` jobs - any changes affect auto-build detection
- Docker image naming (`perconalab/percona-server:*`) - used by downstream

## Where Things Live (directory map)

```
ps/
  jenkins/                           # All build/test pipelines (25 files)
    percona-server-for-mysql-*.groovy # Core build pipelines (4 versions)
    get-ps-branches-*.groovy          # Branch detection + auto-trigger (5 versions)
    ps-package-testing-molecule*.groovy # Molecule testing
    test-ps-innodb-cluster*.groovy    # InnoDB cluster testing
    package-testing-ps-*.groovy       # Package testing variants
    mysql-shell.groovy                # MySQL Shell builds
    jemalloc.groovy                   # jemalloc dependency build
    qpress.groovy                     # qpress tool build
    ps80-ami.groovy                   # AWS AMI builds
    ps80-azure.groovy                 # Azure image builds
  ps-site-check.groovy               # Website validation (root level)
  ps-site-check.yml                  # JJB config for site check
```

## "What file do I edit?" (fast index)

### Core Build Pipelines

- **PS 8.0 build (main)**: `ps/jenkins/percona-server-for-mysql-8.0.groovy`
  - Builds RPMs, DEBs, tarballs, Docker images
  - Triggers `ps-package-testing-molecule` and GitHub Actions on success
  - Supports FIPS mode via `FIPSMODE` parameter
- **PS 8.0 ARM64 build**: `ps/jenkins/percona-server-for-mysql-8.0-arm.groovy`
- **PS 9.x build (innovation)**: `ps/jenkins/percona-server-for-mysql-9.0.groovy`
- **PS 5.7 build (EOL)**: `ps/jenkins/percona-server-for-mysql-5.7.groovy` (uses private repo)

### Branch Detection (Auto-Triggers)

- **8.0 release detection**: `ps/jenkins/get-ps-branches-8.0.groovy` → triggers `ps8.0-autobuild-RELEASE`
- **8.1 release detection**: `ps/jenkins/get-ps-branches-8.1.groovy` → triggers `ps8.0-autobuild-RELEASE`
- **8.4 release detection**: `ps/jenkins/get-ps-branches-8.4.groovy` → triggers `ps8.0-autobuild-RELEASE`
- **9.x release detection**: `ps/jenkins/get-ps-branches-9.x.groovy` → triggers `ps9.0-RELEASE`
- **5.7 release detection**: `ps/jenkins/get-ps-branches-5.7.groovy` → triggers `ps5.7-autobuild-RELEASE`

State stored in: `s3://percona-jenkins-artifactory/percona-server/branch_commit_id_*.properties`

### Package Testing

- **Molecule test runner (central)**: `ps/jenkins/ps-package-testing-molecule.groovy`
  - Runs Molecule scenarios on EC2 instances
  - Supports: install, upgrade, kmip, major_upgrade_to, major_upgrade_from
- **Molecule orchestrator (parallel)**: `ps/jenkins/ps-package-testing-molecule-parallel.groovy`
- **Pro package testing**: `ps/jenkins/package-testing-ps-8.0-pro*.groovy` (3 files)
- **5.7 package testing**: `ps/jenkins/package-testing-ps-5.7.groovy`, `package-testing-ps-build-5.7.groovy`

### InnoDB Cluster Testing

- **Single-OS runner**: `ps/jenkins/test-ps-innodb-cluster.groovy`
- **Parallel orchestrator (20 OS variants)**: `ps/jenkins/test-ps-innodb-cluster-parallel.groovy`

### Dependency Builds

- **MySQL Shell**: `ps/jenkins/mysql-shell.groovy` (build for all platforms)
- **jemalloc**: `ps/jenkins/jemalloc.groovy` (memory allocator dependency)
- **qpress**: `ps/jenkins/qpress.groovy` (compression tool)

### Cloud Images

- **AWS AMI**: `ps/jenkins/ps80-ami.groovy` (Packer-based, watches percona-images repo)
- **Azure**: `ps/jenkins/ps80-azure.groovy`

## Downstream Build Graphs (what calls what)

**PS 8.0 build (post-success flow)**:
```
percona-server-for-mysql-8.0
├── [on success] ps-package-testing-molecule (install action)
├── [on success] PMM_PS.yaml GitHub Actions workflow
├── [parallel] minitests on multiple nodes
└── [parallel] docker_test()
```

**Branch detection flow**:
```
get-ps-branches-8.0 (polls GitHub)
└── [if new release] ps8.0-autobuild-RELEASE
    └── percona-server-for-mysql-8.0.groovy
```

**Package testing orchestration**:
```
ps-package-testing-molecule-parallel
├── ps-package-testing-molecule (action=install)
├── ps-package-testing-molecule (action=upgrade)
├── ps-package-testing-molecule (action=kmip)
├── ps-package-testing-molecule (action=major_upgrade_to)
└── ps-package-testing-molecule (action=major_upgrade_from)
```

**InnoDB cluster orchestration**:
```
test-ps-innodb-cluster-parallel
└── test-ps-innodb-cluster (×20 OS variants: ubuntu-noble, debian-12, rhel-9, etc.)
```

## Shared Libraries

### Global shared library (`lib@master`)

All PS pipelines use:

```groovy
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _
```

This exposes helpers from repo root `vars/` (see `vars/AGENTS.md`). Common ones used by PS jobs:

| Function | Purpose | Used In |
|----------|---------|---------|
| `pushArtifactFolder()` | Upload build artifacts to S3 | All build pipelines |
| `popArtifactFolder()` | Download artifacts from S3 | All build pipelines |
| `uploadRPMfromAWS()` | Upload RPMs to repo | All build pipelines |
| `uploadDEBfromAWS()` | Upload DEBs to repo | All build pipelines |
| `signRPM()` | GPG sign RPMs | All build pipelines |
| `signDEB()` | GPG sign DEBs | All build pipelines |
| `sync2ProdAutoBuild()` | Sync to production repos | All build pipelines |
| `sync2PrivateProdAutoBuild()` | Sync Pro/EOL builds | Pro and 5.7 pipelines |
| `slackNotify()` | Send Slack notifications | All major pipelines |
| `moleculePdpsJenkinsCreds()` | Molecule AWS credentials | Package testing jobs |
| `ps80PackageTesting()` | OS list for PS 8.0 testing | Molecule jobs |
| `ps84PackageTesting()` | OS list for PS 8.4 testing | Molecule jobs |
| `ps57PackageTesting()` | OS list for PS 5.7 testing | Molecule jobs |
| `ps90PackageTesting()` | OS list for PS 9.0 testing | Molecule jobs |

## Agents / Node Labels (used by PS jobs)

These come directly from `ps/**/*.groovy` and are backed by specific infra. Don't "simplify" them.

| Label | Purpose | Key Files |
|-------|---------|-----------|
| `docker` | General Docker-capable nodes (39 uses) | Orchestrator jobs, branch detection |
| `docker-32gb` | High-memory Docker (5 uses) | mysql-shell, qpress, jemalloc |
| `docker-32gb-aarch64` | ARM64 Docker (43 uses) | ARM builds in all main pipelines |
| `min-jammy-x64` | Ubuntu 22.04 x64 (18 uses) | Package upload, builds |
| `min-ol-9-x64` | Oracle Linux 9 x64 (16 uses) | Builds, minitests |
| `min-focal-x64` | Ubuntu 20.04 x64 (15 uses) | Source tarball creation |
| `min-centos-7-x64` | CentOS 7 x64 (14 uses) | Source RPM, builds |
| `min-ol-8-x64` | Oracle Linux 8 x64 (12 uses) | Builds, minitests |
| `min-bookworm-x64` | Debian 12 x64 (11 uses) | Molecule controller, builds |
| `min-bullseye-x64` | Debian 11 x64 (6 uses) | Builds, minitests |
| `min-buster-x64` | Debian 10 x64 (6 uses) | Source DEB creation |
| `min-bionic-x64` | Ubuntu 18.04 x64 (5 uses) | PS 5.7 builds |
| `min-noble-x64` | Ubuntu 24.04 x64 (3 uses) | Builds, minitests |

## Credentials (IDs you'll see in PS pipelines)

| Credential ID | Type | Purpose | Used In |
|---------------|------|---------|---------|
| `AWS_STASH` | AWS | S3 state storage for branch detection | `get-ps-branches-*.groovy` |
| `GITHUB_API_TOKEN` | String | Private repo access, API calls | All main builds |
| `hub.docker.com` | UsernamePassword | Docker Hub push | `percona-server-for-mysql-8.0.groovy` |
| `Github_Integration` | String | GitHub Actions trigger | `percona-server-for-mysql-8.0.groovy` |
| `MOLECULE_AWS_PRIVATE_KEY` | SSHKey | Molecule EC2 SSH access | Molecule tests, InnoDB cluster |
| `5d78d9c7-2188-4b16-8e31-4d5782c6ceaa` | AWS | Molecule EC2 provisioning | Molecule tests |
| `PS_PRIVATE_REPO_ACCESS` | UsernamePassword | Pro repo access | Pro package testing, 5.7 tests |
| `repo.ci.percona.com` | SSHKey | Package upload SSH | `percona-server-for-mysql-5.7.groovy` |
| `re-cd-aws` | AWS | AMI builds | `ps80-ami.groovy` |

## Scheduled Jobs (cron) in `ps/`

**No explicit `cron()` triggers in ps/ pipeline scripts.**

Branch detection jobs (`get-ps-branches-*.groovy`) are likely scheduled externally via Jenkins UI or Job DSL. They poll GitHub for new release branches and auto-trigger builds.

## Fast Navigation (grep recipes)

```bash
# What calls what (build graph)?
rg -n "\\bbuild\\s+job:" ps/jenkins

# Where is a given job name referenced?
rg -n "'ps8.0-autobuild-RELEASE'" ps/jenkins

# Credentials usage
rg -n "credentialsId:" ps/jenkins

# All node labels
rg -o "label ['\"]([^'\"]+)['\"]" ps --no-filename | sort | uniq -c | sort -rn

# Which scripts use specific shared library functions?
rg -n "pushArtifactFolder|popArtifactFolder" ps/jenkins
rg -n "moleculePdpsJenkinsCreds|moleculeParallelTest" ps/jenkins
```

## Git History (fast)

```bash
# Recent PS changes
git log --oneline --max-count 50 -- ps

# Most PS work is Jira-ticketed
git log --oneline -- ps | rg -n 'PKG-' | head

# Churn hotspots (helps pick the right file to open first)
git log --since='12 months ago' --name-only --pretty=format: HEAD -- ps \
  | sort | uniq -c | sort -rn | head

# Who touched PS pipelines recently (rough ownership signal)
git shortlog -sne --since='12 months ago' HEAD -- ps | head

# Follow a file across renames
git log --follow -p -- ps/jenkins/percona-server-for-mysql-8.0.groovy
```

Recent structural changes worth knowing (from `git log -- ps`):
- Added Debian 13 (Trixie) and Amazon Linux 2023 support.
- Added RHEL 10 in PS, PDPS, and Pro jobs.
- PS 5.7 package testing migrated to Molecule.
- PS 9.x innovation track added with dedicated jobs.
- Active Choice plugin adoption for parameter selection.

## Local Validation

```bash
# Groovy syntax check (pick the file you changed)
groovy -e "new GroovyShell().parse(new File('ps/jenkins/percona-server-for-mysql-8.0.groovy'))"

# Note: Pipelines import Jenkins classes or use @Library(...) and may not parse in plain Groovy.

# Molecule test (from package-testing repo)
cd molecule/ps && molecule test -s ubuntu-jammy
```

## Version Matrix

| Version | Status | ARM64 | Docker | Notes |
|---------|--------|-------|--------|-------|
| 9.x | Innovation | Yes | Yes | Preview/experimental |
| 8.4 | LTS | Yes | Yes | Current LTS |
| 8.0 | Active | Yes | Yes | Primary, FIPS support |
| 5.7 | EOL | No | Yes | Private repo, maintenance only |

## Operating Systems Tested

**x86_64**: Ubuntu Noble/Jammy, Debian 11/12/13, Oracle Linux 8/9, RHEL 8/9/10, Amazon Linux 2023

**ARM64**: RHEL 8/9/10, Amazon Linux 2023, Debian 11/12/13, Ubuntu Jammy/Noble

## Notes / Known "gotchas"

- **FIPS mode changes artifact names**: When `FIPSMODE=YES`, package names change from `percona-server-*` to `percona-server-pro-*`. Downstream jobs must handle both.
- **Branch detection stores state in S3**: If builds aren't triggering, check `s3://percona-jenkins-artifactory/percona-server/branch_commit_id_*.properties`.
- **Docker multi-arch manifests**: The 8.0 build creates and pushes multi-arch manifests. If Docker push fails, cleanup may be needed.
- **Molecule tests provision EC2**: Always ensure cleanup happens in `post.always`. Orphaned instances can be found via AWS console.
- **PS is upstream for PXB/PXC/PDPS**: Artifact path or naming changes in PS builds can break downstream product builds. Coordinate changes.
- **Private repo access**: Pro and 5.7 EOL builds use `PS_PRIVATE_REPO_ACCESS` and `GITHUB_API_TOKEN` for private GitHub repos.
- **InnoDB cluster tests**: The parallel orchestrator runs 20 OS variants. Failures are often OS-specific—check individual job logs.

## Related

- [pdps/AGENTS.md](../pdps/AGENTS.md) - Percona Distribution for PS (bundles PS)
- [pxb/AGENTS.md](../pxb/AGENTS.md) - XtraBackup (backs up PS)
- [pxc/AGENTS.md](../pxc/AGENTS.md) - XtraDB Cluster (based on PS)
- [pmm/AGENTS.md](../pmm/AGENTS.md) - PMM integration (monitors PS)
- [cloud/AGENTS.md](../cloud/AGENTS.md) - PS Operator (deploys PS on K8s)
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared library helpers

## Related GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-server](https://github.com/percona/percona-server) | Main PS source code |
| [percona/percona-server-private](https://github.com/percona/percona-server-private) | Pro/FIPS builds (private) |
| [Percona-Lab/ps-build](https://github.com/Percona-Lab/ps-build) | Build scripts collection |
| [Percona-QA/package-testing](https://github.com/Percona-QA/package-testing) | Molecule test scenarios |
