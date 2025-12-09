# AGENTS.md - PDPXC Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Distribution for Percona XtraDB Cluster (PDPXC) CI/CD pipelines. Includes distribution package testing for Galera-based MySQL clustering, HAProxy load balancing, Percona Replication Manager integration, PXC Operator integration, multi-version setup, and upgrade validation.

## Key Files

- `pdpxc-setup.groovy` / `pdpxc-setup-parallel.groovy` – PDPXC setup
- `pdpxc-parallel.groovy` / `pdpxc.groovy` – Distribution testing
- `pdpxc-upgrade.groovy` / `pdpxc-upgrade-parallel.groovy` – Upgrade testing
- `pdpxc-multi.groovy` / `pdpxc-multi-parallel.groovy` – Multi-version testing
- `pdpxc-haproxy.groovy` – HAProxy integration
- `percona-replication-manager.groovy` – Replication Manager (Hetzner)
- `pdpxc-pxco-integration-scheduler.groovy` – PXC Operator integration (Hetzner)

## Product-Specific Patterns

### Distribution Testing

```groovy
moleculeExecute(
    scenario: 'pdpxc-setup',
    platform: params.PLATFORM,
    version: params.VERSION
)
```

### PXC Operator Integration (Hetzner)

```groovy
// Validates PDPXC packages work with Kubernetes PXC Operator
job: 'pdpxc-pxco-integration-scheduler'
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job pxc config pdpxc-parallel --yaml`
2. **Reuse distribution patterns:** Follow `pxc/` pipeline patterns
3. **HAProxy component:** Ensure PXC cluster compatibility
4. **Operator integration:** Coordinate with K8s operator testing
5. **Parameter contracts:** Extend but never rename/remove

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('pdpxc/pdpxc-parallel.groovy'))"

# Molecule testing
cd /path/to/pxc-testing
molecule test -s pdpxc-setup
```

## Credentials & Parameters

- **Credentials:** `moleculePdpxcJenkinsCreds()`
- **Key parameters:** `VERSION`, `PLATFORM`, `SCENARIO`, `REPO`, `TESTING_BRANCH`

## Jenkins Instance

PDPXC jobs run on: `pxc.cd.percona.com`

```bash
~/bin/jenkins job pxc list | grep pdpxc
```

## Related Jobs

- `pxc-*` – Percona XtraDB Cluster jobs
- `cloud/pxc-operator-*` – Kubernetes PXC Operator
- HAProxy load balancing
- Replication Manager testing

## Code Owners

- Eleonora Zinchenko (15 commits) – Primary
- Mikhail Samoylov (6 commits) – Supporting
- Yash (3 commits) – Operator integration

## Status

Last activity: May 2025 (semi-active)
Hetzner additions: Replication Manager, Operator integration scheduler
