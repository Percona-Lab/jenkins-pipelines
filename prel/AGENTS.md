# AGENTS.md - Percona Release Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Release tool CI/CD pipelines. Includes percona-release package testing, parallel distribution validation, and documentation generation. The percona-release package provides repository configuration and management for all Percona products.

## Key Files

- `prel.groovy` – Main percona-release package testing
- `prel-parallel.groovy` – Parallel distribution testing
- `prel-documentation.groovy` – Documentation generation

## Product-Specific Patterns

### Distribution Testing

```groovy
moleculeExecute(
    scenario: 'prel',
    platform: params.PLATFORM
)
```

### Repository Validation

Tests repository setup for all Percona products: PS, PSMDB, PXC, PXB, PMM, Tools.

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job rel config prel-parallel --yaml`
2. **Testing scope:** Foundational; affects all products
3. **Distribution coverage:** Test across all supported distros
4. **Documentation:** Update docs for repository changes
5. **Parameter contracts:** Stable interface; avoid breaking changes

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('prel/prel-parallel.groovy'))"

# Jenkins dry-run
~/bin/jenkins build rel/prel-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculeDistributionJenkinsCreds()`
- **Key parameters:** `VERSION`, `PLATFORM`, `REPO`

## Jenkins Instance

Percona Release jobs run on: `rel.cd.percona.com`

```bash
~/bin/jenkins job rel list
```

## Related Jobs

All product pipelines depend on percona-release.

## Code Owners

- Mikhail Samoylov (4 commits) – Primary

## Status

Last activity: August 2021 (inactive - 3+ years)
