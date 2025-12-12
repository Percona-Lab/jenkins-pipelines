# AGENTS.md - PBM Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona Backup for MongoDB - builds, package testing, backup/restore on RS/sharded clusters
**Where**: Jenkins `psmdb` | `https://psmdb.cd.percona.com` | Jobs: `pbm*`, `hetzner-pbm-*`
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `moleculePbmJenkinsCreds()`, `pbmVersion()`
**Watch Out**: Creates EC2 + S3 resources - always cleanup; coordinate version params with PSMDB

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `psmdb` |
| URL | https://psmdb.cd.percona.com |
| Job Patterns | `pbm-*`, `hetzner-pbm-*`, `pbm-functional-*` |
| Default Credential | `8468e4e0-5371-4741-a9bb-7c143140acea` (AWS) |
| AWS Region | `us-east-2` |
| Groovy Files | 16 (root) + 3 (jenkins/) |
| Last Updated | 2025-12 |

## Job Dependency Graph

```
get-pbm-branches.groovy
   │
   ├── pbm-autobuild-RELEASE (trigger: downstream)
   │
   └── pbm-release-test-run (trigger: downstream)
           │
           ├── pbm-functional-tests-full
           ├── pbm-pkg-install-parallel
           └── pbm-docker

pbm-e2e-tests.groovy (standalone, matrix execution)
   │
   └── Parallel: PSMDB 6.0/7.0 × SHARD 0/1/2
```

**Hetzner Migration**: Jobs prefixed with `hetzner-pbm-*` run on Hetzner infrastructure.

## Directory Map

```
pbm/                                    # 2,560 lines total
├── AGENTS.md                           # This file
├── jenkins/                            # 3 files, 627 lines
│   ├── percona-mongodb-backup.groovy     (280) # Main build
│   ├── percona-mongodb-backup-aarch64.groovy (241) # ARM64 build
│   └── get-pbm-branches.groovy           (106) # Branch discovery
├── pbm-e2e-tests.groovy              (281) # End-to-end testing
├── pbm-docker-arm.groovy             (205) # ARM64 Docker builds
├── pbm-pkg-upgrade.groovy            (149) # Package upgrades
├── pbm-docker.groovy                 (140) # Docker image builds
├── pbm-pkg-upgrade-parallel.groovy   (137) # Parallel upgrades
├── pbm-functional-aws-sharded.groovy (127) # Sharded cluster tests
├── pbm-functional-aws-rs.groovy      (127) # Replica set tests
├── pbm-manual.groovy                 (108) # Manual builds
├── pbm-functional-tests-full.groovy   (98) # Full test suite
├── pbm-functional-tests.groovy        (97) # Functional tests
├── pbm-docker-nightly.groovy          (89) # Nightly Docker builds
├── pbm-pkg-install*.groovy                 # Package installation
├── pbm-site-check.groovy              (68) # Site validation
└── pbm-release-test-run.groovy        (40) # Release orchestrator
```

## Key Jobs from Jenkins

| Job | Builds | Status | Purpose |
|-----|--------|--------|---------|
| `hetzner-pbm-functional-tests-full` | 63 | UNSTABLE | Full test suite (Hetzner) |
| `hetzner-pbm-autobuild-RELEASE` | - | SUCCESS | Release builds (Hetzner) |
| `hetzner-pbm-docker` | - | SUCCESS | Docker builds (Hetzner) |
| `pbm-autobuild-RELEASE` | 153 | ABORTED | Release builds (AWS) |
| `pbm-aarch64-build` | - | FAILED | ARM64 builds |

## Storage Backends

| Backend | Credential ID | Config File |
|---------|---------------|-------------|
| AWS S3 | `PBM-AWS-S3` | `PBM_AWS_S3_YML` |
| GCS (S3 API) | `PBM-GCS-S3` | `PBM_GCS_S3_YML` |
| GCS (HMAC) | `PBM-GCS-HMAC-S3` | `PBM_GCS_HMAC_S3_YML` |
| Azure Blob | `PBM-AZURE` | `PBM_AZURE_YML` |
| MinIO | (local setup) | via OSS storage type |

## Credentials

