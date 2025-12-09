# AGENTS.md - PSMDB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Server for MongoDB (PSMDB) pipelines: server builds, package testing, replica set & sharding validation, encryption/FIPS coverage, backup/restore flows, and PBM integration.

## Key Files

- `psmdb-build.groovy` – PSMDB build pipeline
- `psmdb-package-testing.groovy` – Package testing
- `psmdb-molecule.groovy` – Molecule-based scenarios

## Product-Specific Patterns

### MongoDB version matrix

Actively maintained PSMDB versions:
- 6.0
- 7.0
- 8.0

### Replication/sharding testing

Molecule scenarios validate:
- Replica sets (with/without encryption)
- Sharded clusters w/ config servers
- PITR & PBM restore workflows

## Agent Workflow

1. **Pull live config:** `~/bin/jenkins job psmdb config psmdb-build --yaml` before editing to capture parameters such as `PSMDB_BRANCH`, `PSMDB_VERSION`, `PBM_BRANCH`.
2. **Share logic:** Use helpers like `moleculeExecuteActionWithScenario`, `moleculeParallelPostDestroy`, `pbmVersion`, and `installMolecule*` from `vars/`.
3. **PBM integration:** When pipelines interact with PBM, keep credentials in sync with `pbm/AGENTS.md` and ensure `moleculePbmJenkinsCreds()` is applied.
4. **Encryption/FIPS:** Jobs toggling FIPS or KMIP must propagate flags to both build and test stages; keep parameter names consistent (`ENABLE_FIPS`, `KMS_CONFIG`).
5. **Cleanup clusters:** Always destroy Molecule environments and delete any `kubeconfig`/`aws` temp files in `post`.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('psmdb/psmdb-build.groovy'))"`
- **Python helpers:** `python3 -m py_compile resources/pmm/do_remove_droplets.py` (shared cleanup script)
- **Molecule smoke:** `cd molecule/psmdb && molecule test -s ubuntu-jammy`
- **PBM end-to-end:** Trigger `pbm-pkg-upgrade` or `pbm-functional-*` jobs after major backup-related updates.

## Credentials & Parameters

- **Credentials:** `psmdb-staging` (AWS), `pbm-staging` (when using PBM), `aws-jenkins` (SSH). Wrap with `withCredentials`.
- **Key parameters:** `PSMDB_BRANCH`, `PSMDB_TAG`, `PBM_BRANCH`, `LAYOUT_TYPE`, `ENCRYPTION`, `SCENARIO`.
- **Artifacts:** Tarball & package naming (`percona-server-mongodb-<ver>`) is consumed by PBM—do not change without coordination.

## Jenkins Instance

PSMDB jobs run on: `psmdb.cd.percona.com`

```bash
~/bin/jenkins job psmdb list
```

## Job Inventory

**Total Jobs:** 208 | **Groovy Files:** 48 | **Coverage:** 23%

### Core Build Jobs

| Job | File | Description |
|-----|------|-------------|
| percona-server-for-mongodb-6.0 | `jenkins/percona-server-for-mongodb-6.0.groovy` | PSMDB 6.0 builds |
| percona-server-for-mongodb-7.0 | `jenkins/percona-server-for-mongodb-7.0.groovy` | PSMDB 7.0 builds |
| percona-server-for-mongodb-8.0 | `jenkins/percona-server-for-mongodb-8.0.groovy` | PSMDB 8.0 builds |
| percona-server-for-mongodb-6.0-aarch64 | `jenkins/percona-server-for-mongodb-6.0-aarch64.groovy` | ARM64 6.0 |
| percona-server-for-mongodb-7.0-aarch64 | `jenkins/percona-server-for-mongodb-7.0-aarch64.groovy` | ARM64 7.0 |
| percona-server-for-mongodb-8.0-aarch64 | `jenkins/percona-server-for-mongodb-8.0-aarch64.groovy` | ARM64 8.0 |

### Legacy Build Jobs

| Job | File | Description |
|-----|------|-------------|
| percona-server-for-mongodb-3.6 | `jenkins/percona-server-for-mongodb-3.6.groovy` | Legacy 3.6 |
| percona-server-for-mongodb-4.0 | `jenkins/percona-server-for-mongodb-4.0.groovy` | Legacy 4.0 |
| percona-server-for-mongodb-4.2 | `jenkins/percona-server-for-mongodb-4.2.groovy` | Legacy 4.2 |
| percona-server-for-mongodb-4.4 | `jenkins/percona-server-for-mongodb-4.4.groovy` | Legacy 4.4 |
| percona-server-for-mongodb-5.0 | `jenkins/percona-server-for-mongodb-5.0.groovy` | Legacy 5.0 |
| percona-server-for-mongodb-4.4-aarch64 | `jenkins/percona-server-for-mongodb-4.4-aarch64.groovy` | ARM64 4.4 |
| percona-server-for-mongodb-5.0-aarch64 | `jenkins/percona-server-for-mongodb-5.0-aarch64.groovy` | ARM64 5.0 |

