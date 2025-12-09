# AGENTS.md - PPG Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Distribution for PostgreSQL (PPG) CI/CD pipelines. Includes PostgreSQL server builds (versions 14-18), extension packaging for 20+ extensions (pgvector, pg_stat_monitor, patroni, pgbackrest, etc.), Docker image creation, tarball generation, multi-version testing, major/minor upgrade validation, and HA meta-package workflows across 8+ Linux distributions.

## Key Directories

- `ppg/` – All PPG pipeline definitions (69 Groovy files + corresponding YAML configs)
- Contains: Core server builds, extension builds, testing orchestrators, Docker pipelines, documentation generation

## Key Files

### Core Server Builds
- `postgresql_server.groovy` / `postgresql_server_nightly.groovy` – PostgreSQL server package builds
- `pg_tarballs.groovy` – Source tarball generation for PostgreSQL releases
- `pg_source_tarballs.groovy` – PostgreSQL source tarball packaging
- `ppg-server.groovy` / `ppg-server-ha.groovy` – PPG server packaging (standard + HA meta-packages)

### Extension Builds (20+ extensions)
- `pg_stat_monitor-autobuild.groovy` – Percona's query performance monitoring (auto-triggered)
- `pgvector.groovy` – Vector similarity search extension
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
- `pgrepack.groovy` – Table/index reorganization
- `pgbadger.groovy` – Log analyzer
- `wal2json.groovy` – Logical decoding to JSON
- `timescaledb.groovy` – Time-series database
- `haproxy.groovy` / `etcd.groovy` / `pysyncobj.groovy` – HA components
- `llvm.groovy` – JIT compilation support
- `postgresql-common.groovy` – Common PostgreSQL utilities
- `ydiff.groovy` – Diff tool for PostgreSQL

### Testing & Orchestration
- `ppg-multi-parallel.groovy` – Multi-version parallel testing (all extensions)
- `ppg-multi.groovy` – Multi-version serial testing
- `component.groovy` / `component-generic.groovy` – Individual component testing
- `component-generic-parallel.groovy` / `component-multi-parallel.groovy` – Parallel component builds
- `ppg-upgrade-parallel.groovy` / `ppg-upgrade.groovy` – Major/minor version upgrade validation
- `ppg-12-full-major-upgrade.groovy` / `ppg-12-full-major-upgrade-parallel.groovy` – PostgreSQL 12 upgrade workflows
- `ppg-parallel.groovy` / `ppg.groovy` – Installation testing across distributions
- `pgsm-parallel.groovy` / `pgsm.groovy` – pg_stat_monitor specific testing
- `pgsm-pgdg-parallel.groovy` / `pgsm-pgdg.groovy` – PGDG distribution testing
- `psp-installcheck-world.groovy` / `psp-installcheck-world-parallel.groovy` – PostgreSQL regression test suite
- `psp-performance-test.groovy` – Performance benchmarking
- `tarball.groovy` / `tarball-parallel-ssl*.groovy` – SSL variant testing (OpenSSL 1.x, 3.x, 3.5.x)
- `tde-parallel.groovy` / `tde.groovy` – TDE validation

### Docker & Documentation
- `ppg-docker.groovy` – PPG Docker image builds
- `ppg-pgbackrest-docker.groovy` – pgBackRest container images
- `ppg-pgbouncer-docker.groovy` – pgBouncer container images
- `docker.groovy` / `docker-parallel.groovy` – Generic Docker builds
- `ppg-{11,12,13,14}-documentation-md.groovy` – Version-specific documentation publishing

### Release & Security
- `ppg_release.groovy` – Release orchestration
- `pg_snyk_scan.groovy` – Security vulnerability scanning
- `postgresql-ivee.groovy` – Internal validation/testing
- `get-pg_stat_monitor-branches.groovy` – Branch discovery automation

## Product-Specific Patterns

### PostgreSQL Version Matrix

Active PostgreSQL versions (master branch):
- PG 14 (maintenance)
- PG 15 (LTS)
- PG 16 (current stable)
- PG 17 (current stable)
- PG 18 (testing/preview)

