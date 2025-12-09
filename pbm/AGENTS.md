# AGENTS.md - PBM Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Backup for MongoDB (PBM) pipelines: agent builds, package testing, functional backup/restore on replica sets and sharded clusters, Molecule scenarios, and PBM–PSMDB integration.

## Key Files

- `pbm-build.groovy` – PBM build pipeline
- `pbm-package-testing.groovy` – Package testing
- `pbm-molecule.groovy` – Molecule scenarios (install, upgrade, functional)

## Product-Specific Patterns

### Backup testing

Ensure coverage for:
- Logical & physical backups
- Point-in-time recovery (PITR)
- Selective collection restore
- Multiple storage backends (S3-compatible, filesystem)

### MongoDB topology support

Pipelines must exercise:
- Standalone nodes
- Replica sets
- Sharded clusters with config servers

### Molecule credentials

```groovy
// PBM-specific molecule credentials
moleculePbmJenkinsCreds()
```

## Agent Workflow

1. **Fetch job definitions:** `~/bin/jenkins job psmdb config pbm-package-testing --yaml` to inspect parameters such as `PBM_BRANCH`, `PSMDB_BRANCH`, `LAYOUT`, `STORAGE`.
2. **Respect topology matrices:** When adding or removing scenarios, update both Molecule configs and Jenkins parameters (`TOPOLOGY`, `STORAGE_BACKEND`).
3. **Use helpers:** Call `moleculeExecuteActionWithScenario`, `runMoleculeCommandParallel*`, `pbmVersion`, and `installMolecule` functions from `vars/`.
4. **Cleanup resources:** Molecule scenarios create EC2 instances and S3 buckets; wrap them with `try/finally` and `moleculeParallelPostDestroy`.
5. **Coordinate with PSMDB:** PBM pipelines share credentials and artifacts with `psmdb` jobs—keep version parameters aligned.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('pbm/pbm-build.groovy'))"`
- **Molecule smoke:** `cd molecule/pbm && molecule test -s aws-sharded`
- **PBM CLI tests:** Run `pbm backup` / `pbm restore` locally in Docker containers for quick sanity checks before editing Jenkins steps.
- **Jenkins dry-run:** Trigger `pbm-functional-aws-rs` or `pbm-functional-aws-sharded` with limited OS list for validation.

## Credentials & Parameters

- **Credentials:** `pbm-staging` or `psmdb-staging` AWS creds, `moleculePbmJenkinsCreds()` for Molecule SSH keys. Always guard with `withCredentials`.
- **Key parameters:** `PBM_BRANCH`, `PSMDB_BRANCH`, `LAYOUT_TYPE`, `STORAGE_BACKEND`, `ENABLE_PITR`.
- **Storage buckets:** Reuse existing staged buckets; never hardcode S3 keys.

## Jenkins Instance

PBM jobs run on: `psmdb.cd.percona.com` (shared with PSMDB)

```bash
~/bin/jenkins job psmdb list | grep pbm
```

## Job Inventory

**Total Jobs:** ~52 (on psmdb.cd.percona.com) | **Groovy Files:** 23 | **Coverage:** 44%

Note: PBM jobs run on `psmdb.cd.percona.com`, not a separate instance.

### Core Build Jobs

| Job | File | Description |
|-----|------|-------------|
| percona-mongodb-backup | `jenkins/percona-mongodb-backup.groovy` | Main PBM build |
| percona-mongodb-backup-aarch64 | `jenkins/percona-mongodb-backup-aarch64.groovy` | ARM64 build |
| get-pbm-branches | `jenkins/get-pbm-branches.groovy` | Branch discovery |
| pbm_generate_sbom | `jenkins/pbm_generate_sbom.groovy` | SBOM generation |

### Docker Jobs

| Job | File | Description |
|-----|------|-------------|
| pbm-docker | `pbm-docker.groovy` | Docker images |
| pbm-docker-arm | `pbm-docker-arm.groovy` | ARM64 Docker |
| pbm-docker-nightly | `pbm-docker-nightly.groovy` | Nightly builds |
| hetzner-pbm-docker | (uses pbm-docker.groovy) | Hetzner Docker |
| hetzner-pbm-docker-arm | (uses pbm-docker-arm.groovy) | Hetzner ARM64 |
| hetzner-pbm-docker-nightly | (uses pbm-docker-nightly.groovy) | Hetzner nightly |

### Package Testing

| Job | File | Description |
|-----|------|-------------|
| pbm-pkg-install | `pbm-pkg-install.groovy` | Package install |
| pbm-pkg-install-parallel | `pbm-pkg-install-parallel.groovy` | Parallel install |
| pbm-pkg-upgrade | `pbm-pkg-upgrade.groovy` | Package upgrade |
| pbm-pkg-upgrade-parallel | `pbm-pkg-upgrade-parallel.groovy` | Parallel upgrade |

### Functional Testing

| Job | File | Description |
|-----|------|-------------|
| pbm-functional-aws-rs | `pbm-functional-aws-rs.groovy` | AWS replica set |
| pbm-functional-aws-sharded | `pbm-functional-aws-sharded.groovy` | AWS sharded |
| pbm-functional-tests | `pbm-functional-tests.groovy` | Functional tests |
| pbm-functional-tests-full | `pbm-functional-tests-full.groovy` | Full test suite |
| pbm-e2e-tests | `pbm-e2e-tests.groovy` | End-to-end tests |
| pbm-manual | `pbm-manual.groovy` | Manual trigger |
| pbm-release-test-run | `pbm-release-test-run.groovy` | Release testing |

### Hetzner Jobs (Active)

| Job | File | Description |
|-----|------|-------------|
| hetzner-pbm-functional-tests | `hetzner-pbm-functional-tests.groovy` | Hetzner functional |
| hetzner-pbm-functional-tests-full | `hetzner-pbm-functional-tests-full.groovy` | Full Hetzner tests |
| hetzner-pbm-site-check | `hetzner-pbm-site-check.groovy` | Hetzner site check |
| hetzner-pbm-autobuild | (no file) | Hetzner builds |
| hetzner-pbm-autobuild-RELEASE | (no file) | Release builds |
| hetzner-pbm-e2e-tests | (no file) | E2E tests |
| hetzner-pbm-release-test-run | (no file) | Release testing |
| hetzner-pbm_generate_sbom | (no file) | SBOM generation |

### Utility Jobs

| Job | File | Description |
|-----|------|-------------|
| pbm-site-check | `pbm-site-check.groovy` | Site validation |
| pbm-documentation | `pbm-documentation.groovy` | Doc generation |

### Jenkins-Only Jobs (No Groovy File)

~29 jobs without source files:
- `pbm-autobuild` – Legacy autobuild (disabled)
- `pbm-autobuild-RELEASE` – Release builds
- `pbm-autobuild-CUSTOM` – Custom builds
- `pbm-aarch64-build` – Legacy ARM64
- `hetzner-pbm-*` – Some Hetzner variants

## Related Jobs

- `pbm-build` – Build artifacts
- `pbm-package-testing` – Package validation
- `psmdb-*` pipelines for server compatibility testing