### Hetzner Build Jobs (Active)

| Job | File | Description |
|-----|------|-------------|
| hetzner-psmdb60-autobuild | (no file) | Hetzner 6.0 builds |
| hetzner-psmdb70-autobuild | (no file) | Hetzner 7.0 builds |
| hetzner-psmdb80-autobuild | (no file) | Hetzner 8.0 builds |
| hetzner-psmdb-docker | `psmdb-docker.groovy` | Docker images |
| hetzner-psmdb-docker-arm | `psmdb-docker-arm.groovy` | ARM64 Docker |
| hetzner-psmdb-docker-pro | `psmdb-docker-pro.groovy` | Pro Docker |

### Testing & Regression

| Job | File | Description |
|-----|------|-------------|
| psmdb | `psmdb.groovy` | Core test suite |
| psmdb-regression | `psmdb-regression.groovy` | Regression testing |
| psmdb-parallel | `psmdb-parallel.groovy` | Parallel execution |
| psmdb-upgrade | `psmdb-upgrade.groovy` | Upgrade testing |
| psmdb-upgrade-parallel | `psmdb-upgrade-parallel.groovy` | Parallel upgrades |
| psmdb-fips | `psmdb-fips.groovy` | FIPS compliance |
| psmdb-integration | `psmdb-integration.groovy` | Integration tests |
| hetzner-psmdb-regression | (no file) | Hetzner regression |
| hetzner-psmdb-multijob-regression | `psmdb-multijob-regression.groovy` | Multi-job regression |
| hetzner-psmdb-multijob-testing | `psmdb-multijob-testing.groovy` | Multi-job testing |

### Tarball Jobs

| Job | File | Description |
|-----|------|-------------|
| psmdb-tarball | `psmdb-tarball.groovy` | Tarball builds |
| psmdb-tarball-all-os | `psmdb-tarball-all-os.groovy` | All OS tarballs |
| psmdb-tarball-all-setups | `psmdb-tarball-all-setups.groovy` | All setups |
| psmdb-tarball-functional | `psmdb-tarball-functional.groovy` | Functional tests |
| psmdb-tarball-pro-functional | `psmdb-tarball-pro-functional.groovy` | Pro functional |
| psmdb-tarball-multi | `psmdb-tarball-multi.groovy` | Multi-version |

### Utility Jobs

| Job | File | Description |
|-----|------|-------------|
| get-psmdb-branches | `jenkins/get-psmdb-branches.groovy` | Branch discovery |
| get-psmdb-branches-6.0 | `jenkins/get-psmdb-branches-6.0.groovy` | 6.0 branches |
| get-psmdb-branches-7.0 | `jenkins/get-psmdb-branches-7.0.groovy` | 7.0 branches |
| get-psmdb-branches-8.0 | `jenkins/get-psmdb-branches-8.0.groovy` | 8.0 branches |
| psmdb_generate_sbom | `jenkins/psmdb_generate_sbom.groovy` | SBOM generation |
| psmdb-site-check | `psmdb-site-check.groovy` | Site validation |
| mongodb-reshard | `mongodb-reshard.groovy` | Reshard utility |
| percona-mongodb-mongosh | `jenkins/percona-mongodb-mongosh.groovy` | Mongosh builds |
| percona-mongodb-mongosh-aarch64 | `jenkins/percona-mongodb-mongosh-aarch64.groovy` | ARM64 mongosh |

### Cross-Product Jobs (Runs on PSMDB)

Jobs from other products that run on psmdb.cd.percona.com:
- `pbm-*` – PBM jobs (~40 jobs) → See pbm/AGENTS.md
- `hetzner-pbm-*` – Hetzner PBM jobs (~12 jobs)
- `pcsm-*` – PCSM jobs (~12 jobs) → See pcsm/AGENTS.md
- `pdmdb-*` – PDMDB distribution (~10 jobs) → See pdmdb/AGENTS.md
- `percona-dbaas-cli-*` – DBaaS CLI (~7 jobs)
- `pmm-ps-*` – PMM integration tests (~3 jobs)
- `percona-distribution-mysql-*` – MySQL distribution (~2 jobs)
- Infrastructure jobs (reconnect-workers, spot-price-auto-updater)

### Jenkins-Only Jobs (No Groovy File)

~160 jobs without source files (largest gap):
- `psmdb*-autobuild` – Autobuild variants (mostly disabled)
- `psmdb*-autobuild-RELEASE` – Release autobuilds
- `percona-server-for-mongodb-*-param` – Parameter jobs
- `percona-server-for-mongodb-*-template` – Template jobs
- `multi-psmdb-*` – Multi-version jobs (disabled)
- `hetzner-psmdb*-custombuild` – Custom builds
- `test-*` – Developer test jobs

## Related Jobs

- `psmdb-build` – Build artifacts
- `psmdb-package-testing` – Package validation
- PBM (Percona Backup for MongoDB) integration tests