Scenario patterns:
```groovy
// Installation scenarios (from vars/ppgScenarios.groovy)
'pg-{14,15,16,17,18}'           // Standard installation
'pg-{14,15,16,17,18}-meta-ha'   // HA meta-packages
'pg-{14,15,16,17,18}-meta-server' // Server meta-packages

// Upgrade scenarios (from vars/ppgUpgradeScenarios.groovy)
'pg-{version}-minor-upgrade'     // Same major version (e.g., 16.1 → 16.2)
'pg-{version}-major-upgrade'     // Cross major version (e.g., 15.x → 16.x)
```

### Extension Version Management

Each extension has independent versioning (from ppg-multi-parallel.groovy):
```groovy
// Extensions tracked in multi-component testing
PGSM_VERSION: 'pg_stat_monitor version'
PGPOOL_VERSION: 'pgpool2 version'
POSTGIS_VERSION: 'PostGIS version'
PGAUDIT_VERSION: 'pgAudit version'
PG_REPACK_VERSION: 'pg_repack version'
PATRONI_VERSION: 'Patroni version'
PGBACKREST_VERSION: 'pgBackRest version'
SETUSER_VERSION: 'set_user extension version'
PGBADGER_VERSION: 'pgBadger version'
PGBOUNCER_VERSION: 'pgBouncer version'
WAL2JSON_VERSION: 'wal2json version'
```

### SSL/OpenSSL Variants

PPG supports multiple OpenSSL versions (master branch):
```groovy
// SSL variant jobs
tarball-parallel-ssl1.groovy   // OpenSSL 1.x (EL7, Ubuntu Xenial/Bionic)
tarball-parallel-ssl3.groovy   // OpenSSL 3.x (EL8/9, Ubuntu Focal+, Debian 11+)
tarball-parallel-ssl35.groovy  // OpenSSL 3.5.x (latest distros)
```

Platform coverage by SSL version:
- SSL1: Legacy distributions (EL7, Ubuntu 16.04/18.04)
- SSL3: Modern distributions (EL8/9, Ubuntu 20.04+, Debian 11+)
- SSL35: Latest distributions with OpenSSL 3.5+

### Testing Repository Integration

Test automation repository:
```bash
github.com/Percona-QA/ppg-testing.git
# Default branch: main (some legacy jobs use master)
# Contains: Molecule scenarios, Ansible playbooks, Python testinfra
```

### Build Infrastructure Patterns

```groovy
// Docker-based builds (common pattern)
buildStage("oraclelinux:8", "--build_src_rpm=1")

// Extension builds use dedicated builder scripts
wget ${GIT_REPO}/main/percona-packaging/scripts/pg_stat_monitor_builder.sh
bash -x ./psm_builder.sh --pg_release=${PG_RELEASE} --ppg_repo_name=${PPG_REPO}

// Source repositories
// PostgreSQL server: github.com/percona/postgres-packaging.git
// Extensions: Various upstream + Percona forks
```

## Agent Workflow

1. **Inspect job configuration:**
   ```bash
   ~/bin/jenkins job pg config <job-name> --yaml
   ```
   Key parameters to capture:
   - `VERSION` / `PPG_VERSION`: PPG release (e.g., 'ppg-17.6')
   - `PG_BRANCH` / `GIT_BRANCH`: PostgreSQL branch (e.g., 'REL_17_STABLE')
   - `MAJOR_VERSION`: PostgreSQL major version (14-18)
   - `COMPONENT_VERSION`: Extension-specific version (for component jobs)
   - `TO_REPO` / `FROM_REPO`: Repository targets (testing/experimental/release)
   - `TESTING_BRANCH`: ppg-testing.git branch (usually 'main')
   - `SCENARIO`: Test scenario (from ppgScenarios/ppgUpgradeScenarios)

2. **Leverage shared helpers from vars/:**
   PPG-specific functions:
   ```groovy
   ppgScenarios()                    // Available installation test scenarios
   ppgUpgradeScenarios()             // Upgrade test scenarios
   ppgOperatingSystemsAMD()          // x64 platform list
   ppgOperatingSystemsARM()          // ARM64 platform list (hetzner branch)
   ppgOperatingSystemsSSL1()         // OpenSSL 1.x platforms
   ppgOperatingSystemsSSL3()         // OpenSSL 3.x platforms
   ppgOperatingSystemsSSL35()        // OpenSSL 3.5.x platforms
   ```

   Build helpers:
   ```groovy
   buildStage(DOCKER_OS, STAGE_PARAM)       // Common build pattern
   cleanUpWS()                               // Workspace cleanup
   pushArtifactFolder() / popArtifactFolder() // S3 artifact management
   ```

