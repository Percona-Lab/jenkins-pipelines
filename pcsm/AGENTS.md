# AGENTS.md - PCSM Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Cloud Service Manager (PCSM) CI/CD pipelines. Includes packaging, manual testing, and performance validation. PCSM (formerly PLM - Percona Lifecycle Manager) manages cloud database deployments. Recently renamed (October 31, 2025).

## Key Files

- `pcsm-packaging.groovy` – Package builds for PCSM
- `pcsm-manual.groovy` – Manual integration testing
- `pcsm-performance.groovy` – Performance benchmarking and validation

## Product-Specific Patterns

### Packaging Pattern

```groovy
// Standard package building
packageTypes: ['rpm', 'deb']
distributions: 'Standard Linux distributions'
```

### Performance Testing

```groovy
// PCSM includes dedicated performance testing
job: 'pcsm-performance'
scenarios: ['deployment', 'scaling', 'lifecycle']
```

### Molecule Credentials

```groovy
// Uses PBM Molecule credentials
moleculePbmJenkinsCreds()
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job cloud config pcsm-packaging --yaml` to capture parameters.
2. **Recent rename:** Product was renamed from PLM to PCSM on Oct 31, 2025; watch for pattern establishment.
3. **Performance focus:** PCSM has dedicated performance testing; treat as critical validation.
4. **Manual testing:** Manual job indicates complex integration scenarios.
5. **Parameter contracts:** New product; parameters may evolve; document changes.

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('pcsm/pcsm-packaging.groovy'))"

# Performance testing
# Use pcsm-performance job for benchmarking

# Jenkins dry-run
~/bin/jenkins build cloud/pcsm-packaging \
  -p VERSION=1.0.0 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculePbmJenkinsCreds()` – AWS/SSH for testing
- **Key parameters:**
  - `VERSION` – PCSM version
  - `BRANCH` – Git branch
  - `PLATFORMS` – Target distributions
  - `PERFORMANCE_PROFILE` – Performance test configuration

## Jenkins Instance

PCSM jobs run on: `cloud.cd.percona.com`

```bash
# List jobs
~/bin/jenkins job cloud list | grep pcsm

# Get job parameters
~/bin/jenkins job cloud params pcsm-packaging
```

## Related Jobs

- Cloud infrastructure pipelines
- Database lifecycle management
- Performance monitoring

## Code Owners

See `.github/CODEOWNERS` – PCSM pipelines maintained by:
- Sandra Romanchenko (1 commit) – Primary contributor

Primary contact: `@sandra-romanchenko`

## Status

Last activity: October 31, 2025 (brand new)
Recent work: Renamed from PLM to PCSM
Activity: 1 commit (product just created)
Note: Patterns still emerging; monitor for standardization
