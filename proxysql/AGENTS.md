# AGENTS.md - ProxySQL Pipelines (Hetzner Branch)

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

ProxySQL CI/CD pipelines for MySQL proxy layer testing and builds. Includes ProxySQL 2.x and 3.x QA testing, source tarball generation, binary builds, and comprehensive test suite validation across multiple Linux distributions. ProxySQL provides connection pooling, query routing, and high availability for MySQL/Percona Server deployments.

## Key Directories

- `jenkins/` – Build scripts and utilities
- Root level – QA pipelines and test orchestration

## Key Files

- `qa-proxysql2-pipeline.groovy` – ProxySQL 2.x QA test suite
- `proxysql.groovy` – Main ProxySQL build and test pipeline (Hetzner)
- `proxysql3.groovy` – ProxySQL 3.x support (Hetzner)
- `jenkins/proxysql-tarball.groovy` – Source tarball generation
- Additional shell scripts: `build-binary-proxysql`, `run-*`, `test-proxysql`

## Product-Specific Patterns

### ProxySQL Version Support (Hetzner)

```groovy
// Hetzner branch supports both ProxySQL 2.x and 3.x
job: 'qa-proxysql2-pipeline'  // Version 2.x
job: 'proxysql3'              // Version 3.x (Hetzner addition)
```

### Build Pattern (Go-based)

```groovy
// ProxySQL uses C++/Go builds (different from other products)
buildType: 'native'
```

### Multi-Version Testing (Hetzner)

Hetzner branch adds ProxySQL 3.x testing alongside 2.x for version compatibility validation.

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job ps80 config qa-proxysql2-pipeline --yaml`
2. **Version coordination:** When updating, consider both 2.x and 3.x variants
3. **Build considerations:** ProxySQL uses native C++/Go builds
4. **Test coverage:** QA pipeline includes connection pooling, query routing, failover
5. **Parameter contracts:** Support both versions in release automation

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('proxysql/proxysql3.groovy'))"

# Build validation
bash jenkins/build-binary-proxysql --version 3.5.0

# Test suite
bash jenkins/test-proxysql --suite qa

# Jenkins dry-run
~/bin/jenkins build ps80/qa-proxysql2-pipeline \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculepxcJenkinsCreds()`
- **Key parameters:**
  - `VERSION` – ProxySQL version (2.x or 3.x)
  - `PROXYSQL_BRANCH` – Git branch
  - `PLATFORM` – OS selection
  - `BUILD_TYPE` – Build configuration

## Jenkins Instance

ProxySQL jobs run on: `ps80.cd.percona.com`

```bash
~/bin/jenkins job ps80 list | grep proxysql
```

## Related Jobs

- `ps-*` – Percona Server MySQL
- `pxc-*` – Percona XtraDB Cluster
- Connection pooling and query routing validation

## Code Owners

- rameshvs02 (32 commits) – Primary
- Mohit Joshi (27 commits) – Co-maintainer
- Vadim Yalovets (10 commits) – Infrastructure

## Status

Last activity: September 2025 (active)
Hetzner additions: ProxySQL 3.x support (proxysql3.groovy)
