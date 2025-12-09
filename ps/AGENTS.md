# AGENTS.md - PS Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona Server for MySQL builds (5.7, 8.0, 8.4, 9.x), package testing, MTR suites, InnoDB cluster
**Where**: Jenkins `ps80` | `https://ps80.cd.percona.com` | Jobs: `ps*`, `pdps*`
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `installMolecule*()`
**Watch Out**: RocksDB/sanitizer flags must align across build/test; coordinate with PXB/PXC teams

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `ps80` |
| URL | https://ps80.cd.percona.com |
| Job Patterns | `ps*`, `pdps*`, `proxysql*` |
| Default Credential | `ps-staging` (AWS) |
| AWS Region | `us-east-2` |
| Groovy Files | 25 |

## Scope

Percona Server for MySQL (PS) pipelines: server builds, package testing, Molecule-based distro coverage, MTR suites, upgrade checks, and integration with PXB/PXC jobs.

## Key Files

| File | Purpose |
|------|---------|
| `jenkins/percona-server-for-mysql-8.0.groovy` | PS 8.0 build |
| `jenkins/percona-server-for-mysql-5.7.groovy` | PS 5.7 build |
| `jenkins/percona-server-for-mysql-9.0.groovy` | PS 9.0 build |
| `jenkins/ps-package-testing-molecule.groovy` | Molecule testing |
| `jenkins/test-ps-innodb-cluster.groovy` | InnoDB cluster tests |
| `jenkins/mysql-shell.groovy` | MySQL Shell builds |
| `ps-site-check.groovy` | Site validation |

**ARM64 builds**: `jenkins/percona-server-for-mysql-8.0-arm.groovy`

## Key Jenkins Jobs

| Job Name | Purpose |
|----------|---------|
| `ps80-build` | Build PS 8.0 packages |
| `ps-package-testing` | Package validation |
| `ps-package-testing-molecule` | Molecule scenarios |
| `test-ps-innodb-cluster` | InnoDB cluster validation |
| `ps80-ami` | AMI builds |

## Version Matrix

| Version | Status | ARM64 | Notes |
|---------|--------|-------|-------|
| 9.x | Innovation | Yes | Preview |
| 8.4 | Active | Yes | Current |
| 8.0 | LTS | Yes | Primary |
| 5.7 | Maintenance | No | Legacy |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Mismatched RocksDB flags | Build/test incompatible | Align `--with-rocksdb` |
| Wrong sanitizer settings | ASAN/debug misalignment | Match `BUILD_TYPE` |
| Breaking artifact names | PXB/PXC depend on them | Coordinate downstream |
| Skipping MTR suites | Missing coverage | Include in test matrix |

## Agent Workflow

1. **Check live config**: `~/bin/jenkins params ps80/ps80-build`
2. **Build variants**: Keep debug/release flags consistent
3. **Downstream impact**: PS builds consumed by PXB/PXC
4. **Cleanup**: Long-running MTR needs `timeout` + `deleteDir()`

## PR Review Checklist

- [ ] `buildDiscarder(logRotator(...))` in options
- [ ] `deleteDir()` in `post.always`
- [ ] RocksDB/sanitizer flags aligned
- [ ] MTR suite coverage
- [ ] No artifact naming changes

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Artifact path | Breaks PXB, PXC builds | pxb-team, pxc-team |
| Build flags | Test compatibility | QA |
| Version matrix | Downstream updates | PDPS team |

## Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('ps/jenkins/percona-server-for-mysql-8.0.groovy'))"

# MTR subset
./mtr --suite=ps --parallel=4
```

## Jenkins CLI

```bash
~/bin/jenkins job ps80 list                   # All jobs
~/bin/jenkins job ps80 list | grep package    # Package jobs
~/bin/jenkins params ps80/<job>               # Parameters
~/bin/jenkins build ps80/<job> -p KEY=val     # Build
```

## Related

- `pdps/AGENTS.md` - Distribution packaging
- `pxb/AGENTS.md` - XtraBackup (depends on PS)
- `pxc/AGENTS.md` - XtraDB Cluster (depends on PS)
- `vars/AGENTS.md` - Shared helpers
