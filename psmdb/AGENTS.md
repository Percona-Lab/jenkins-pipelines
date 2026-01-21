# AGENTS.md - PSMDB Pipelines

> **Active Branch**: Production PSMDB jobs run from the `hetzner` branch with `hetzner-*` job prefixes.
> See the [hetzner branch AGENTS.md](https://github.com/Percona-Lab/jenkins-pipelines/blob/hetzner/psmdb/AGENTS.md) for comprehensive documentation.

Extends: [../AGENTS.md](../AGENTS.md) | [../vars/AGENTS.md](../vars/AGENTS.md)

Related: [pbm/AGENTS.md](../pbm/AGENTS.md) | [pdmdb/AGENTS.md](../pdmdb/AGENTS.md)

## TL;DR

**What**: PSMDB builds (6.0-8.0), package testing, replica/sharding validation, PBM integration
**Where**: Jenkins `psmdb` | `https://psmdb.cd.percona.com` | Jobs: `hetzner-psmdb*`, `hetzner-pbm*`, `hetzner-pdmdb*`
**Active Versions**: 6.0, 7.0, 8.0 (older versions disabled)
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `pbmVersion()`, `moleculePbmJenkinsCreds()`
**Watch Out**: Artifact naming consumed by PBM - coordinate changes; always destroy Molecule clusters

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `psmdb` |
| URL | https://psmdb.cd.percona.com |
| Active Branch | `hetzner` |
| Job Patterns | `hetzner-psmdb*`, `hetzner-pbm*`, `hetzner-pcsm*` |
| Infrastructure | Hetzner (migrated from AWS) |
| Groovy Files | 46 |

## Scope

Percona Server for MongoDB (PSMDB) pipelines: server builds, package testing, replica set & sharding validation, encryption/FIPS coverage, backup/restore flows, and PBM integration.

## Key Files

| File | Purpose |
|------|---------|
| `jenkins/percona-server-for-mongodb-8.0.groovy` | PSMDB 8.0 build |
| `jenkins/percona-server-for-mongodb-7.0.groovy` | PSMDB 7.0 build |
| `jenkins/percona-server-for-mongodb-6.0.groovy` | PSMDB 6.0 build |
| `psmdb-parallel.groovy` | Parallel package testing |
| `psmdb-upgrade.groovy` | Upgrade path validation |
| `psmdb-docker.groovy` | Docker image builds |
| `psmdb-fips.groovy` | FIPS/encryption testing |
| `psmdb-tarball.groovy` | Tarball distribution |

**ARM64 builds**: `jenkins/percona-server-for-mongodb-*-aarch64.groovy`

## Key Jenkins Jobs

| Job Name | Purpose |
|----------|---------|
| `psmdb-build` | Build packages (triggers downstream) |
| `psmdb-package-testing` | Test packages across distros |
| `psmdb-parallel` | Parallel multi-distro testing |
| `psmdb-molecule-*` | Molecule scenario execution |
| `psmdb-upgrade-*` | Upgrade path validation |

## Version Matrix

| Version | Status | ARM64 |
|---------|--------|-------|
| 8.0 | Active | Yes |
| 7.0 | Active | Yes |
| 6.0 | Maintenance | Yes |
| 5.0 | Legacy | Yes |

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Changing artifact names | Breaks PBM jobs | Coordinate with PBM team |
| Skipping `moleculeParallelPostDestroy()` | Leaves EC2/Molecule running | Always in `post.always` |
| Hardcoding version | Breaks multi-version matrix | Use `PSMDB_VERSION` param |
| Missing `moleculePbmJenkinsCreds()` | PBM tests fail auth | Add when using PBM |

## Agent Workflow

1. **Check live config**: `~/bin/jenkins params psmdb/psmdb-build`
2. **Search helpers**: `rg "molecule" vars/` before writing custom logic
3. **PBM integration**: Keep credentials synced with `pbm/AGENTS.md`
4. **Cleanup**: Always `moleculeParallelPostDestroy()` + `deleteDir()` in `post`

## PR Review Checklist

- [ ] `buildDiscarder(logRotator(...))` in options
- [ ] `deleteDir()` in `post.always`
- [ ] `withCredentials()` for all secrets
- [ ] Version matrix updated if adding version
- [ ] Molecule cleanup in `post` blocks
- [ ] No hardcoded artifact paths

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Artifact naming | Breaks PBM, PDMDB | PBM team, Distribution team |
| Parameter removal | Breaks downstream | RelEng |
| Version matrix | Platform updates | QA |

## Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('psmdb/psmdb-parallel.groovy'))"

# Molecule smoke
cd molecule/psmdb && molecule test -s ubuntu-jammy
```

## Jenkins CLI

```bash
~/bin/jenkins job psmdb list                        # All jobs
~/bin/jenkins job psmdb list | grep 'psmdb[678]0'   # By version
~/bin/jenkins params psmdb/<job>                    # Parameters
~/bin/jenkins build psmdb/<job> -p KEY=val          # Build
```

## Related

- `pbm/AGENTS.md` - Backup integration
- `pdmdb/AGENTS.md` - Distribution packaging
- `vars/AGENTS.md` - Shared helpers (moleculeExecute*, pbmVersion)
