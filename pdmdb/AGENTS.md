# AGENTS.md - PDMDB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona Distribution for MongoDB - package testing, multi-version setup, upgrade validation
**Where**: Jenkins `psmdb` | `https://psmdb.cd.percona.com` | Jobs: `pdmdb-*`
**Key Helpers**: `moleculePbmJenkinsCreds()`, `moleculeExecuteActionWithScenario()`
**Watch Out**: Depends on PSMDB artifacts; follow release automation contracts

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `psmdb` |
| URL | https://psmdb.cd.percona.com |
| Job Patterns | `pdmdb-*`, `pdmdb-parallel`, `pdmdb-upgrade*` |
| Default Credential | `moleculePbmJenkinsCreds()` |
| AWS Region | `us-east-2` |
| Groovy Files | ~5 |

## Scope

Percona Distribution for MongoDB (PDMDB) CI/CD pipelines. Includes distribution package testing, multi-version setup, upgrade validation, and integration testing across multiple Linux distributions.

## Key Files

- `pdmdb-setup.groovy` – Initial PDMDB setup and installation
- `pdmdb-parallel.groovy` – Parallel distribution testing across platforms
- `pdmdb-upgrade.groovy` – Upgrade path testing (minor/major versions)
- `pdmdb-multi.groovy` / `pdmdb-multi-parallel.groovy` – Multi-version testing orchestration
- `pdmdb-site-check.groovy` – Site validation checks

## Product-Specific Patterns

### Distribution Testing Pattern

```groovy
// Standard PDMDB test scenarios
moleculeExecute(
    scenario: 'pdmdb-setup',
    platform: params.PLATFORM,
    version: params.VERSION
)
```

### Molecule Credentials

```groovy
// Use PDMDB-specific Molecule credentials
moleculePbmJenkinsCreds()
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job psmdb config pdmdb-parallel --yaml` to capture parameters like `VERSION`, `PLATFORM`, `SCENARIO`.
2. **Reuse distribution patterns:** Follow established patterns from `psmdb/` pipelines for consistency.
3. **Platform matrix:** Use standard Molecule platform lists from `vars/` helpers.
4. **Lifecycle cleanup:** Always include cleanup in `post { always { ... } }` blocks.
5. **Parameter contracts:** PDMDB jobs follow release automation contracts; extend but never rename/remove parameters.

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('pdmdb/pdmdb-parallel.groovy'))"

# Molecule testing (local validation)
cd /path/to/psmdb-testing
molecule test -s pdmdb-setup

# Jenkins dry-run
~/bin/jenkins build psmdb/pdmdb-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  -p VERSION=pdmdb-8.0 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculePbmJenkinsCreds()` – AWS/SSH for Molecule testing
- **Key parameters:**
  - `VERSION` – PDMDB version (e.g., 'pdmdb-8.0')
  - `PLATFORM` – OS selection via Molecule platform helpers
  - `SCENARIO` – Test scenario name
  - `REPO` – Repository selection (testing/release/experimental)

# Jenkins

Instance: `psmdb` | URL: `https://psmdb.cd.percona.com`

## CLI
```bash
~/bin/jenkins job psmdb list | grep pdmdb           # All PDMDB jobs
~/bin/jenkins params psmdb/<job>                    # Parameters
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("pdmdb"))'
```

## Job Patterns
`pdmdb-*`, `pdmdb-parallel`, `pdmdb-upgrade*`

## Credentials
`moleculePbmJenkinsCreds()` (AWS/SSH). Always use `withCredentials`.

## Related Jobs

- `psmdb-*` – Related Percona Server MongoDB jobs
- Distribution testing orchestration
- Package validation workflows

## Code Owners

See `.github/CODEOWNERS` – PDMDB pipelines maintained by:
- Oleksandr Havryliak (15 commits) – Primary contributor
- Mikhail Samoylov (11 commits) – Supporting contributor

Primary contact: `@oleksandr-havryliak`

## Status

Last activity: July 2024 (dormant)
Maintenance mode: Stable distribution testing infrastructure
