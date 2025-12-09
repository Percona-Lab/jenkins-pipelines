# AGENTS.md - PDPXC Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Distribution for Percona XtraDB Cluster (PDPXC) CI/CD pipelines. Includes distribution package testing for Galera-based MySQL clustering, HAProxy load balancing, Percona Replication Manager integration, multi-version setup, upgrade validation, and PXC Operator integration testing.

## Key Files

- `pdpxc-setup.groovy` / `pdpxc-setup-parallel.groovy` – Initial PDPXC setup and installation
- `pdpxc-parallel.groovy` / `pdpxc.groovy` – Distribution testing across platforms
- `pdpxc-upgrade.groovy` / `pdpxc-upgrade-parallel.groovy` – Upgrade path testing (minor/major versions)
- `pdpxc-multi.groovy` / `pdpxc-multi-parallel.groovy` – Multi-version testing orchestration
- `pdpxc-haproxy.groovy` – HAProxy load balancer integration testing
- `pdpxc-percona-replication-manager.groovy` – Percona Replication Manager testing
- `pdpxc-pxco-integration-scheduler.groovy` – PXC Operator integration scheduling

## Product-Specific Patterns

### Distribution Testing Pattern

```groovy
// Standard PDPXC test scenarios
moleculeExecute(
    scenario: 'pdpxc-setup',
    platform: params.PLATFORM,
    version: params.VERSION
)
```

### HAProxy Integration

```groovy
// HAProxy provides load balancing for PXC clusters
// Validated as part of PDPXC distribution
scenario: 'pdpxc-haproxy'
```

### PXC Operator Integration

```groovy
// Operator integration scheduling
// Validates PDPXC packages work with Kubernetes PXC Operator
job: 'pdpxc-pxco-integration-scheduler'
```

### Molecule Credentials

```groovy
// Use PDPXC-specific Molecule credentials
moleculePdpxcJenkinsCreds()
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job pxc config pdpxc-parallel --yaml` to capture parameters like `VERSION`, `PLATFORM`, `SCENARIO`, `TESTING_BRANCH`.
2. **Reuse distribution patterns:** Follow established patterns from `pxc/` pipelines for consistency with XtraDB Cluster testing.
3. **HAProxy component:** When modifying HAProxy jobs, ensure compatibility with PXC cluster configurations.
4. **Operator integration:** PXC Operator integration jobs coordinate with Kubernetes operator testing infrastructure.
5. **Parameter contracts:** PDPXC jobs follow release automation contracts; extend but never rename/remove parameters.

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('pdpxc/pdpxc-parallel.groovy'))"

# Molecule testing (local validation)
cd /path/to/pxc-testing
molecule test -s pdpxc-setup

# HAProxy testing
molecule test -s pdpxc-haproxy

# Jenkins dry-run
~/bin/jenkins build pxc/pdpxc-parallel \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  -p VERSION=pdpxc-8.0 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculePdpxcJenkinsCreds()` – AWS/SSH for Molecule testing
- **Key parameters:**
  - `VERSION` – PDPXC version (e.g., 'pdpxc-8.0', 'pdpxc-8.4')
  - `PLATFORM` – OS selection via Molecule platform helpers
  - `SCENARIO` – Test scenario name
  - `REPO` – Repository selection (testing/release/experimental)
  - `TESTING_BRANCH` – pxc-testing.git branch (usually 'main')

# Jenkins

Instance: `pxc` | URL: `https://pxc.cd.percona.com`

## CLI
```bash
~/bin/jenkins job pxc list | grep pdpxc             # All PDPXC jobs
~/bin/jenkins params pxc/<job>                      # Parameters
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://pxc.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("pdpxc"))'
```

## Job Patterns
`pdpxc-*`, `pdpxc-haproxy`, `pdpxc-upgrade*`

## Credentials
`moleculePdpxcJenkinsCreds()` (AWS/SSH). Always use `withCredentials`.

## Related Jobs

- `pxc-*` – Related Percona XtraDB Cluster jobs
- `cloud/pxc-operator-*` – Kubernetes PXC Operator testing
- HAProxy load balancer integration
- Percona Replication Manager testing
- Distribution testing orchestration

## Code Owners

See `.github/CODEOWNERS` – PDPXC pipelines maintained by:
- Eleonora Zinchenko (15 commits) – Primary contributor
- Mikhail Samoylov (6 commits) – Supporting contributor
- Yash (3 commits) – Operator integration
- Vadim Yalovets (3 commits) – Supporting contributor

Primary contact: `@eleonora-zinchenko`

## Status

Last activity: May 2025 (semi-active)
Recent work: PXC Operator integration, trigger automation
