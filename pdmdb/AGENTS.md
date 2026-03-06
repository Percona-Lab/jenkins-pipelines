# AGENTS.md - PDMDB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona Distribution for MongoDB - package testing, multi-version setup, upgrade validation
**Where**: Jenkins `psmdb` | `https://psmdb.cd.percona.com` | Jobs: `pdmdb-*`, `hetzner-pdmdb-*`
**Key Helpers**: `moleculePbmJenkinsCreds()`, `moleculeExecuteActionWithScenario()`
**Watch Out**: Depends on PSMDB artifacts; follow release automation contracts

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `psmdb` |
| URL | https://psmdb.cd.percona.com |
| Job Patterns | `pdmdb-*`, `hetzner-pdmdb-*` |
| Default Credential | `moleculePbmJenkinsCreds()` |
| AWS Region | `us-east-2` |
| Groovy Files | 9 |
| Last Updated | 2025-12 |

## Job Dependency Graph

```
pdmdb-multi-parallel.groovy (orchestrator, fanout: 4)
   │
   ├── pdmdb-parallel (distribution testing)
   ├── pdmdb-setup-parallel (initial setup)
   └── pdmdb-upgrade-parallel (2x: different upgrade paths)

pdmdb-multi.groovy (sequential, fanout: 4)
   │
   ├── pdmdb (distribution testing)
   ├── pdmdb-setup (initial setup)
   └── pdmdb-upgrade (2x: different upgrade paths)
```

**Fanout Pattern**: Multi-parallel triggers 4 downstream jobs; upgrade runs twice with different version params.

## Directory Map

```
pdmdb/                                  # 964 lines total
├── AGENTS.md                           # This file
├── pdmdb-upgrade.groovy          (148) # Upgrade path testing
├── pdmdb-upgrade-parallel.groovy (144) # Parallel upgrades
├── pdmdb-multi.groovy            (122) # Sequential orchestration
├── pdmdb-multi-parallel.groovy   (113) # Parallel orchestration
├── pdmdb.groovy                  (104) # Main distribution testing
├── pdmdb-setup.groovy             (98) # Initial setup
├── pdmdb-parallel.groovy          (93) # Parallel testing
├── pdmdb-site-check.groovy        (72) # Site validation
├── pdmdb-setup-parallel.groovy    (70) # Parallel setup
└── *.yml                               # JJB configs
```

## Key Jobs from Jenkins

| Job | Status | Purpose |
|-----|--------|---------|
| `pdmdb` | SUCCESS | Main distribution testing |
| `pdmdb-parallel` | SUCCESS | Parallel testing |
| `pdmdb-setup` | SUCCESS | Initial setup |
| `pdmdb-setup-parallel` | SUCCESS | Parallel setup |
| `pdmdb-upgrade` | SUCCESS | Upgrade testing |
| `pdmdb-upgrade-parallel` | SUCCESS | Parallel upgrades |
| `pdmdb-multi` | SUCCESS | Sequential orchestration |
| `pdmdb-multi-parallel` | SUCCESS | Parallel orchestration |
| `hetzner-pdmdb-site-check` | SUCCESS | Site check (Hetzner) |

## Version Matrix

| Version | Status | Upgrade From |
|---------|--------|--------------|
| PDMDB 8.0 | Active | 7.0 |
| PDMDB 7.0 | Active | 6.0 |
| PDMDB 6.0 | Maintenance | 5.0 |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Missing PSMDB artifacts | PDMDB depends on PSMDB | Ensure PSMDB builds complete first |
| Using wrong orchestrator | Multi vs multi-parallel | Choose based on parallelism needs |
| Skipping upgrade tests | Incomplete validation | Include upgrade paths in test matrix |

## Jenkins CLI Quick Reference

```bash
# List all PDMDB jobs
~/bin/jenkins job psmdb list | rg -i pdmdb

# Get job status
~/bin/jenkins status psmdb/pdmdb-parallel
~/bin/jenkins status psmdb/hetzner-pdmdb-site-check

# Get parameters
~/bin/jenkins params psmdb/pdmdb-setup

# Trigger a build
~/bin/jenkins build psmdb/pdmdb-parallel -p PLATFORM=generic-oracle-linux-9-x64 -p VERSION=pdmdb-8.0

# View logs
~/bin/jenkins logs psmdb/pdmdb-upgrade

# Check history
~/bin/jenkins history psmdb/pdmdb-multi-parallel
```

## Local Validation

```bash
# Groovy syntax check
groovy -e "new GroovyShell().parse(new File('pdmdb/pdmdb-parallel.groovy'))"

# Molecule testing
cd /path/to/psmdb-testing && molecule test -s pdmdb-setup
```

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Version params | Release automation | RelEng |
| Upgrade paths | Compatibility testing | QA |
| Platform support | Test coverage | Build team |

## Related

- [psmdb/AGENTS.md](../psmdb/AGENTS.md) - PSMDB builds (PDMDB depends on these)
- [pbm/AGENTS.md](../pbm/AGENTS.md) - PBM integration
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers

## GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-server-mongodb](https://github.com/percona/percona-server-mongodb) | PSMDB source |
| [percona/percona-server-mongodb-packaging](https://github.com/percona/percona-server-mongodb-packaging) | Packaging |
| [Percona-QA/psmdb-testing](https://github.com/Percona-QA/psmdb-testing) | Test scenarios |
