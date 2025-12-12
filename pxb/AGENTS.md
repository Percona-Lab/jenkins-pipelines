# AGENTS.md - PXB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona XtraBackup builds (2.4, 8.0, 8.4, 9.x), backup/restore testing, PS/PXC compatibility
**Where**: Jenkins `pxb` | `https://pxb.cd.percona.com` | Jobs: `pxb*`, `percona-xtrabackup-*`
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `launchSpotInstance()`, `ccache*()`
**Watch Out**: Must test against PS and PXC releases; coordinate artifact naming with downstream

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `pxb` |
| URL | https://pxb.cd.percona.com |
| Job Patterns | `pxb80-*`, `pxb24-*`, `pxb-*-testing`, `percona-xtrabackup-*` |
| Default Credential | `pxb-staging` (AWS) |
| AWS Region | `us-east-2` |
| Groovy Files | 11 (jenkins/) + 10 (v2/jenkins/) |
| Last Updated | 2025-12 |

## Job Dependency Graph

```
pxb-80.groovy ──────────────────┐
   │                            │
   ├── pxb-package-testing-all ─┘ (trigger: downstream)
   │
pxb-tarball-molecule.groovy
   │
   └── pxb-package-testing-molecule-all (trigger: downstream)

percona-xtrabackup-8.0-compile-pipeline (v2)
   │
   └── percona-xtrabackup-8.0-test-pipeline (v2)
```

**Parallel Execution Pattern**: All molecule tests run parallel across distributions.

## Directory Map

```
pxb/                                    # 3.5k lines total
├── AGENTS.md                           # This file
├── Makefile                            # Build helpers
├── pxb-site-check.groovy               # Site validation
├── *.yml                               # Job-DSL templates (2.3, 2.4, 8.0)
├── jenkins/                            # 11 groovy files, 3,526 lines
│   ├── pxb-80.groovy            (846)  # PXB 8.0 build pipeline
│   ├── pxb-9x.groovy            (830)  # PXB 9.x innovation builds
│   ├── pxb-24.groovy            (298)  # PXB 2.4 maintenance
│   ├── pxb-pt-testing-molecule.groovy (268) # Package testing
│   ├── pxb-80-arm.groovy        (251)  # ARM64 builds
│   ├── pxb-pt-testing-molecule-kmip.groovy (236) # KMIP encryption testing
│   ├── pxb-tarball-molecule.groovy (232) # Tarball validation
│   ├── pxb-24-arm.groovy        (173)  # PXB 2.4 ARM64
│   ├── pxb80-single-platform-run.groovy (133) # Single platform
│   ├── pxb24-single-platform-run.groovy (133) # Single platform
│   └── pxb-pt-testing-molecule-all.groovy (126) # Full matrix
└── v2/                                 # Next-gen pipelines
    ├── docker/                         # Build containers
    ├── jenkins/                        # 10 groovy files (compile/test)
    │   ├── percona-xtrabackup-8.0-*.groovy
    │   ├── percona-xtrabackup-8.1-*.groovy
    │   └── percona-xtrabackup-2.4-*.groovy
    └── local/                          # Local testing
```

## Key Jobs from Jenkins

| Job | Builds | Status | Purpose |
|-----|--------|--------|---------|
| `pxb-package-testing-molecule` | 259 | FAILURE | Package validation (Molecule) |
| `pxb-tarball-molecule` | 142 | SUCCESS | Tarball testing |
| `pxb-9x-testing` | 101 | SUCCESS | PXB 9.x innovation testing |
| `percona-xtrabackup-8.0-compile-param` | - | SUCCESS | Compile builds |
| `pxb-kmip-package-testing-molecule` | - | FAILURE | KMIP encryption tests |

## Version Matrix

| Version | Status | ARM64 | Pipeline |
|---------|--------|-------|----------|
| 9.x | Innovation | Yes | `pxb-9x.groovy` |
| 8.4 | LTS | Yes | `pxb-80.groovy` |
| 8.0 | Active | Yes | `pxb-80.groovy` |
| 2.4 | Maintenance | Yes | `pxb-24.groovy` |
| 2.3 | Legacy | No | Job-DSL templates only |

