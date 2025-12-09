# AGENTS.md - PDPS Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Distribution for Percona Server (PDPS) CI/CD pipelines. Includes distribution package testing for Percona Server MySQL, orchestrator components, Perl DBD-MySQL integration, multi-version setup, upgrade validation, and comprehensive distribution testing across Linux platforms.

## Key Files

- `pdps-setup.groovy` / `pdps-setup-parallel.groovy` – Initial PDPS setup and installation
- `pdps-parallel.groovy` / `pdps.groovy` – Distribution testing across platforms
- `pdps-upgrade.groovy` / `pdps-upgrade-parallel.groovy` – Upgrade path testing (minor/major versions)
- `pdps-multi.groovy` / `pdps-multi-parallel.groovy` – Multi-version testing orchestration
- `pdps-orchestrator.groovy` / `pdps-orchestrator_docker.groovy` – Orchestrator HA component testing
- `pdps-perl-DBD-Mysql.groovy` – Perl DBD-MySQL integration testing

## Product-Specific Patterns

### Distribution Testing Pattern

```groovy
// Standard PDPS test scenarios
moleculeExecute(
    scenario: 'pdps-setup',
    platform: params.PLATFORM,
    version: params.VERSION
)
```

### Orchestrator Testing

```groovy
// Orchestrator is a HA management tool for MySQL
// Tested in both direct and Docker variants
scenario: 'pdps-orchestrator'       // Direct installation
scenario: 'pdps-orchestrator-docker' // Docker-based testing
```

### Molecule Credentials

```groovy
// Use PDPS-specific Molecule credentials
moleculePdpsJenkinsCreds()
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job ps80 config pdps-parallel --yaml` to capture parameters like `VERSION`, `PLATFORM`, `SCENARIO`, `TESTING_BRANCH`.
2. **Reuse distribution patterns:** Follow established patterns from `ps/` pipelines for consistency with Percona Server testing.
3. **Orchestrator component:** When modifying orchestrator jobs, ensure both direct and Docker variants are updated.
4. **Perl DBD integration:** DBD-MySQL testing validates Perl driver compatibility with PDPS versions.
5. **Parameter contracts:** PDPS jobs follow release automation contracts; extend but never rename/remove parameters.

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('pdps/pdps-parallel.groovy'))"

# Molecule testing (local validation)
cd /path/to/ps-testing
molecule test -s pdps-setup

# Orchestrator testing
molecule test -s pdps-orchestrator

# Jenkins dry-run
~/bin/jenkins build ps80/pdps-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  -p VERSION=pdps-8.0 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculePdpsJenkinsCreds()` – AWS/SSH for Molecule testing
- **Key parameters:**
  - `VERSION` – PDPS version (e.g., 'pdps-8.0', 'pdps-8.4')
  - `PLATFORM` – OS selection via Molecule platform helpers
  - `SCENARIO` – Test scenario name
  - `REPO` – Repository selection (testing/release/experimental)
  - `TESTING_BRANCH` – ps-testing.git branch (usually 'main')

## Jenkins CLI

Instance: `ps80` | URL: `https://ps80.cd.percona.com`

```bash
# jenkins CLI
~/bin/jenkins job ps80 list | grep pdps             # All PDPS jobs
~/bin/jenkins params ps80/<job>                     # Parameters

# curl (see root AGENTS.md for auth)
curl -su "USER:TOKEN" "https://ps80.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("pdps"))'
```

Job patterns: `pdps-*`, `pdps-orchestrator*`, `pdps-upgrade*`

## Related Jobs

- `ps-*` – Related Percona Server MySQL jobs
- Orchestrator HA component testing
- Distribution testing orchestration
- Perl DBD-MySQL compatibility validation

## Code Owners

See `.github/CODEOWNERS` – PDPS pipelines maintained by:
- Eleonora Zinchenko (24 commits) – Primary contributor
- Mikhail Samoylov (7 commits) – Supporting contributor
- Puneet Kaushik (4 commits) – Supporting contributor

Primary contact: `@eleonora-zinchenko`

## Status

Last activity: November 2024 (dormant)
Maintenance mode: Stable distribution testing infrastructure with Noble (Ubuntu 24.04) support added
