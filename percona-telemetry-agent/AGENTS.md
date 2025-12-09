# AGENTS.md - Percona Telemetry Agent Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Telemetry Agent CI/CD pipelines. Includes Linux package builds (RPM/DEB) for the telemetry collection agent, multi-distribution testing, and package validation. The telemetry agent collects usage metrics and diagnostic data for Percona products.

## Key Files

- `percona-telemetry-agent.groovy` – Main package build and release pipeline
- `test_telemetry_agent.groovy` – Package installation and validation testing

## Product-Specific Patterns

### Package Build Pattern

```groovy
// Builds RPM and DEB packages
packageTypes: ['rpm', 'deb']
architectures: ['x86_64', 'aarch64']
```

### Distribution Support

Recent additions (August 2025):
- Debian 13 (Trixie)
- Ubuntu 24.04 (Noble)
- Oracle Linux 9
- Rocky Linux 9

### Testing Pattern

```groovy
// Simple installation and smoke test
test: 'package_install'
validation: 'agent_collect_metrics'
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job pmm config percona-telemetry-agent --yaml` to capture parameters like `VERSION`, `BRANCH`, `PLATFORMS`.
2. **Package building:** Agent is packaged for multiple distributions simultaneously.
3. **Testing validation:** Test job validates package installation and basic metric collection.
4. **Release workflow:** Build → Test → Release to repositories.
5. **Parameter contracts:** Simple parameter set; extend but never rename/remove.

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('percona-telemetry-agent/test_telemetry_agent.groovy'))"

# Local package validation
# Install package on target distribution
# Verify agent starts and collects metrics

# Jenkins dry-run
~/bin/jenkins build pmm/test_telemetry_agent \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  --watch
```

## Credentials & Parameters

- **Credentials:** Standard build credentials for package repositories
- **Key parameters:**
  - `VERSION` – Agent version (e.g., '1.0.0')
  - `BRANCH` – Git branch to build from
  - `PLATFORMS` – Target distributions list
  - `REPO` – Repository target (testing/release)

## Jenkins CLI

Instance: `pmm` | URL: `https://pmm.cd.percona.com`

```bash
# jenkins CLI
~/bin/jenkins job pmm list | grep telemetry         # Telemetry jobs
~/bin/jenkins params pmm/<job>                      # Parameters

# curl (see root AGENTS.md for auth)
curl -su "USER:TOKEN" "https://pmm.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("telemetry"))'
```

Job patterns: `*telemetry*`, `test_telemetry_agent`

## Related Jobs

- `pmm-*` – PMM product jobs (telemetry integration)
- Package repository management
- Release automation

## Code Owners

See `.github/CODEOWNERS` – Telemetry Agent pipelines maintained by:
- Surabhi Bhat (10 commits) – Primary maintainer
- Michael Okoko (2 commits) – Supporting contributor
- EvgeniyPatlan (1 commit) – Infrastructure support

Primary contact: `@surabhi-bhat`

## Status

Last activity: August 2025 (active)
Recent work: Debian 13 (Trixie) support addition
Activity: 3 commits in last 6 months
