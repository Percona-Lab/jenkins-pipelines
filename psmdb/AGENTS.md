# AGENTS.md - PSMDB Pipelines

Extends: [../AGENTS.md](../AGENTS.md) | [../vars/AGENTS.md](../vars/AGENTS.md) | [../cloud/AGENTS.md](../cloud/AGENTS.md)

Related: [pbm/AGENTS.md](../pbm/AGENTS.md) | [pdmdb/AGENTS.md](../pdmdb/AGENTS.md)

## TL;DR (read this first)

- **Main pipelines live in** `psmdb/*.groovy` (test/orchestration) and `psmdb/jenkins/*.groovy` (versioned autobuilds).
- **Jenkins instance**: `psmdb` (https://psmdb.cd.percona.com) with job patterns `hetzner-psmdb*`, `hetzner-pbm*`, `hetzner-pcsm*`, `hetzner-pdmdb*`.
- **Hetzner migration**: Production jobs now use `hetzner-` prefix (e.g., `hetzner-psmdb-multijob-testing`). Original AWS jobs are **DISABLED**. Scripts remain unchanged.
- **Active versions**: Only **6.0, 7.0, 8.0** autobuilds are active. Versions 3.6, 4.0, 4.2, 4.4, 5.0 are disabled.
- **Highest fan-out orchestrators**: `hetzner-psmdb-multijob-testing` (build → test fan-out), `psmdb-multi-parallel` (upgrade matrix, disabled).
- **Highest fan-in dependency**: `hetzner-psmdb-multijob-testing` is called by version autobuild entrypoints → treat its parameter/artifact contract as stable.
- **Cross-product dependency**: `hetzner-psmdb-docker` / `hetzner-psmdb-docker-arm` trigger `hetzner-pbm-functional-tests` → coordinate changes with PBM.
- **High-cost resources**: Molecule scenarios provision cloud resources; always keep `moleculeParallelPostDestroy(...)` and `deleteDir()` in `post { always { ... } }`.

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins instance | `psmdb` |
| URL | https://psmdb.cd.percona.com |
| Total jobs | 209 (111 active, 60 disabled) |
| Infrastructure | Hetzner (migrated from AWS) |
| Common job prefixes | `hetzner-psmdb*`, `hetzner-pbm*`, `hetzner-pcsm*`, `hetzner-pdmdb*` |
| Primary script roots | `psmdb/`, `psmdb/jenkins/` |
| Pipeline groovy scripts | 46 (23 + 23 in jenkins/) |
| Active branch-watchers | `hetzner-psmdb{60,70,80}-autobuild` (`H/15 * * * *`) |
| Weekly CVE scan | `hetzner-psmdb-docker-cve` (`cron('0 0 * * 0')`) |

## Dynamics Snapshot (repo scan + git)

Derived from a local scan of `psmdb/**/*.groovy` (`build job:` calls + labels + credentials), pipeline JJB YAML in `psmdb/**/*.yml` (`script-path` + `triggers`), and `git log --since='12 months ago' -- psmdb` (churn).

- **Largest orchestrators (fan-out by build calls)**:
  - `psmdb-multi-parallel`: 19 build calls (mostly an upgrade matrix calling `psmdb-upgrade-parallel` 18 times).
  - `psmdb-multi`: 9 build calls (`psmdb-upgrade` 8 times + `psmdb` once).
  - `psmdb-multijob-testing`: 7 build calls (integration + docker + tarball + functional).
- **Most-shared dependency (fan-in by distinct callers)**:
  - `psmdb-multijob-testing`: 6 callers (`psmdb42-autobuild`, `psmdb44-autobuild`, `psmdb50-autobuild`, `psmdb60-autobuild`, `psmdb70-autobuild`, `psmdb80-autobuild`).
- **Scheduled “branch watcher” jobs**:
  - `psmdb{40,42,44,50,60,70,80}-autobuild` and `psmdb-autobuild` run every 15 minutes (JJB `timed` trigger) and fan out into build + tests.
- **Cross-product edges**:
  - `psmdb-docker` / `psmdb-docker-arm` → `pbm-functional-tests`.
- **Highest churn files (last 12 months)**:
  - `psmdb/psmdb-tarball-functional.groovy`, `psmdb/psmdb-parallel.groovy`, `psmdb/psmdb-fips.groovy`, `psmdb/psmdb-initsync.groovy`, `psmdb/psmdb-docker.groovy`.

## Job Dependency Graph (LLM-Optimized)

Data source: repository scan only (no Jenkins API). Last updated: 2025-12.

### Node Metrics (repo-derived)

| Job | Tier | Cat | Fan-In (callers) | Fan-Out (unique) | Schedule | Risk |
|-----|------|-----|------------------|------------------|----------|------|
| `psmdb-multijob-testing` | 0 | orch | 6 | 5 | - | CRITICAL |
| `psmdb-multi-parallel` | 0 | orch | 0 | 2 | - | MEDIUM |
| `psmdb-multi` | 0 | orch | 0 | 2 | - | MEDIUM |
| `psmdb{40,42,44,50,60,70,80}-autobuild` | 0 | trigger | 0 | 3 | `H/15 * * * *` | HIGH |
| `psmdb-upgrade-parallel` | 1 | test | 1 | 0 | - | HIGH |
| `psmdb-docker` / `psmdb-docker-arm` | 1 | build | 1 | 1 | - | HIGH |
| `pbm-functional-tests` | 2 | xprod | 2 | 0 | - | HIGH |
| `psmdb-docker-cve` | 1 | scan | 0 | 0 | `0 0 * * 0` | MEDIUM |

Legend: Tier: 0=entry/orch, 1=build/test, 2=external/cross-product | Cat: orch=orchestrator, trigger=scheduled watcher, xprod=cross-product

### Explicit Edges (31 unique edges, 63 calls)

```
psmdb-multi-parallel → psmdb-upgrade-parallel (x18)
psmdb-multi-parallel → psmdb-parallel
psmdb-multi → psmdb-upgrade (x8)
psmdb-multi → psmdb
psmdb-tarball-multi → psmdb-tarball-all-os (x7)
psmdb-multijob-testing → psmdb-integration (x3)
psmdb-multijob-testing → psmdb-docker
psmdb-multijob-testing → psmdb-docker-arm
psmdb-multijob-testing → psmdb-parallel
psmdb-multijob-testing → psmdb-tarball-functional
psmdb-docker → pbm-functional-tests
psmdb-docker-arm → pbm-functional-tests
psmdb40-autobuild → psmdb40-autobuild-RELEASE
psmdb42-autobuild → psmdb42-autobuild-RELEASE
psmdb42-autobuild → psmdb-multijob-testing
psmdb44-autobuild → psmdb44-autobuild-RELEASE
psmdb44-autobuild → psmdb44-aarch64-build
psmdb44-autobuild → psmdb-multijob-testing
psmdb50-autobuild → psmdb50-autobuild-RELEASE
psmdb50-autobuild → psmdb50-aarch64-build
psmdb50-autobuild → psmdb-multijob-testing
psmdb60-autobuild → psmdb60-autobuild-RELEASE
psmdb60-autobuild → psmdb60-aarch64-build
psmdb60-autobuild → psmdb-multijob-testing
psmdb70-autobuild → psmdb70-autobuild-RELEASE
psmdb70-autobuild → psmdb70-aarch64-build
psmdb70-autobuild → psmdb-multijob-testing
psmdb80-autobuild → psmdb80-autobuild-RELEASE
psmdb80-autobuild → psmdb80-aarch64-build
psmdb80-autobuild → psmdb-multijob-testing
psmdb-autobuild → psmdb36-autobuild-RELEASE
```

### Upstream Triggers (scheduled)

**Active (Hetzner)** - these are the production jobs:

| Job | Schedule | Script |
|-----|----------|--------|
| `hetzner-psmdb60-autobuild` | `H/15 * * * *` | `psmdb/jenkins/get-psmdb-branches-6.0.groovy` |
| `hetzner-psmdb70-autobuild` | `H/15 * * * *` | `psmdb/jenkins/get-psmdb-branches-7.0.groovy` |
| `hetzner-psmdb80-autobuild` | `H/15 * * * *` | `psmdb/jenkins/get-psmdb-branches-8.0.groovy` |
| `hetzner-psmdb-docker-cve` | `0 0 * * 0` | `psmdb/psmdb-docker-cve.groovy` |

**Disabled (legacy AWS)** - original jobs, now inactive:

- `psmdb40-autobuild`, `psmdb42-autobuild`, `psmdb44-autobuild`, `psmdb50-autobuild` — EOL versions
- `psmdb60-autobuild`, `psmdb70-autobuild`, `psmdb80-autobuild` — replaced by `hetzner-*` equivalents
- `psmdb-autobuild`, `psmdb-docker-cve` — replaced by `hetzner-*` equivalents

### Stability Tiers

**CRITICAL** (fan-in >= 4): parameter/artifact changes cascade widely
- `psmdb-multijob-testing`: 6 callers

**HIGH** (cross-product or scheduled entrypoints): coordinate changes and verify configs
- `pbm-functional-tests` (called by `psmdb-docker*`)
- `psmdb*-autobuild` (15-min watchers)
- `psmdb-upgrade-parallel` (upgrade matrix runner)

## Where Things Live (directory map)

```
psmdb/
  *.groovy                 # Test/orchestration pipelines (upgrade, tarball, docker, regression, etc)
  *.yml                    # JJB pipeline job configs for the above + legacy templates/params
  jenkins/
    get-psmdb-branches*.groovy  # Scheduled branch watchers (fan out into autobuild + tests)
    percona-server-for-mongodb-*.groovy  # Versioned build pipelines
    percona-mongodb-mongosh*.groovy      # mongosh packaging pipelines
    *.yml                    # JJB pipeline job configs (script-path + triggers)
```

## “What file do I edit?” (fast index)

### Orchestration / matrices

- **Upgrade matrix orchestrator**: `psmdb/psmdb-multi-parallel.groovy`
- **Upgrade orchestrator**: `psmdb/psmdb-multi.groovy`
- **Build → test fan-out**: `psmdb/psmdb-multijob-testing.groovy`
- **Tarball matrix**: `psmdb/psmdb-tarball-multi.groovy`

### Upgrade / functional testing

- **Upgrade runner (parallel)**: `psmdb/psmdb-upgrade-parallel.groovy`
- **Upgrade runner**: `psmdb/psmdb-upgrade.groovy`
- **Package testing (parallel)**: `psmdb/psmdb-parallel.groovy`
- **Integration tests**: `psmdb/psmdb-integration.groovy`

### Docker / security

- **Docker image build**: `psmdb/psmdb-docker.groovy`
- **Docker image build (ARM)**: `psmdb/psmdb-docker-arm.groovy`
- **Docker CVE scan**: `psmdb/psmdb-docker-cve.groovy`
- **Regression suites runner**: `psmdb/psmdb-regression.groovy`

### Tarball / packaging

- **Tarball build pipeline**: `psmdb/psmdb-tarball.groovy`
- **Tarball functional**: `psmdb/psmdb-tarball-functional.groovy`
- **Tarball PRO functional**: `psmdb/psmdb-tarball-pro-functional.groovy`
- **Tarball all OSes**: `psmdb/psmdb-tarball-all-os.groovy`
- **Tarball all setups**: `psmdb/psmdb-tarball-all-setups.groovy`

### Versioned autobuilds + watchers

- **Branch watchers (scheduled)**: `psmdb/jenkins/get-psmdb-branches-*.groovy`
- **Server build pipelines**: `psmdb/jenkins/percona-server-for-mongodb-*.groovy`
- **ARM64 builds**: `psmdb/jenkins/percona-server-for-mongodb-*-aarch64.groovy`
- **mongosh**: `psmdb/jenkins/percona-mongodb-mongosh*.groovy`

## Downstream Build Graphs (what calls what)

These are extracted from `build job:` usage in `psmdb/` pipelines. Some jobs are scheduled via JJB and act as “entrypoints”.

**Branch watcher (example: 8.0)**: `psmdb80-autobuild`
```
psmdb80-autobuild
├── psmdb80-autobuild-RELEASE
├── psmdb80-aarch64-build
└── psmdb-multijob-testing → (see below)
```

**Build → test fan-out**: `psmdb-multijob-testing`
```
psmdb-multijob-testing
├── psmdb-integration
├── psmdb-parallel
├── psmdb-tarball-functional
├── psmdb-docker     → pbm-functional-tests
└── psmdb-docker-arm → pbm-functional-tests
```

**Upgrade matrix**: `psmdb-multi-parallel`
```
psmdb-multi-parallel
├── psmdb-parallel
└── psmdb-upgrade-parallel (x18 parameterized matrix)
```

## Shared Libraries (common confusion)

All PSMDB pipelines load the global shared library:

```groovy
library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])
```

Common helpers used by PSMDB pipelines (see `vars/AGENTS.md`):

- Molecule orchestration: `moleculeExecuteActionWithScenario(...)`, `moleculeParallelPostDestroy(...)`, `moleculePbmJenkinsCreds()`
- OS matrices: `pdmdbOperatingSystems(...)`
- Molecule installers: `installMolecule*()` (varies by distro in pipeline)

## Agents / Node Labels (used by PSMDB jobs)

These labels are used directly in `psmdb/**/*.groovy`:

- `min-bookworm-x64`
- `min-centos-7-x64`
- `docker`, `docker-32gb`, `docker-64gb`, `docker-64gb-aarch64`
- `micro-amazon`
- `master`
- `${params.instance}` (dynamic selection; used by `psmdb-regression.groovy`)

## Credentials (IDs you’ll see in PSMDB pipelines)

Credentials referenced directly in `psmdb/**/*.groovy` (do not rename/remove):

- AWS: `AWS_STASH`, `8468e4e0-5371-4741-a9bb-7c143140acea`
- Repo auth: `PSMDB_PRIVATE_REPO_ACCESS`
- Docker: `hub.docker.com`
- Misc: `VAULT_TRIAL_LICENSE`, `OIDC_ACCESS`, `GITHUB_API_TOKEN`

Many pipelines also call `moleculePbmJenkinsCreds()` (from `vars/`) which injects additional credentials; avoid echoing environment variables or enabling `set -x`.

## Fast Navigation (grep + CLI recipes)

```bash
# What calls what (build graph)?
rg -n \"\\bbuild\\s+job:\" psmdb

# Scheduled branch watchers (JJB triggers)
rg -n \"\\btriggers:|\\btimed:\" psmdb/jenkins --glob '*.yml'

# Credentials usage (by ID)
rg -n \"credentialsId:\" psmdb --glob '*.groovy'

# Agent labels
rg -n \"\\blabel\\s+['\\\"]\" psmdb --glob '*.groovy'
```

### Jenkins CLI

```bash
~/bin/jenkins job psmdb list                        # All jobs
~/bin/jenkins job psmdb list | grep -E '^psmdb(6|7|8)0'   # By version
~/bin/jenkins params psmdb/<job>                    # Parameters
~/bin/jenkins job psmdb config <job> -f yaml        # Job config (script-path is source of truth)
~/bin/jenkins build psmdb/<job> -p KEY=val          # Trigger build
```

## Git History (fast)

```bash
# Recent PSMDB changes
git log --oneline --max-count 50 -- psmdb

# Churn hotspots (helps you pick the right file to open first)
git log --since='12 months ago' --name-only --pretty=format: HEAD -- psmdb \
  | sort | uniq -c | sort -rn | head
```

## Local Validation

```bash
# Groovy syntax check (pick the file you changed)
groovy -e \"new GroovyShell().parse(new File('psmdb/psmdb-multi-parallel.groovy'))\"

# Note: some pipelines depend on Jenkins-specific classes/plugins and won’t parse in plain Groovy.
```

## Notes / Known "gotchas"

### Hetzner Migration

Production jobs migrated from AWS to Hetzner infrastructure (2024-2025). Key points:
- Jenkins jobs now use `hetzner-` prefix (e.g., `hetzner-psmdb-multijob-testing`)
- Scripts in this repo remain unchanged — same `.groovy` files, different Jenkins job names
- Original AWS-based jobs are **DISABLED** but preserved for reference
- See draft PR for migration details

### Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Changing artifact names / repo paths | Breaks PBM / distribution consumers | Coordinate with PBM + RelEng; confirm live job configs |
| Skipping `moleculeParallelPostDestroy()` | Leaves cloud resources running | Always run cleanup in `post { always { ... } }` |
| Removing parameters | Breaks downstream callers | Check all callers + Jenkins configs first |
| Touching `psmdb-docker*` without PBM awareness | Breaks `pbm-functional-tests` | Notify PBM owners and run a safe build |

### PR Review Checklist

- [ ] `buildDiscarder(logRotator(...))` in `options` (required org-wide)
- [ ] `deleteDir()` in `post.always` (required for long-lived agents)
- [ ] `withCredentials()` / declarative `withCredentials(...)` option for all secrets
- [ ] Molecule cleanup paths are present and run on failure
- [ ] No parameter removals; no artifact naming changes without coordination

### Related

- `pbm/AGENTS.md` - Backup integration
- `pdmdb/AGENTS.md` - Distribution packaging
- `vars/AGENTS.md` - Shared helpers (molecule*, pbmVersion, etc.)