## Credentials

| ID | Purpose | Used In |
|----|---------|---------|
| `c42456e5-c28d-4962-b32c-b75d161bff27` | AWS (default) | molecule tests |
| `24e68886-c552-4033-8503-ed85bbaa31f3` | AWS (single-platform) | single-platform-run |
| `MOLECULE_AWS_PRIVATE_KEY` | SSH key | Molecule instances |
| `PS_PRIVATE_REPO_ACCESS` | PRO repo access | PRO/Enterprise builds |
| `GITHUB_API_TOKEN` | GitHub API | Branch checks |

## Agent Labels

| Label | Purpose | Files |
|-------|---------|-------|
| `docker-32gb` | Standard x64 builds | pxb-80, pxb-24 |
| `docker-32gb-aarch64` | ARM64 builds | pxb-80-arm, pxb-24-arm |
| `min-bookworm-x64` | Molecule controller | pxb-tarball-molecule |

## Key Jira Tickets

| Ticket | Summary |
|--------|---------|
| PKG-682 | Update platforms for PXB Jenkins jobs (RHEL 10, Debian 13) |
| PKG-575 | Fix var declarations for 8.4 jobs |
| PKG-475 | Update PXB release pipeline for PRO builds (private repos) |
| PKG-256 | PXB 9.1.0 packaging tasks |
| RM-1471 | PXB 8.0.35-32 release |

## Backup Testing

PXB must validate:
- Full, incremental, compressed, encrypted backups
- Streaming (SSH/S3/remote storage)
- Physical + logical restore flows
- KMIP encryption (via `pxb-pt-testing-molecule-kmip.groovy`)

## Compatibility Matrix

| Target | Versions |
|--------|----------|
| Percona Server | 5.7, 8.0, 8.4, 9.x |
| PXC | 5.7, 8.0, 8.4 |
| MySQL Community | 5.7, 8.0 |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Breaking artifact names | PS/PXC builds depend on them | Coordinate downstream |
| Missing PS/PXC compat | Incomplete coverage | Include in test matrix |
| ccache not uploaded | Slower builds | Upload in `post.success` |
| S3 bucket leaks | Costs add up | Delete temp buckets |
| Ignoring KMIP tests | Enterprise feature | Include in PRO testing |

## Jenkins CLI Quick Reference

```bash
# List all PXB jobs
~/bin/jenkins job pxb list | rg -i pxb

# Get job status
~/bin/jenkins status pxb/pxb-package-testing-molecule
~/bin/jenkins status pxb/pxb-9x-testing

# Get parameters (compile pipeline)
~/bin/jenkins params pxb/percona-xtrabackup-8.0-compile-param

# Trigger a build
~/bin/jenkins build pxb/pxb-tarball-molecule -p PXB_VERSION=8.4.0-4 -p REPO_TYPE=PRO

# View logs with build number
~/bin/jenkins logs pxb/pxb-package-testing-molecule -b 259

# Check history
~/bin/jenkins history pxb/pxb-9x-testing
~/bin/jenkins history pxb/pxb-tarball-molecule
```

## Local Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('pxb/jenkins/pxb-80.groovy'))"

# Molecule smoke
cd molecule/pxb && molecule test -s ubuntu-jammy

# v2 pipeline syntax
groovy -e "new GroovyShell().parse(new File('pxb/v2/jenkins/percona-xtrabackup-8.0-compile-pipeline.groovy'))"
```

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Artifact path | PS/PXC builds break | ps-team, pxc-team |
| Version support | Compat matrix | QA |
| S3 paths | Release automation | RelEng |
| KMIP config | Enterprise features | Security team |

## Related

- [ps/AGENTS.md](../ps/AGENTS.md) - Percona Server (depends on PXB)
- [pxc/AGENTS.md](../pxc/AGENTS.md) - XtraDB Cluster (depends on PXB)
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers (molecule*, ccache*)

## GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-xtrabackup](https://github.com/percona/percona-xtrabackup) | Main source |
| [Percona-QA/package-testing](https://github.com/Percona-QA/package-testing) | Test scenarios |
