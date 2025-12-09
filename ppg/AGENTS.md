# AGENTS.md - PPG Pipelines (Hetzner Branch)

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Distribution for PostgreSQL (PPG) CI/CD pipelines for Hetzner Cloud infrastructure. Includes PostgreSQL server builds (versions 14-17), extension packaging for 20+ extensions with ARM64 support, Docker image creation for multi-architecture, tarball generation, multi-version testing, upgrade validation, and HA meta-package workflows across 8+ Linux distributions on both x64 and ARM64 architectures.

## Key Directories

- `ppg/` – All PPG pipeline definitions (138+ Groovy files + corresponding YAML configs)
- Contains: Core server builds, extension builds (x64 + ARM64), testing orchestrators, Docker pipelines, documentation generation

## Architecture Support

### x64 (AMD64) - 69 Jobs
Standard x86_64 architecture for traditional server deployments.

### ARM64 (aarch64) - 27 Jobs (Hetzner-Specific)
ARM64 variants for all major extensions and builds:
- `*-arm.groovy` jobs for all 20+ extensions
- Enables deployment on ARM servers (Apple Silicon compatible, ARM cloud instances)
- Uses Hetzner Cloud ARM instances for cost-effective builds

## Key Files

### Core Server Builds
- `postgresql_server.groovy` / `postgresql_server_nightly.groovy` – PostgreSQL server (x64)
- `postgresql_server_arm.groovy` – PostgreSQL server (ARM64)
- `pg_tarballs.groovy` / `pg_tarballs-arm.groovy` – Source tarball generation
- `pg_source_tarballs.groovy` – PostgreSQL source tarballs
- `ppg-server.groovy` / `ppg-server-arm.groovy` – PPG server packaging (x64/ARM64)
- `ppg-server-ha.groovy` / `ppg-server-ha-arm.groovy` – HA meta-packages (x64/ARM64)

### Extension Builds (20+ extensions × 2 architectures)

**x64 Extensions:**
- `pg_stat_monitor-autobuild.groovy` – Percona's query performance monitoring
- `pgvector.groovy` – Vector similarity search
- `pg_tde.groovy` / `pg_tde_nightly.groovy` – Transparent Data Encryption
- `patroni.groovy` – HA cluster management
- `pgbackrest.groovy` – Backup & restore solution
- `pgbouncer.groovy` – Connection pooler
- `pgpool2-build.groovy` – Connection pooling & replication
- `pgaudit.groovy` / `pgaudit_set_user.groovy` – Audit logging
- `percona-postgis.groovy` / `postgis_tarballs.groovy` – Geospatial extension
- `pg_cron.groovy` – Job scheduler
- `pg_gather.groovy` – Diagnostics collection
- `pg_percona_telemetry_autobuild.groovy` – Telemetry agent
- `pgrepack.groovy` – Table/index reorganization (x64 only)
- `pgbadger.groovy` – Log analyzer
- `wal2json.groovy` – Logical decoding to JSON
- `timescaledb.groovy` – Time-series database
- `haproxy.groovy` / `etcd.groovy` / `pysyncobj.groovy` – HA components
- `llvm.groovy` – JIT compilation support
- `postgresql-common.groovy` – Common utilities (x64 only)
- `ydiff.groovy` – Diff tool

**ARM64 Extension Variants (Hetzner):**
- All extensions have `-arm` variants (e.g., `patroni-arm.groovy`, `pgvector-arm.groovy`)
- Total: 27 ARM-specific jobs for parallel architecture support
- Enables ARM deployments for cost optimization and Apple Silicon compatibility

**Additional Packages (Hetzner):**
- `gdal311.groovy` / `gdal385.groovy` – GDAL geospatial libraries
- `proj95.groovy` – PROJ coordinate transformation
- `pg_generate_sbom.groovy` – Software Bill of Materials generation
- `pg_percona_telemetry_autobuild_arm.groovy` – ARM64 telemetry
- `pg_tde_arm_nightly.groovy` – ARM64 TDE nightly
- `pg_tarballs_17_nightly.groovy` – PG 17 nightly tarballs
- `pg17_autobuild.groovy` – PG 17 autobuild
- `percona-postgis35.groovy` – PostGIS 3.5 support

