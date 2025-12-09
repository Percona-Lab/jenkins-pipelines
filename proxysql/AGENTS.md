# AGENTS.md - ProxySQL Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

ProxySQL CI/CD pipelines for MySQL proxy layer testing and builds. Includes ProxySQL 2.x QA testing, source tarball generation, binary builds, and comprehensive test suite validation across multiple Linux distributions. ProxySQL provides connection pooling, query routing, and high availability for MySQL/Percona Server deployments.

## Key Directories

- `jenkins/` – Build scripts and utilities
- Root level – QA pipelines and test orchestration

## Key Files

- `qa-proxysql2-pipeline.groovy` – ProxySQL 2.x QA test suite orchestration
- `proxysql.groovy` – Main ProxySQL build and test pipeline
- `jenkins/proxysql-tarball.groovy` – Source tarball generation
- Additional shell scripts in `jenkins/`: `build-binary-proxysql`, `run-*`, `test-proxysql`

## Product-Specific Patterns

### ProxySQL Version Support (Master Branch)

```groovy
// Master branch supports ProxySQL 2.x
job: 'qa-proxysql2-pipeline'
version: '2.x'
```

### Build Pattern (Go-based)

```groovy
// ProxySQL is written in C++ with Go components
// Different build pattern from other Percona products
buildType: 'native'  // Not Docker-based like other products
```

### Test Suite Integration

```groovy
// ProxySQL has extensive test suite
testSuite: 'qa-proxysql2-pipeline'
// Covers: Connection pooling, query routing, failover, load balancing
```

### Molecule Credentials

```groovy
// Uses PXC Molecule credentials (ProxySQL works with PXC)
moleculepxcJenkinsCreds()
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job ps80 config qa-proxysql2-pipeline --yaml` to capture parameters like `VERSION`, `PROXYSQL_BRANCH`, `PLATFORM`.
2. **Build considerations:** ProxySQL uses native C++/Go builds; different from Docker-based builds in other products.
3. **Test suite coverage:** QA pipeline includes connection pooling, query routing, failover scenarios, and load balancing tests.
4. **Platform support:** Recent work (Sept 2025) added RHEL10 support alongside existing distributions.
5. **Parameter contracts:** ProxySQL jobs support release automation; extend but never rename/remove parameters.

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('proxysql/qa-proxysql2-pipeline.groovy'))"

# Build validation
bash jenkins/build-binary-proxysql --version 2.5.5

# Test suite validation
bash jenkins/test-proxysql --suite qa

# Jenkins dry-run
~/bin/jenkins build ps80/qa-proxysql2-pipeline \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  -p PROXYSQL_BRANCH=v2.5.x \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculepxcJenkinsCreds()` – AWS/SSH for testing
- **Key parameters:**
  - `VERSION` – ProxySQL version (e.g., '2.5.5')
  - `PROXYSQL_BRANCH` – Git branch (e.g., 'v2.5.x', 'v2.6.x')
  - `PLATFORM` – OS selection via Molecule platform helpers
  - `BUILD_TYPE` – Build configuration (debug/release)
  - `RUN_TESTS` – Boolean to control test execution

## Jenkins Instance

ProxySQL jobs run on: `ps80.cd.percona.com` (shares Percona Server instance)

```bash
# List jobs
~/bin/jenkins job ps80 list | grep proxysql

# Get job parameters
~/bin/jenkins job ps80 params qa-proxysql2-pipeline
```

## Related Jobs

- `ps-*` – Percona Server MySQL jobs (ProxySQL works with PS)
- `pxc-*` – Percona XtraDB Cluster jobs (ProxySQL provides PXC load balancing)
- Connection pooling and query routing validation
- HA failover testing

## Code Owners

See `.github/CODEOWNERS` – ProxySQL pipelines maintained by:
- rameshvs02 (32 commits) – Primary contributor
- Mohit Joshi (27 commits) – Co-maintainer
- Venkatesh Prasad (8 commits) – Supporting contributor
- Vadim Yalovets (10 commits) – Infrastructure support

Primary contact: `@rameshvs02`, `@mohit-joshi`

## Status

Last activity: September 2025 (active)
Recent work: RHEL10 support, ongoing QA test improvements
Activity: 4 commits in last 6 months (most active non-PPG product)
