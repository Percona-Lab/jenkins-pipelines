# AGENTS.md - PDPS Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona Distribution for PS - package testing, orchestrator, Perl DBD-MySQL, upgrade validation
**Where**: Jenkins `ps80` | `https://ps80.cd.percona.com` | Jobs: `pdps-*`, `orchestrator*`
**Key Helpers**: `moleculePdpsJenkinsCreds()`, `moleculeExecuteActionWithScenario()`
**Watch Out**: Depends on PS artifacts; includes orchestrator HA testing

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `ps80` |
| URL | https://ps80.cd.percona.com |
| Job Patterns | `pdps-*`, `orchestrator*`, `perl-DBD-*` |
| Default Credential | `moleculePdpsJenkinsCreds()` |
| AWS Region | `us-east-2` |
| Groovy Files | 10 |
| Last Updated | 2025-12 |

## Job Dependency Graph

```
pdps-multi-parallel.groovy (orchestrator)
   │
   ├── pdps-parallel (3x different scenarios)
   └── pdps-upgrade-parallel

pdps-multi.groovy (sequential orchestrator)
   │
   ├── pdps (3x different scenarios)
   └── pdps-upgrade
```

## Directory Map

```
pdps/                                   # 1,596 lines total
├── AGENTS.md                           # This file
├── perl-DBD-Mysql.groovy         (345) # Perl DBD-MySQL testing
├── pdps-multi.groovy             (196) # Sequential orchestration
├── pdps-multi-parallel.groovy    (180) # Parallel orchestration
├── pdps.groovy                   (164) # Main distribution testing
├── pdps-upgrade.groovy           (163) # Upgrade testing
├── pdps-upgrade-parallel.groovy  (130) # Parallel upgrades
├── pdps-parallel.groovy          (127) # Parallel testing
├── orchestrator.groovy           (115) # Orchestrator HA
├── orchestrator_docker.groovy     (94) # Orchestrator Docker
├── pdps-site-check.groovy         (82) # Site validation
└── *.yml                               # JJB configs
```

## Key Jobs from Jenkins

| Job | Status | Purpose |
|-----|--------|---------|
| `orchestrator` | SUCCESS | Orchestrator HA testing |
| `orchestrator_docker` | SUCCESS | Orchestrator Docker testing |
| `mysql-orchestrator-pipeline` | SUCCESS | Orchestrator pipeline |
| `pdps` | FAILED | Main distribution testing |
| `pdps-parallel` | FAILED | Parallel testing |
| `pdps-upgrade` | FAILED | Upgrade testing |
| `pdps-upgrade-parallel` | FAILED | Parallel upgrades |

## Components Tested

| Component | Pipeline | Purpose |
|-----------|----------|---------|
| PDPS Distribution | pdps-parallel | Package installation/testing |
| Orchestrator | orchestrator.groovy | MySQL HA management |
| Orchestrator Docker | orchestrator_docker.groovy | Containerized orchestrator |
| Perl DBD-MySQL | perl-DBD-Mysql.groovy | Perl driver compatibility |

## Version Matrix

| Version | Status | Upgrade From |
|---------|--------|--------------|
| PDPS 8.4 | LTS | 8.0 |
| PDPS 8.0 | Active | 5.7 |
| PDPS 5.7 | Maintenance | - |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Missing PS artifacts | PDPS depends on PS | Ensure PS builds complete first |
| Skipping orchestrator tests | Incomplete distribution | Include orchestrator in test matrix |
| Wrong orchestrator variant | Docker vs direct | Choose correct variant for use case |

## Jenkins CLI Quick Reference

```bash
# List all PDPS jobs
~/bin/jenkins job ps80 list | grep pdps

# Get job status
~/bin/jenkins status ps80/orchestrator

# Get parameters
~/bin/jenkins params ps80/pdps-parallel

# Trigger a build
~/bin/jenkins build ps80/pdps-parallel -p PLATFORM=generic-oracle-linux-9-x64 -p VERSION=pdps-8.0

# View logs
~/bin/jenkins logs ps80/orchestrator_docker
```

## Local Validation

```bash
# Groovy syntax check
groovy -e "new GroovyShell().parse(new File('pdps/pdps-parallel.groovy'))"

# Molecule testing
cd /path/to/ps-testing && molecule test -s pdps-setup

# Orchestrator testing
molecule test -s pdps-orchestrator
```

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Version params | Release automation | RelEng |
| Orchestrator config | HA behavior | DBA team |
| Perl DBD changes | Driver compatibility | QA |

## Related

- [ps/AGENTS.md](../ps/AGENTS.md) - PS builds (PDPS depends on these)
- [proxysql/AGENTS.md](../proxysql/AGENTS.md) - ProxySQL (often bundled)
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers

## GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-server](https://github.com/percona/percona-server) | PS source |
| [openark/orchestrator](https://github.com/openark/orchestrator) | Orchestrator upstream |
| [Percona-QA/package-testing](https://github.com/Percona-QA/package-testing) | Package test scenarios |