### Testing & Orchestration
- `ppg-multi-parallel.groovy` / `ppg-multi.groovy` – Multi-version testing
- `component.groovy` / `component-generic.groovy` – Component testing
- `component-generic-parallel.groovy` / `component-multi-parallel.groovy` – Parallel builds
- `ppg-upgrade-parallel.groovy` / `ppg-upgrade.groovy` – Upgrade validation
- `ppg-12-full-major-upgrade.groovy` / `ppg-12-full-major-upgrade-parallel.groovy` – PG 12 upgrades
- `ppg-parallel.groovy` / `ppg.groovy` – Installation testing
- `pgsm-parallel.groovy` / `pgsm.groovy` – pg_stat_monitor testing
- `psp-installcheck-world.groovy` / `psp-installcheck-world-parallel.groovy` – Regression suite
- `psp-performance-test.groovy` – Performance benchmarking
- `tarball.groovy` / `tarball-parallel-ssl*.groovy` – SSL variant testing
- `tde-parallel.groovy` / `tde.groovy` – TDE validation

### Docker & Documentation
- `ppg-docker.groovy` / `ppg-docker-arm.groovy` – PPG Docker images (x64/ARM64)
- `ppg-pgbackrest-docker.groovy` / `ppg-pgbackrest-docker-arm.groovy` – pgBackRest containers
- `ppg-pgbouncer-docker.groovy` / `ppg-pgbouncer-docker-arm.groovy` – pgBouncer containers
- `docker.groovy` / `docker-parallel.groovy` – Generic Docker builds
- `ppg-{11,12,13,14}-documentation-md.groovy` – Documentation publishing

### Release & Utilities
- `ppg_release.groovy` – Release orchestration
- `ppg-controller-trigger.groovy` – Build trigger controller (Hetzner)
- `pg_snyk_scan.groovy` – Security scanning
- `postgresql-ivee.groovy` / `postgresql-ivee-arm.groovy` – Validation
- `get-pg_stat_monitor-branches.groovy` – Branch discovery

## Product-Specific Patterns

### PostgreSQL Version Matrix (Hetzner)

Active PostgreSQL versions:
- PG 14 (maintenance)
- PG 15 (LTS)
- PG 16 (current stable)
- PG 17 (current stable)

Note: PG 18 support not yet available in hetzner branch (master has PG 18).

### Multi-Architecture Pattern

```groovy
// Conditional agent labels based on CLOUD parameter
agent {
    label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
}

// ARM64 variant
agent {
    label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
}
```

### Library Version

```groovy
// Hetzner branch uses separate library version for new jobs
@Library('jenkins-pipelines@hetzner') _

// Legacy jobs still use master
@Library('jenkins-pipelines@master') _
```

### Extension Version Management

```groovy
// Same extension tracking as master branch
// Extensions with ARM variants tracked independently:
PGSM_VERSION, PGPOOL_VERSION, POSTGIS_VERSION, PGAUDIT_VERSION,
PG_REPACK_VERSION, PATRONI_VERSION, PGBACKREST_VERSION, etc.
```

### SSL/OpenSSL Variants

Hetzner supports OpenSSL 1.x and 3.x (no 3.5.x variant):
```groovy
tarball-parallel-ssl1.groovy   // OpenSSL 1.x (EL7, Ubuntu 16.04/18.04)
tarball-parallel-ssl3.groovy   // OpenSSL 3.x (EL8/9, Ubuntu 20.04+, Debian 11+)
```

Note: `tarball-parallel-ssl35.groovy` not present in hetzner branch (master-only).

## Agent Workflow

1. **Inspect job configuration:**
   ```bash
   ~/bin/jenkins job pg config <job-name> --yaml
   ```
   Hetzner-specific parameters:
   - `CLOUD`: Choice between 'Hetzner' and 'AWS'
   - `VERSION` / `PPG_VERSION`: PPG release (e.g., 'ppg-17.0')
   - `ARCH`: 'amd64' or 'arm64' for architecture selection
   - `TO_REPO` / `FROM_REPO`: Repository targets
   - `TESTING_BRANCH`: ppg-testing.git branch

2. **Architecture selection:**
   When working with ARM64 jobs:
   - Use `-arm` suffix jobs for ARM64 builds
   - Agent labels are conditional based on CLOUD parameter
   - Test both x64 and ARM64 variants for compatibility
   - ARM builds support Apple Silicon and ARM cloud deployments

3. **Cloud provider awareness:**
   ```groovy
   // Hetzner branch supports dual cloud
   if (params.CLOUD == 'Hetzner') {
       // Use Hetzner Object Storage
       // Use Hetzner-specific agent labels
   } else {
       // Use AWS S3
       // Use AWS EC2 agents
   }
   ```

4. **Version matrix updates:**
   When adding PostgreSQL versions:
   - Update `vars/ppgScenarios.groovy`
   - Update `vars/ppgUpgradeScenarios.groovy`
   - Create both x64 and ARM64 variants if needed
   - Update documentation jobs
   - Update Docker jobs for both architectures

