# AGENTS.md - Percona Release Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: percona-release package - repository configuration and management tool
**Where**: Jenkins `rel` | `https://rel.cd.percona.com` | Jobs: `prel-*`
**Key Note**: Foundational package - changes affect ALL Percona products
**Watch Out**: Test across all distros; impacts all product installations

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `rel` |
| URL | https://rel.cd.percona.com |
| Job Patterns | `prel-*`, `prel-parallel` |
| Groovy Files | ~3 |
| Scope | All Percona products |

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

1. **Inspect existing jobs:** `~/bin/jenkins job rel config prel-parallel -f yaml` to capture parameters.
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

# Jenkins

Instance: `rel` | URL: `https://rel.cd.percona.com`

## CLI
```bash
~/bin/jenkins job rel list | grep prel              # All prel jobs
~/bin/jenkins params rel/<job>                      # Parameters
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://rel.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("prel"))'
```

## Job Patterns
`prel*`, `prel-parallel`, `prel-documentation`

## Credentials
`moleculeDistributionJenkinsCreds()`. Always use `withCredentials`.

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
