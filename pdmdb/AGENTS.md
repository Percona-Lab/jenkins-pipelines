# AGENTS.md - PDMDB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Distribution for MongoDB (PDMDB) CI/CD pipelines. Includes distribution package testing, multi-version setup, upgrade validation, and integration testing across multiple Linux distributions.

## Key Files

- `pdmdb-setup.groovy` / `pdmdb-setup-parallel.groovy` – Initial PDMDB setup
- `pdmdb-parallel.groovy` / `pdmdb.groovy` – Distribution testing
- `pdmdb-upgrade.groovy` / `pdmdb-upgrade-parallel.groovy` – Upgrade path testing
- `pdmdb-multi.groovy` / `pdmdb-multi-parallel.groovy` – Multi-version orchestration
- `pdmdb-site-check.groovy` – Site validation (Hetzner)

## Product-Specific Patterns

### Distribution Testing Pattern

```groovy
moleculeExecute(
    scenario: 'pdmdb-setup',
    platform: params.PLATFORM,
    version: params.VERSION
)
```

### Molecule Credentials

```groovy
moleculePbmJenkinsCreds()
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job psmdb config pdmdb-parallel --yaml`
2. **Reuse distribution patterns:** Follow `psmdb/` pipeline patterns
3. **Platform matrix:** Use standard Molecule platform lists
4. **Lifecycle cleanup:** Include cleanup in `post { always { ... } }`
5. **Parameter contracts:** Extend but never rename/remove parameters

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('pdmdb/pdmdb-parallel.groovy'))"

# Molecule testing
cd /path/to/psmdb-testing
molecule test -s pdmdb-setup

# Jenkins dry-run
~/bin/jenkins build psmdb/pdmdb-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculePbmJenkinsCreds()`
- **Key parameters:** `VERSION`, `PLATFORM`, `SCENARIO`, `REPO`

## Jenkins Instance

PDMDB jobs run on: `psmdb.cd.percona.com`

```bash
~/bin/jenkins job psmdb list | grep pdmdb
```

## Related Jobs

- `psmdb-*` – Percona Server MongoDB jobs
- Distribution testing orchestration

## Code Owners

- Oleksandr Havryliak (15 commits) – Primary
- Mikhail Samoylov (11 commits) – Supporting

## Status

Last activity: July 2024 (dormant)
