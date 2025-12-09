# AGENTS.md - PDPS Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Distribution for Percona Server (PDPS) CI/CD pipelines. Includes distribution package testing, orchestrator components, Perl DBD-MySQL integration, multi-version setup, and upgrade validation across Linux platforms.

## Key Files

- `pdps-setup.groovy` / `pdps-setup-parallel.groovy` – PDPS setup
- `pdps-parallel.groovy` / `pdps.groovy` – Distribution testing
- `pdps-upgrade.groovy` / `pdps-upgrade-parallel.groovy` – Upgrade testing
- `pdps-multi.groovy` / `pdps-multi-parallel.groovy` – Multi-version testing
- `pdps-orchestrator.groovy` / `pdps-orchestrator_docker.groovy` – Orchestrator HA component
- `perl-DBD-Mysql.groovy` – Perl DBD-MySQL integration (Hetzner)

## Product-Specific Patterns

### Distribution Testing

```groovy
moleculeExecute(
    scenario: 'pdps-setup',
    platform: params.PLATFORM,
    version: params.VERSION
)
```

### Perl DBD-MySQL Integration (Hetzner)

New in hetzner branch: Perl driver compatibility testing.

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job ps80 config pdps-parallel --yaml`
2. **Reuse distribution patterns:** Follow `ps/` pipeline patterns
3. **Orchestrator component:** Update both direct and Docker variants together
4. **Perl DBD integration:** Validate Perl driver compatibility
5. **Parameter contracts:** Extend but never rename/remove

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('pdps/pdps-parallel.groovy'))"

# Molecule testing
cd /path/to/ps-testing
molecule test -s pdps-setup
```

## Credentials & Parameters

- **Credentials:** `moleculePdpsJenkinsCreds()`
- **Key parameters:** `VERSION`, `PLATFORM`, `SCENARIO`, `REPO`, `TESTING_BRANCH`

## Jenkins Instance

PDPS jobs run on: `ps80.cd.percona.com`

```bash
~/bin/jenkins job ps80 list | grep pdps
```

## Related Jobs

- `ps-*` – Percona Server MySQL jobs
- Orchestrator HA testing
- Perl DBD-MySQL validation

## Code Owners

- Eleonora Zinchenko (24 commits) – Primary
- Mikhail Samoylov (7 commits) – Supporting

## Status

Last activity: November 2024 (dormant)
