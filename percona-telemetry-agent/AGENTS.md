# AGENTS.md - Percona Telemetry Agent Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Telemetry Agent CI/CD pipelines. Includes Linux package builds (RPM/DEB) for the telemetry collection agent, multi-distribution testing, and package validation. The telemetry agent collects usage metrics and diagnostic data for Percona products.

## Key Files

- `percona-telemetry-agent.groovy` – Main package build and release
- `test_telemetry_agent.groovy` – Package installation and validation

## Product-Specific Patterns

### Package Build Pattern

```groovy
packageTypes: ['rpm', 'deb']
architectures: ['x86_64', 'aarch64']
```

### Distribution Support

Recent: Debian 13 (Trixie), Ubuntu 24.04, Oracle Linux 9, Rocky Linux 9

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job pmm config percona-telemetry-agent --yaml`
2. **Package building:** Multi-distribution simultaneous builds
3. **Testing validation:** Package installation and metric collection
4. **Release workflow:** Build → Test → Release
5. **Parameter contracts:** Simple parameter set; extend but never rename

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('percona-telemetry-agent/test_telemetry_agent.groovy'))"

# Jenkins dry-run
~/bin/jenkins build pmm/test_telemetry_agent \
  -p PLATFORM=generic-oracle-linux-9-x64 \
  --watch
```

## Credentials & Parameters

- **Credentials:** Standard build credentials
- **Key parameters:** `VERSION`, `BRANCH`, `PLATFORMS`, `REPO`

## Jenkins Instance

Telemetry Agent jobs run on: `pmm.cd.percona.com`

```bash
~/bin/jenkins job pmm list | grep telemetry
```

## Related Jobs

- `pmm-*` – PMM product integration

## Code Owners

- Surabhi Bhat (10 commits) – Primary
- Michael Okoko (2 commits) – Supporting

## Status

Last activity: August 2025 (active)