| ID | Purpose | Used In |
|----|---------|---------|
| `8468e4e0-5371-4741-a9bb-7c143140acea` | AWS ECR/S3 | pbm-docker |
| `hub.docker.com` | Docker Hub push | pbm-docker, pbm-docker-arm |
| `PBM-AWS-S3` | S3 storage config | pbm-e2e-tests |
| `PBM-GCS-S3` | GCS storage config | pbm-e2e-tests |
| `PBM-AZURE` | Azure storage config | pbm-e2e-tests |

## Agent Labels

| Label | Purpose | Files |
|-------|---------|-------|
| `docker` | Standard builds | pbm-e2e-tests (most stages) |
| `docker-32gb` | Memory-intensive | pbm-docker, pbm-e2e-tests (sharded) |
| `min-bookworm-x64` | Package testing | pbm-pkg-install, pbm-pkg-upgrade |
| `micro-amazon` | Lightweight | pbm-e2e-tests (init) |

## Key Jira Tickets

| Ticket | Summary |
|--------|---------|
| PBM-1506 | Add two different setups for GCS |
| PKG-389 | Docker images manifest retagged (multi-arch) |
| PKG-41 | Add nightly PBM Docker build |

## Backup Coverage

| Backup Type | Topology |
|-------------|----------|
| Logical | Standalone, RS, Sharded |
| Physical | RS, Sharded |
| PITR | RS, Sharded |
| Selective | RS |

## MongoDB Topologies

| Topology | Tested In | PSMDB Versions |
|----------|-----------|----------------|
| Replica Set | `pbm-functional-aws-rs` | 6.0, 7.0 |
| Sharded | `pbm-functional-aws-sharded` | 6.0, 7.0 |
| Standalone | `pbm-functional-tests` | 6.0, 7.0 |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Missing `moleculePbmJenkinsCreds()` | SSH auth fails | Add before Molecule steps |
| S3 bucket not cleaned | Costs accumulate | Delete in `post.always` |
| PSMDB version mismatch | Incompatible versions | Align `PBM_BRANCH` with `PSMDB_BRANCH` |
| Skipping sharded tests | Incomplete coverage | Include in test matrix |
| Ignoring Hetzner jobs | Missing migration context | Check both AWS and Hetzner jobs |

## Jenkins CLI Quick Reference

```bash
# List all PBM jobs
~/bin/jenkins job psmdb list | grep pbm

# Get job status
~/bin/jenkins status psmdb/hetzner-pbm-functional-tests-full

# Get parameters
~/bin/jenkins params psmdb/pbm-autobuild-RELEASE

# Trigger a build
~/bin/jenkins build psmdb/pbm-docker -p PBM_VERSION=2.9.0 -p PBM_REPO_CH=testing

# View logs
~/bin/jenkins logs psmdb/hetzner-pbm-functional-tests-full -b 63

# Check Hetzner jobs
~/bin/jenkins job psmdb list | grep hetzner-pbm
```

## Local Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('pbm/pbm-functional-tests.groovy'))"

# Molecule smoke
cd molecule/pbm && molecule test -s aws-sharded

# Local PBM test
docker run -it percona/pbm:latest pbm --help
```

## PR Review Checklist

- [ ] `buildDiscarder(logRotator(...))` in options
- [ ] `deleteDir()` in `post.always`
- [ ] `moleculePbmJenkinsCreds()` present
- [ ] S3 bucket cleanup
- [ ] RS and sharded topology coverage
- [ ] PSMDB version alignment
- [ ] All storage backends tested (S3, GCS, Azure)

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Storage backends | Test coverage | QA |
| PBM version params | PSMDB tests | MongoDB team |
| S3 paths | Backup locations | Ops team |
| Docker tags | Downstream users | RelEng |

## Related

- [psmdb/AGENTS.md](../psmdb/AGENTS.md) - MongoDB server (PBM depends on PSMDB)
- [pdmdb/AGENTS.md](../pdmdb/AGENTS.md) - Distribution packaging
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers (pbmVersion, moleculePbm*)

## GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-backup-mongodb](https://github.com/percona/percona-backup-mongodb) | Main source |
| [Percona-QA/psmdb-testing](https://github.com/Percona-QA/psmdb-testing) | Test scenarios |
