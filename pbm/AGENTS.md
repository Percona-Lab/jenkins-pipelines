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

| Ticket | Summary | Status |
|--------|---------|--------|
| PKG-41 | Nightly Docker builds for K8s operator testing | Done |
| PKG-389 | Multi-arch image rework (`x.y-multi` → `x.y` manifest) | Done |
| PBM-1506 | GCS parallel download for restore performance | Done |

## Current Initiatives

### Docker Pipeline Improvements
- **Nightly builds** (PKG-41): `perconalab/percona-backup-mongodb:nightly` for operator testing
- **Multi-arch rework** (PKG-389): Standardized tagging `x.y` (manifest) + `-amd64`/`-arm64` suffixes
- ARM64 support expanding across all image variants

### Storage Backend Evolution
- **GCS SDK migration** (PBM-1506): Parallel download for faster restores
- Native GCS SDK replacing S3-compatible API
- Related: PBM-1557 (further GCS improvements)

### Driving Forces
- K8s operators (PSMDB/PXC/PGO) require nightly Docker images
- ARM64 adoption driving multi-arch requirements
- Cloud storage performance optimization (large backup SLAs)

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
# List all PBM jobs (AWS and Hetzner)
~/bin/jenkins job psmdb list | rg -i pbm

# List Hetzner-specific jobs
~/bin/jenkins job psmdb list | rg hetzner-pbm

# Get job status
~/bin/jenkins status psmdb/hetzner-pbm-autobuild-RELEASE
~/bin/jenkins status psmdb/hetzner-pbm-functional-tests-full

# Get parameters
~/bin/jenkins params psmdb/hetzner-pbm-autobuild-RELEASE

# Trigger a build (Hetzner is now default)
~/bin/jenkins build psmdb/hetzner-pbm-docker -p PBM_VERSION=2.12.0 -p PBM_REPO_CH=testing

# View logs with build number
~/bin/jenkins logs psmdb/hetzner-pbm-functional-tests-full -b 63

# Check history
~/bin/jenkins history psmdb/hetzner-pbm-autobuild-RELEASE
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
