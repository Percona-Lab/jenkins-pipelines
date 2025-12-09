# AGENTS.md - PCSM Pipelines (Hetzner Branch)

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Cloud Service Manager (PCSM) CI/CD pipelines for Hetzner infrastructure. Includes packaging, Docker image builds (x64 + ARM64), manual testing, and performance validation. PCSM (formerly PLM) manages cloud database deployments. Recently renamed (October 31, 2025).

## Key Files

- `pcsm-packaging.groovy` – Package builds
- `pcsm-manual.groovy` – Manual integration testing
- `pcsm-performance.groovy` – Performance benchmarking
- `pcsm-docker.groovy` / `pcsm-docker-arm.groovy` – Docker images (x64/ARM64) (Hetzner)

## Product-Specific Patterns

### Multi-Architecture Docker Support (Hetzner)

```groovy
// Hetzner branch adds ARM64 Docker support
job: 'pcsm-docker'      // x64 images
job: 'pcsm-docker-arm'  // ARM64 images (Hetzner addition)
```

### Performance Testing

```groovy
job: 'pcsm-performance'
scenarios: ['deployment', 'scaling', 'lifecycle']
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job cloud config pcsm-packaging --yaml`
2. **Recent rename:** Product renamed from PLM to PCSM (Oct 31, 2025)
3. **Multi-arch builds:** Update both x64 and ARM64 Docker variants together
4. **Performance focus:** Performance testing is critical validation
5. **Parameter evolution:** New product; document parameter changes

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('pcsm/pcsm-docker-arm.groovy'))"

# Jenkins dry-run
~/bin/jenkins build cloud/pcsm-docker-arm \
  -p VERSION=1.0.0 \
  --watch
```

## Credentials & Parameters

- **Credentials:** `moleculePbmJenkinsCreds()`
- **Key parameters:** `VERSION`, `BRANCH`, `PLATFORMS`, `PERFORMANCE_PROFILE`

## Jenkins CLI

Instance: `psmdb` | URL: `https://psmdb.cd.percona.com`

```bash
# jenkins CLI
~/bin/jenkins job psmdb list | grep pcsm            # All PCSM jobs
~/bin/jenkins job psmdb list | grep 'hetzner-pcsm'  # Hetzner prefix
~/bin/jenkins params psmdb/<job>                    # Parameters
~/bin/jenkins build psmdb/<job> -p VERSION=1.0.0    # Build

# curl (see root AGENTS.md for auth)
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("pcsm"))'
```

Job patterns: `hetzner-pcsm-docker*`, `hetzner-pcsm-functional*`

## Related Jobs

- Cloud infrastructure pipelines
- Multi-architecture Docker builds

## Code Owners

- Sandra Romanchenko (1 commit) – Primary

## Status

Last activity: October 31, 2025 (brand new)
Hetzner additions: ARM64 Docker support (pcsm-docker-arm)