5. **Artifact management:**
   - Hetzner Object Storage for artifacts (not AWS S3)
   - Use `cleanUpWS()` for workspace cleanup
   - ARM builds require ARM-specific agents
   - Docker multi-arch builds require QEMU or native ARM builders

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('ppg/ppg-server-arm.groovy'))"

# Molecule testing (same as master)
cd /path/to/ppg-testing
molecule test -s pg-17

# Architecture-specific testing
# ARM64 jobs run on Hetzner ARM instances
# Verify architecture: uname -m (should show aarch64)

# Jenkins dry-run (x64)
~/bin/jenkins build pg/ppg-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  -p CLOUD=Hetzner \
  -p VERSION=ppg-17.0 \
  --watch

# Jenkins dry-run (ARM64)
~/bin/jenkins build pg/ppg-server-arm \
  -p CLOUD=Hetzner \
  -p VERSION=ppg-17.0 \
  --watch
```

## Credentials & Parameters

### Credentials
```groovy
// Standard credentials (same as master)
moleculeDistributionJenkinsCreds()

// Hetzner-specific
// Cloud provider credentials based on CLOUD parameter
// Hetzner Object Storage credentials for artifact uploads
```

### Key Parameter Contracts
```groovy
// Hetzner-specific parameters
CLOUD: ['Hetzner', 'AWS']                     // Cloud provider choice
ARCH: ['amd64', 'arm64']                      // Architecture selection (new)

// Standard parameters (same as master)
VERSION / PPG_VERSION: 'ppg-{major}.{minor}'
REPO / TO_REPO / FROM_REPO: [testing, experimental, release]
TESTING_BRANCH: 'main'
DESTROY_ENV: 'yes'/'no'

// Build/test parameters remain the same
GIT_REPO, GIT_BRANCH, PLATFORM, SCENARIO, etc.
```

## Jenkins Instance

PPG jobs run on: `pg.cd.percona.com`

```bash
# List all PPG jobs (includes ARM variants)
~/bin/jenkins job pg list | grep ppg

# ARM-specific jobs
~/bin/jenkins job pg list | grep -- '-arm'

# Get job parameters
~/bin/jenkins job pg params ppg-server-arm
```

Agent labels (Hetzner):
- `docker-x64-min` – x64 builds (Hetzner)
- `docker-aarch64` – ARM64 builds (Hetzner)
- Conditional based on `CLOUD` parameter

## Related Jobs

### Multi-Architecture Coordination
- Each extension has x64 and ARM64 variants
- Docker builds support multi-arch manifests
- Testing runs on both architectures for compatibility

### Cross-Product Dependencies
- **PMM**: Uses PPG packages (both architectures)
- **PostgreSQL Operator**: Uses ppg-docker images (multi-arch)
- **Documentation**: Same as master branch

### Internal Job Flow
```
1. pg_tarballs + pg_tarballs-arm → Generate source tarballs (both archs)
2. postgresql_server + postgresql_server_arm → Build packages
3. Extensions (x64 + ARM64) → 20+ extension builds × 2 architectures
4. ppg-parallel → Test installations
5. ppg-upgrade-parallel → Validate upgrades
6. ppg-docker + ppg-docker-arm → Multi-arch Docker images
7. ppg_release → Release promotion
```

## Code Owners

See `.github/CODEOWNERS` – PPG pipelines maintained by:
- Muhammad Aqeel (106 commits) – Docker/tarball infrastructure
- EvgeniyPatlan (93 commits) – Infrastructure and automation
- Manika Singhal (51 commits) – Version management
- Naeem Akhter (38 commits) – Platform management, parallel execution
- Mikhail Samoylov (26 commits) – Component testing

Primary contact: `@aqeel`, `@evgeniypatlan`

## Hetzner Branch Specifics

### ARM64 Support
27 ARM-specific jobs enable multi-architecture deployments for cost optimization and modern hardware support (Apple Silicon, AWS Graviton, Hetzner ARM instances).

### Cloud Provider Selection
Dual-cloud support via `CLOUD` parameter:
- `Hetzner` – Hetzner Cloud infrastructure, Object Storage
- `AWS` – AWS EC2/S3 (legacy compatibility)

### Missing from Hetzner
- No PG 18 support yet (master has PG 18)
- No `pgsm-pgdg` jobs (master-only)
- No `tarball-parallel-ssl35` (master-only)

### Added in Hetzner
- 27 ARM64 extension jobs
- GDAL 3.11/3.85 support
- PROJ 9.5 support
- PostGIS 3.5 support
- SBOM generation
- PG 17 nightly tarballs
- Controller trigger automation
