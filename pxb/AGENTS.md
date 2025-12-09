# AGENTS.md - PXB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Percona XtraBackup builds (2.4, 8.0), backup/restore testing, PS/PXC compatibility
**Where**: Jenkins `pxb` | `https://pxb.cd.percona.com` | Jobs: `pxb*`
**Key Helpers**: `moleculeExecuteActionWithScenario()`, `launchSpotInstance()`, `ccache*()`
**Watch Out**: Must test against PS and PXC releases; coordinate artifact naming with downstream

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `pxb` |
| URL | https://pxb.cd.percona.com |
| Job Patterns | `pxb80-*`, `pxb24-*`, `pxb-*-testing` |
| Default Credential | `pxb-staging` (AWS) |
| AWS Region | `us-east-2` |
| Groovy Files | 10 |

## Scope

Percona XtraBackup (PXB) pipelines: build artifacts, package validation, Molecule/functional backup + restore testing, and compatibility checks with PS and PXC releases.

## Key Files

| File | Purpose |
|------|---------|
| `jenkins/pxb-80.groovy` | PXB 8.0 build |
| `jenkins/pxb-24.groovy` | PXB 2.4 build |
| `jenkins/pxb-pt-testing-molecule.groovy` | Molecule testing |
| `jenkins/pxb-pt-testing-molecule-all.groovy` | Full matrix testing |
| `jenkins/pxb-tarball-molecule.groovy` | Tarball testing |
| `jenkins/pxb-80-arm.groovy` | ARM64 builds |

## Key Jenkins Jobs

| Job Name | Purpose |
|----------|---------|
| `pxb80-build` | Build PXB 8.0 packages |
| `pxb-package-testing` | Package validation |
| `pxb-pt-testing-molecule` | Molecule scenarios |
| `pxb-tarball-molecule` | Tarball testing |

## Version Matrix

| Version | Status | ARM64 |
|---------|--------|-------|
| 8.0 | Active | Yes |
| 2.4 | Maintenance | Yes |

## Backup Testing

PXB must validate:
- Full, incremental, compressed, encrypted backups
- Streaming (SSH/S3/remote storage)
- Physical + logical restore flows

## Compatibility Matrix

| Target | Versions |
|--------|----------|
| Percona Server | 5.7, 8.0, 8.4 |
| PXC | 5.7, 8.0 |
| MySQL Community | 5.7, 8.0 |

## Molecule

```bash
# Run Molecule scenarios
cd molecule/pxb && molecule test -s ubuntu-jammy

# PXB-specific helpers
moleculeExecuteActionWithScenario(params)
moleculeParallelPostDestroy()
installMolecule()
```

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Breaking artifact names | PS/PXC builds depend on them | Coordinate downstream |
| Missing PS/PXC compat | Incomplete coverage | Include in test matrix |
| ccache not uploaded | Slower builds | Upload in `post.success` |
| S3 bucket leaks | Costs add up | Delete temp buckets |

## Agent Workflow

1. **Check live config**: `~/bin/jenkins params pxb/pxb80-build`
2. **Compatibility**: Test against PS and PXC releases
3. **ccache**: Upload/download to speed builds
4. **Cleanup**: Delete S3 temp buckets + `deleteDir()` in `post`

## PR Review Checklist

- [ ] `buildDiscarder(logRotator(...))` in options
- [ ] `deleteDir()` in `post.always`
- [ ] PS/PXC compatibility tested
- [ ] ccache handling correct
- [ ] No artifact naming changes

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Artifact path | PS/PXC builds break | ps-team, pxc-team |
| Version support | Compat matrix | QA |
| S3 paths | Release automation | RelEng |

## Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('pxb/jenkins/pxb-80.groovy'))"

# Molecule smoke
cd molecule/pxb && molecule test -s ubuntu-jammy
```

## Jenkins CLI

```bash
~/bin/jenkins job pxb list                    # All jobs
~/bin/jenkins job pxb list | grep pxb80       # PXB 8.0 jobs
~/bin/jenkins params pxb/<job>                # Parameters
~/bin/jenkins build pxb/<job> -p KEY=val      # Build
```

## Related

- `ps/AGENTS.md` - Percona Server (depends on PXB)
- `pxc/AGENTS.md` - XtraDB Cluster (depends on PXB)
- `vars/AGENTS.md` - Shared helpers (molecule*, ccache*)