3. **Extension coordination:**
   When modifying extension pipelines, check integration points:
   - Individual extension build jobs (e.g., `pgaudit.groovy`)
   - Component testing (`component.groovy` with PRODUCT parameter)
   - Multi-component validation (`ppg-multi-parallel.groovy` parameters)
   - Release pipelines (`ppg_release.groovy`)

   Extension version updates must be coordinated across all these pipelines.

4. **Version matrix updates:**
   Adding/removing PostgreSQL versions requires changes to:
   - `vars/ppgScenarios.groovy` – Add 'pg-{version}' variants
   - `vars/ppgUpgradeScenarios.groovy` – Add upgrade scenarios
   - Component jobs – Update `PG_RELEASE` choices
   - Documentation jobs – Create `ppg-{version}-documentation-md.groovy`
   - Docker jobs – Update `MAJ_VER`/`MIN_VER` parsing logic

5. **Artifact & cleanup management:**
   - Always wrap builds with `cleanUpWS()` pre/post blocks
   - Use `stash`/`unstash` for multi-stage artifact passing
   - Docker builds require 'docker-32gb' label for large images
   - Upload tarballs via `pushArtifactFolder()` to AWS S3
   - Set `DESTROY_ENV='yes'` for ephemeral test VMs

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('ppg/ppg-multi-parallel.groovy'))"

# Molecule testing (local validation)
cd /path/to/ppg-testing
molecule test -s pg-17                 # Standard installation
molecule test -s pg-17-meta-ha         # HA meta-packages
molecule test -s pg-17-major-upgrade   # Upgrade scenarios
molecule test -s ppg-17                # Component testing

# PostgreSQL installcheck-world (comprehensive regression testing)
# Triggered via psp-installcheck-world.groovy
# Runs PostgreSQL's complete regression test suite

# Performance validation
# Use psp-performance-test.groovy for benchmarking

# Jenkins dry-run strategy
# Start with single-platform testing before parallel rollout:
~/bin/jenkins build pg/ppg-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  -p REPO=experimental \
  -p VERSION=ppg-17.7 \
  -p SCENARIO=pg-17 \
  --watch

# Docker image validation
# Trivy security scanning runs automatically in ppg-docker.groovy
# Manual scan: trivy image percona-distribution-postgresql:17

# Extension smoke tests
# Each extension job includes basic functional tests via builder scripts
# Example: pg_stat_monitor_builder.sh includes extension load & query tests
```

## Credentials & Parameters

### Credentials
```groovy
// Standard credentials (used via moleculeDistributionJenkinsCreds())
// - AWS credentials for EC2 Molecule testing
// - SSH keys for build agents

// Docker-specific credentials
'hub.docker.com' (usernamePassword)              // DockerHub publishing
'8468e4e0-5371-4741-a9bb-7c143140acea' (AWS)     // ECR access

// Documentation publishing
'publish-doc-percona.com' (sshUserPrivateKey)    // Doc server upload
'jenkins-deploy' (sshUserPrivateKey)             // Deployment key

// Build cache
// S3 bucket: pg-build-cache (defined in IaC/pg.cd/JenkinsStack.yml)
```

### Key Parameter Contracts
```groovy
// Common across all PPG jobs
VERSION / PPG_VERSION: 'ppg-{major}.{minor}'  // e.g., 'ppg-17.6'
REPO / TO_REPO / FROM_REPO: [testing, experimental, release]
TESTING_BRANCH: 'main'                        // ppg-testing.git branch default
DESTROY_ENV: 'yes'/'no'                       // VM cleanup toggle

// Build jobs (pg_tarballs, ppg-server, postgresql_server)
GIT_REPO: 'Source repository URL'
GIT_BRANCH / PG_BRANCH: 'Branch/tag to build'
RPM_RELEASE / DEB_RELEASE: 'Package release numbers'
BUILD_DEPENDENCIES: '0'/'1'                   // Build third-party deps

