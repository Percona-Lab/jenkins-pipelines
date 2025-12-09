# AGENTS.md - Percona Release Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Release tool CI/CD pipelines. Includes percona-release package testing, parallel distribution validation, and documentation generation. The percona-release package provides repository configuration and management for all Percona products.

## Key Files

- `prel.groovy` – Main percona-release package testing
- `prel-parallel.groovy` – Parallel distribution testing
- `prel-documentation.groovy` – Documentation generation and publishing

## Product-Specific Patterns

### Distribution Testing

```groovy
// Tests percona-release package across distributions
moleculeExecute(
    scenario: 'prel',
    platform: params.PLATFORM
)
```

### Repository Configuration Validation

```groovy
// Validates repository setup for all Percona products
// Tests: PS, PSMDB, PXC, PXB, PMM, Tools repositories
products: ['ps', 'psmdb', 'pxc', 'pxb', 'pmm', 'tools']
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job rel config prel-parallel --yaml` to capture parameters.
2. **Testing scope:** Percona-release is foundational; changes affect all products.
3. **Distribution coverage:** Test across all supported Linux distributions.
4. **Documentation:** Ensure docs are updated for repository changes.
5. **Parameter contracts:** Stable interface; avoid breaking changes.

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('prel/prel-parallel.groovy'))"

# Local validation
# Install percona-release package
# Verify repository configuration

# Jenkins dry-run
~/bin/jenkins build rel/prel-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculeDistributionJenkinsCreds()` – Standard Molecule credentials
- **Key parameters:**
  - `VERSION` – percona-release version
  - `PLATFORM` – Distribution to test
  - `REPO` – Repository source

## Jenkins Instance

Percona Release jobs run on: `rel.cd.percona.com`

```bash
# List jobs
~/bin/jenkins job rel list

# Get job parameters
~/bin/jenkins job rel params prel-parallel
```

## Related Jobs

- All product pipelines depend on percona-release for repository setup
- Documentation publishing
- Repository management

## Code Owners

See `.github/CODEOWNERS` – Percona Release pipelines maintained by:
- Mikhail Samoylov (4 commits) – Primary contributor
- Anastasia Alexadrova (1 commit) – Original contributor

Primary contact: `@mikhail-samoylov`

## Status

Last activity: August 2021 (inactive - 3+ years dormant)
Maintenance mode: Stable infrastructure, minimal changes
Note: Debian 11 support added in last commit
