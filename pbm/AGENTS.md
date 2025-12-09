# AGENTS.md - PBM Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona Backup for MongoDB - builds, package testing, backup/restore on RS/sharded clusters
**Where**: Jenkins `psmdb` | `https://psmdb.cd.percona.com` | Jobs: `pbm*`
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `moleculePbmJenkinsCreds()`, `pbmVersion()`
**Watch Out**: Creates EC2 + S3 resources - always cleanup; coordinate version params with PSMDB

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `psmdb` |
| URL | https://psmdb.cd.percona.com |
| Job Patterns | `pbm-*`, `pbm-functional-*`, `pbm-docker*` |
| Default Credential | `pbm-staging` or `psmdb-staging` (AWS) |
| AWS Region | `us-east-2` |
| Groovy Files | 19 |

## Scope

Percona Backup for MongoDB (PBM) pipelines: agent builds, package testing, functional backup/restore on replica sets and sharded clusters, Molecule scenarios, and PBM-PSMDB integration.

## Key Files

| File | Purpose |
|------|---------|
| `jenkins/percona-mongodb-backup.groovy` | PBM build |
| `jenkins/percona-mongodb-backup-aarch64.groovy` | ARM64 build |
| `pbm-functional-tests.groovy` | Functional tests |
| `pbm-functional-tests-full.groovy` | Full test suite |
| `pbm-e2e-tests.groovy` | E2E testing |
| `pbm-docker.groovy` | Docker image builds |
| `pbm-pkg-upgrade.groovy` | Package upgrade testing |

## Key Jenkins Jobs

| Job Name | Purpose |
|----------|---------|
| `pbm-build` | Build PBM packages |
| `pbm-package-testing` | Package validation |
| `pbm-functional-aws-rs` | AWS replica set tests |
| `pbm-functional-aws-sharded` | AWS sharded tests |
| `pbm-e2e-tests` | End-to-end testing |

## Backup Coverage

| Backup Type | Topology |
|-------------|----------|
| Logical | Standalone, RS, Sharded |
| Physical | RS, Sharded |
| PITR | RS, Sharded |
| Selective | RS |

## Storage Backends

- S3-compatible (AWS S3, MinIO)
- Filesystem (local, NFS)
- GCS (Google Cloud Storage)
- Azure Blob

## Molecule

```bash
# PBM-specific credentials
moleculePbmJenkinsCreds()

# Run Molecule scenarios
cd molecule/pbm && molecule test -s aws-sharded

# Helpers
moleculeExecuteActionWithScenario(params)
moleculeParallelPostDestroy()
pbmVersion()
```

## MongoDB Topologies

| Topology | Tested In |
|----------|-----------|
| Standalone | `pbm-functional-standalone` |
| Replica Set | `pbm-functional-aws-rs` |
| Sharded | `pbm-functional-aws-sharded` |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Missing `moleculePbmJenkinsCreds()` | SSH auth fails | Add before Molecule steps |
| S3 bucket not cleaned | Costs accumulate | Delete in `post.always` |
| PSMDB version mismatch | Incompatible versions | Align `PBM_BRANCH` with `PSMDB_BRANCH` |
| Skipping sharded tests | Incomplete coverage | Include in test matrix |

## Agent Workflow

1. **Check live config**: `~/bin/jenkins params psmdb/pbm-functional-aws-rs`
2. **Credentials**: Use `moleculePbmJenkinsCreds()` for Molecule
3. **Topology coverage**: Test RS AND sharded clusters
4. **Storage backends**: Test S3 and filesystem minimum
5. **Cleanup**: Delete S3 buckets + EC2 instances in `post`

## PR Review Checklist

- [ ] `buildDiscarder(logRotator(...))` in options
- [ ] `deleteDir()` in `post.always`
- [ ] `moleculePbmJenkinsCreds()` present
- [ ] S3 bucket cleanup
- [ ] RS and sharded topology coverage
- [ ] PSMDB version alignment

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Storage backends | Test coverage | QA |
| PBM version params | PSMDB tests | MongoDB team |
| S3 paths | Backup locations | Ops team |

## Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('pbm/pbm-functional-tests.groovy'))"

# Molecule smoke
cd molecule/pbm && molecule test -s aws-sharded

# Local PBM test
docker run -it percona/pbm:latest pbm --help
```

## Jenkins CLI

```bash
~/bin/jenkins job psmdb list | grep pbm       # All PBM jobs
~/bin/jenkins params psmdb/pbm-functional-aws-rs  # Parameters
~/bin/jenkins build psmdb/pbm-build -p KEY=val    # Build
```

## Related

- `psmdb/AGENTS.md` - MongoDB server (PBM depends on PSMDB)
- `pdmdb/AGENTS.md` - Distribution packaging
- `vars/AGENTS.md` - Shared helpers (pbmVersion, moleculePbm*)