// Testing jobs (ppg-parallel, component, ppg-upgrade)
PLATFORM: 'From ppgOperatingSystemsAMD/ARM/ALL()'
SCENARIO: 'From ppgScenarios() or ppgUpgradeScenarios()'
MAJOR_VERSION: '14-18'                        // PostgreSQL major version
MAJOR_REPO: true/false                        // Use major version repo path

// Component jobs (component.groovy)
PRODUCT: ['pg_audit', 'patroni', 'pgbackrest', ...]
COMPONENT_REPO: 'Upstream Git repository'
COMPONENT_VERSION: 'Specific tag/branch for component'

// Docker jobs (ppg-docker)
TARGET_REPO: [PerconaLab, AWS_ECR, DockerHub]
LATEST: 'yes'/'no'                            // Tag as latest

// Extension-specific versions (ppg-multi-parallel)
PGSM_VERSION, PGPOOL_VERSION, POSTGIS_VERSION, etc.
// Each extension has independent version tracking
```

### Artifact Naming Conventions
```
Source tarballs: percona-postgresql-{version}.tar.gz
Binary packages: percona-postgresql{major}-server-{version}
Extensions: percona-pg{major}-{extension}-{ext-version}
Docker images: percona-distribution-postgresql:{major}
```

## Jenkins Instance

PPG jobs run on: `pg.cd.percona.com`

```bash
# List all PPG jobs
~/bin/jenkins job pg list

# Common job prefixes
pg_*           # PostgreSQL core builds (pg_tarballs, pg_source_tarballs)
postgresql_*   # Server builds (postgresql_server, postgresql_server_nightly)
ppg-*          # PPG packaging & testing (ppg-parallel, ppg-multi-parallel)
ppg_*          # PPG utilities (ppg_release)
pg*            # Extensions (pgaudit, pgbackrest, pgbouncer, etc.)
pgsm*          # pg_stat_monitor specific (pgsm-parallel, pgsm-pgdg)
component*     # Component testing framework
psp-*          # Performance and regression testing

# Get job parameters
~/bin/jenkins job pg params ppg-multi-parallel

# Check build status
~/bin/jenkins job pg status ppg-multi-parallel

# View job configuration
~/bin/jenkins job pg config ppg-multi --yaml
```

Region: eu-central-1
Infrastructure defined in: `IaC/pg.cd/JenkinsStack.yml`

## Related Jobs

### Cross-Product Dependencies
- **PMM** (Percona Monitoring & Management): Consumes PPG packages for PMM Server PostgreSQL backend
- **PostgreSQL Operator** (K8s): Uses ppg-docker images for database containers, depends on pgbackrest, pgbouncer, patroni extensions
- **Documentation**: `ppg-{version}-documentation-md.groovy` publishes to percona.com

### Internal Job Dependencies

Typical pipeline flow:
```
1. pg_tarballs.groovy → Generate source tarballs
2. postgresql_server.groovy → Build PostgreSQL server packages
3. [extension].groovy (20+ jobs) → Build extension packages
4. ppg-parallel.groovy → Install & smoke test all packages
5. component.groovy → Deep testing of individual extensions
6. ppg-upgrade-parallel.groovy → Validate upgrades
7. ppg-multi-parallel.groovy → Comprehensive multi-component testing
8. ppg-docker.groovy → Create Docker images
9. ppg_release.groovy → Orchestrate release promotion
```

Nightly jobs:
- `postgresql_server_nightly.groovy` – Daily server builds
- `pg_tde_nightly.groovy` – TDE extension nightly validation
- Auto-triggered builds: `pg_stat_monitor-autobuild.groovy`, `pg_percona_telemetry_autobuild.groovy`

## Code Owners

See `.github/CODEOWNERS` – PPG pipelines maintained by:
- Muhammad Aqeel (106 commits) – Docker/tarball infrastructure lead
- EvgeniyPatlan (93 commits) – Infrastructure and trigger automation
- Manika Singhal (51 commits) – Version management and recent development
- Naeem Akhter (38 commits) – Test platform management, parallel execution
- Mikhail Samoylov (26 commits) – Component testing and build integration

Primary contact: `@aqeel`, `@evgeniypatlan`
