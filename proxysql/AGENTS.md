# AGENTS.md - ProxySQL Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: ProxySQL 2.x builds, QA testing, connection pooling/query routing validation
**Where**: Jenkins `ps80` | `https://ps80.cd.percona.com` | Jobs: `*proxysql*`
**Key Helpers**: `moleculepxcJenkinsCreds()` (uses PXC credentials)
**Watch Out**: C++/Go native builds (not Docker-based); works with PS and PXC

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `ps80` |
| URL | https://ps80.cd.percona.com |
| Job Patterns | `*proxysql*`, `qa-proxysql2-*` |
| Default Credential | `c42456e5-c28d-4962-b32c-b75d161bff27` (AWS) |
| Build Type | Native C++/Go |
| Groovy Files | 3 |
| Last Updated | 2025-12 |

## Job Dependency Graph

```
proxysql.groovy (standalone, matrix execution)
   │
   └── Parallel builds: x64 + ARM64 across 9 platforms

qa-proxysql2-pipeline.groovy (standalone)
   │
   └── Parallel: Build → Test stages

proxysql-tarball.groovy (standalone)
   │
   └── Source tarball generation
```

**No downstream triggers** - ProxySQL jobs are standalone.

## Directory Map

```
proxysql/                               # 605 lines total
├── AGENTS.md                           # This file
├── proxysql.groovy               (385) # Main build pipeline (9 platforms)
├── qa-proxysql2-pipeline.groovy  (136) # QA test suite
├── proxysql.yml                        # JJB config
├── qa-proxysql2-param.yml              # QA job parameters
├── qa-proxysql2-pipeline.yml           # QA JJB config
├── jenkins/                            # 1 groovy file
│   ├── proxysql-tarball.groovy   (84)  # Source tarball
│   └── proxysql-tarball.yml            # JJB config
├── build-binary-proxysql               # Build script
├── test-proxysql                       # Test script
├── run-build-proxysql                  # Run script
├── run-test-proxysql                   # Run script
└── checkout                            # Git checkout helper
```

## Key Jobs from Jenkins

| Job | Status | Purpose |
|-----|--------|---------|
| `proxysql-package-testing-all` | NotBuilt | Package validation |
| `qa-proxysql2-pipeline` | - | QA test suite |

## Platform Matrix

| Platform | x64 | ARM64 |
|----------|-----|-------|
| Oracle Linux 8 | Yes | Yes |
| Oracle Linux 9 | Yes | Yes |
| Debian 11 | Yes | Yes |
| Debian 12 | Yes | Yes |
| Ubuntu Focal | Yes | - |
| Ubuntu Jammy | Yes | Yes |
| Ubuntu Noble | Yes | Yes |
| Amazon Linux 2 | Yes | - |
| RHEL 10 | Yes | - |

## Credentials

| ID | Purpose | Used In |
|----|---------|---------|
| `c42456e5-c28d-4962-b32c-b75d161bff27` | AWS S3/EC2 | qa-proxysql2-pipeline |
| `PS_PRIVATE_REPO_ACCESS` | PRO repo access | proxysql-tarball |

## Agent Labels

| Label | Purpose | Files |
|-------|---------|-------|
| `docker` | Standard x64 builds | proxysql.groovy (most) |
| `docker-32gb` | Memory-intensive x64 | proxysql.groovy (Amazon Linux) |
| `docker-32gb-aarch64` | ARM64 builds | proxysql.groovy (ARM stages) |
| `micro-amazon` | Lightweight init | qa-proxysql2-pipeline |
| `min-bookworm-x64` | Tarball builds | proxysql-tarball |

## Key Jira Tickets

| Ticket | Summary |
|--------|---------|
| PSQLADM-573 | ProxySQL admin scripts testing job uses compiled version |
| PSQLADM-555 | Fetch latest versions of PXC packages |

## ProxySQL Features Tested

- Connection pooling
- Query routing
- Load balancing
- Failover handling
- MySQL/PXC compatibility
- Admin interface scripts

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Wrong build type | ProxySQL is C++/Go, not Docker | Use native build scripts |
| Missing PXC creds | ProxySQL tests with PXC | Use `moleculepxcJenkinsCreds()` |
| Skipping ARM64 | Incomplete coverage | Include ARM64 in matrix |
| Wrong Jenkins instance | ProxySQL on ps80, not pxc | Use `ps80` instance |

## Jenkins CLI Quick Reference

```bash
# List all ProxySQL jobs
~/bin/jenkins job ps80 list | rg -i proxysql

# Get job status
~/bin/jenkins status ps80/proxysql-package-testing-all

# Get parameters
~/bin/jenkins params ps80/qa-proxysql2-pipeline

# Trigger a build
~/bin/jenkins build ps80/qa-proxysql2-pipeline -p PLATFORM=generic-oracle-linux-9-x64

# View logs
~/bin/jenkins logs ps80/qa-proxysql2-pipeline

# Check history
~/bin/jenkins history ps80/qa-proxysql2-pipeline
```

## Local Validation

```bash
# Groovy syntax check
groovy -e "new GroovyShell().parse(new File('proxysql/proxysql.groovy'))"
groovy -e "new GroovyShell().parse(new File('proxysql/qa-proxysql2-pipeline.groovy'))"

# Build validation
bash proxysql/build-binary-proxysql --version 2.5.5

# Test suite validation
bash proxysql/test-proxysql --suite qa
```

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Version params | Release automation | RelEng |
| Platform support | Test coverage | QA |
| Build scripts | Binary generation | Build team |

## Related

- [ps/AGENTS.md](../ps/AGENTS.md) - Percona Server (ProxySQL works with PS)
- [pxc/AGENTS.md](../pxc/AGENTS.md) - XtraDB Cluster (ProxySQL provides PXC load balancing)
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers

## GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [sysown/proxysql](https://github.com/sysown/proxysql) | Upstream source |
| [percona/proxysql-packaging](https://github.com/percona/proxysql-packaging) | Percona packaging |
