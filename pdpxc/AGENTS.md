# AGENTS.md - PDPXC Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona Distribution for PXC - Galera clustering, HAProxy, PRM, upgrade validation
**Where**: Jenkins `pxc` | `https://pxc.cd.percona.com` | Jobs: `pdpxc-*`, `haproxy`
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `moleculeParallelPostDestroy()`
**Watch Out**: Depends on PXC artifacts; includes HAProxy and operator integration

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `pxc` |
| URL | https://pxc.cd.percona.com |
| Job Patterns | `pdpxc-*`, `haproxy`, `percona-replication-manager` |
| Default Credential | `pxc-staging` |
| AWS Region | `us-east-2` |
| Groovy Files | 10 |
| Last Updated | 2025-12 |

## Job Dependency Graph

```
pdpxc-multi-parallel.groovy (orchestrator, fanout: 5)
   │
   ├── pdpxc-parallel (3x: install, setup, pro scenarios)
   ├── pdpxc-upgrade-parallel (upgrade path testing)
   └── haproxy (load balancer tests)

pdpxc-multi.groovy (sequential, fanout: 5)
   │
   ├── pdpxc (3x: install, setup, pro scenarios)
   ├── pdpxc-upgrade
   └── haproxy

pdpxc-pxco-integration-scheduler.groovy
   │
   └── Triggers PXC Operator integration tests
```

**Fanout Pattern**: Multi-parallel orchestrator triggers 5 downstream jobs in parallel.

## Directory Map

```
pdpxc/                                  # 1,782 lines total
├── AGENTS.md                           # This file
├── percona-replication-manager.groovy (355) # PRM testing (ARM64 + x64)
├── pdpxc-multi.groovy              (216) # Multi-version orchestration
├── haproxy.groovy                  (202) # HAProxy load balancer
├── pdpxc-multi-parallel.groovy     (198) # Parallel orchestrator
├── pdpxc-upgrade-parallel.groovy   (177) # Parallel upgrades
├── pdpxc-upgrade.groovy            (162) # Upgrade testing
├── pdpxc.groovy                    (158) # Main distribution testing
├── pdpxc-parallel.groovy           (122) # Parallel testing
├── pdpxc-pxco-integration-scheduler.groovy (106) # Operator integration
├── pdpxc-site-check.groovy          (86) # Site validation
└── *.yml                                 # JJB configs
```

## Key Jobs from Jenkins

| Job | Status | Purpose |
|-----|--------|---------|
| `pdpxc-upgrade-parallel` | SUCCESS | Parallel upgrade testing |
| `pdpxc-upgrade` | SUCCESS | Upgrade path validation |
| `pdpxc-integration-push-test` | SUCCESS | Integration push tests |
| `pdpxc-parallel` | FAILED | Parallel distribution testing |
| `haproxy` | FAILED | HAProxy load balancer tests |
| `pdpxc-multi-parallel` | FAILED | Multi-version orchestration |

## Agent Labels

| Label | Purpose | Files |
|-------|---------|-------|
| `docker` | Standard builds | percona-replication-manager (most) |
| `docker-32gb-aarch64` | ARM64 builds | percona-replication-manager |
| `min-bookworm-x64` | Molecule controller | pdpxc.groovy |
| `min-centos-7-x64` | Legacy orchestration | pdpxc-multi-parallel |

## Components Tested

| Component | Pipeline | Purpose |
|-----------|----------|---------|
| PDPXC Distribution | pdpxc-parallel | Package installation/testing |
| HAProxy | haproxy.groovy | Load balancer integration |
| Percona Replication Manager | percona-replication-manager | PRM validation |
| PXC Operator | pdpxc-pxco-integration-scheduler | K8s operator integration |

## Version Matrix

| Version | Status | Upgrade From |
|---------|--------|--------------|
| PDPXC 8.4 | LTS | 8.0 |
| PDPXC 8.0 | Active | 5.7 |
| PDPXC 5.7 | Maintenance | - |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Missing PXC artifacts | PDPXC depends on PXC | Ensure PXC builds complete first |
| Skipping HAProxy tests | Incomplete distribution | Include HAProxy in test matrix |
| Ignoring ARM64 | PRM has ARM64 builds | Include ARM64 stages |
| Wrong orchestrator | Multi-parallel vs multi | Use correct orchestration job |

## Jenkins CLI Quick Reference

```bash
# List all PDPXC jobs
~/bin/jenkins job pxc list | rg -i pdpxc

# Get job status
~/bin/jenkins status pxc/pdpxc-upgrade-parallel
~/bin/jenkins status pxc/pdpxc-integration-push-test

# Get parameters
~/bin/jenkins params pxc/pdpxc-parallel

# Trigger a build
~/bin/jenkins build pxc/pdpxc-parallel -p PLATFORM=generic-oracle-linux-9-x64 -p VERSION=pdpxc-8.0

# View logs
~/bin/jenkins logs pxc/pdpxc-upgrade-parallel

# Check history
~/bin/jenkins history pxc/pdpxc-upgrade-parallel
~/bin/jenkins history pxc/pdpxc-multi-parallel
```

## Local Validation

```bash
# Groovy syntax check
groovy -e "new GroovyShell().parse(new File('pdpxc/pdpxc-parallel.groovy'))"

# Molecule testing
cd /path/to/pxc-testing && molecule test -s pdpxc-setup

# HAProxy testing
molecule test -s pdpxc-haproxy
```

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Version params | Release automation | RelEng |
| HAProxy config | Load balancer behavior | QA |
| PRM changes | Replication management | DBA team |
| Operator integration | K8s testing | Cloud team |

## Related

- [pxc/AGENTS.md](../pxc/AGENTS.md) - PXC builds (PDPXC bundles PXC)
- [proxysql/AGENTS.md](../proxysql/AGENTS.md) - ProxySQL (PDPXC bundles ProxySQL)
- [pxb/AGENTS.md](../pxb/AGENTS.md) - XtraBackup (PDPXC bundles PXB)
- [cloud/AGENTS.md](../cloud/AGENTS.md) - PXCO operator integration tests
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers

## GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-xtradb-cluster](https://github.com/percona/percona-xtradb-cluster) | PXC source |
| [Percona-QA/pxc-qa](https://github.com/Percona-QA/pxc-qa) | PXC testing tool |
