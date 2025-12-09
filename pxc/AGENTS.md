# AGENTS.md - PXC Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona XtraDB Cluster builds (5.7, 8.0), Galera testing, package validation, multi-node clusters
**Where**: Jenkins `pxc` | `https://pxc.cd.percona.com` | Jobs: `pxc*`, `proxysql*`
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `moleculeParallelPostDestroy()`
**Watch Out**: Galera wsrep settings must be consistent; always cleanup multi-node clusters

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `pxc` |
| URL | https://pxc.cd.percona.com |
| Job Patterns | `pxc*`, `proxysql*`, `pdpxc*` |
| Default Credential | `pxc-staging` (AWS) |
| AWS Region | `us-east-2` |
| Groovy Files | 24 |

## Scope

Percona XtraDB Cluster (PXC) CI/CD pipelines: Galera cluster builds, package testing across distros/architectures, Molecule multi-node tests, and upgrade/compatibility validation.

## Key Files

| File | Purpose |
|------|---------|
| `jenkins/percona-xtradb-cluster-8.0.groovy` | PXC 8.0 build |
| `jenkins/percona-xtradb-cluster-5.7.groovy` | PXC 5.7 build |
| `jenkins/pxc-package-testing.groovy` | Package validation |
| `jenkins/pxc-package-testing-parallel.groovy` | Parallel distro testing |
| `jenkins/pxc80-pipeline.groovy` | PXC 8.0 CI pipeline |
| `jenkins/proxysql-package-testing.groovy` | ProxySQL testing |
| `pxc-site-check.groovy` | Site validation |

**ARM64 builds**: `jenkins/percona-xtradb-cluster-8.0-arm.groovy`

## Key Jenkins Jobs

| Job Name | Purpose |
|----------|---------|
| `pxc80-build` | Build PXC 8.0 packages |
| `pxc-package-testing` | Test packages across distros |
| `pxc80-pipeline` | Full CI pipeline |
| `proxysql-package-testing` | ProxySQL integration |
| `qa-pxc80-pipeline` | QA test suite |

## Version Matrix

| Version | Status | ARM64 |
|---------|--------|-------|
| 8.0 | Active | Yes |
| 5.7 | Maintenance | No |
| 5.6 | Legacy | No |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Inconsistent wsrep settings | Galera cluster won't form | Keep wsrep_* params aligned |
| Skipping multi-node cleanup | Leaves 3-node cluster running | `moleculeParallelPostDestroy()` |
| Wrong `pxc_strict_mode` | SST/IST failures | Match build/test settings |
| Forgetting ProxySQL tests | Breaks load balancer validation | Include in test matrix |

## Agent Workflow

1. **Check live config**: `~/bin/jenkins params pxc/pxc80-build`
2. **Galera settings**: Keep `wsrep_provider_options` consistent
3. **Multi-node tests**: Always 3-node minimum for split-brain testing
4. **Cleanup**: `moleculeParallelPostDestroy()` + `deleteDir()` in `post`

## PR Review Checklist

- [ ] `buildDiscarder(logRotator(...))` in options
- [ ] `deleteDir()` in `post.always`
- [ ] Galera wsrep settings consistent
- [ ] Multi-node cluster cleanup
- [ ] ProxySQL tests included if applicable

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Galera params | SST/IST behavior | QA team |
| Package naming | Breaks PDPXC | Distribution team |
| Version matrix | Platform updates | QA |

## Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('pxc/jenkins/pxc80-pipeline.groovy'))"

# Molecule smoke
cd molecule/pxc && molecule test -s ubuntu-jammy
```

## Jenkins CLI

```bash
~/bin/jenkins job pxc list                    # All jobs
~/bin/jenkins job pxc list | grep pxc80       # PXC 8.0 jobs
~/bin/jenkins params pxc/<job>                # Parameters
~/bin/jenkins build pxc/<job> -p KEY=val      # Build
```

## Related

- `pdpxc/AGENTS.md` - Distribution packaging
- `proxysql/AGENTS.md` - ProxySQL pipelines
- `vars/AGENTS.md` - Shared helpers
