# AGENTS.md - PMM Pipelines (Hetzner Branch)

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Monitoring and Management (PMM) CI/CD pipelines for Hetzner infrastructure. Includes server builds, client testing, UI and API validation, upgrade smoke tests, and AWS staging infrastructure. Note: Hetzner branch does not include EKS or OpenShift cluster provisioning (removed for Hetzner-optimized deployment).

## Key Directories

- `v3/` – PMM v3 pipelines (AMI/OVF builds, upgrade testing, no EKS/OpenShift) - See [v3/AGENTS.md](v3/AGENTS.md)
- `openshift/` – Not present in hetzner branch (master-only)
- `infrastructure/` – Images, RPMs, and supporting build jobs

## Key Files

- `pmm2-e2e-master.groovy` – E2E test orchestrator
- `pmm2-package-testing.groovy` – Package testing across distros
- `v3/pmm3-ami.groovy` – AMI builds (see v3/AGENTS.md for full v3 documentation)

## Product-Specific Patterns

### PMM Version Helper

```groovy
def version = pmmVersion('dev-latest')
def stable = pmmVersion('list')
def rc = pmmVersion('rc')
```

### Python Script Integration

```groovy
runPython('pmm_version_parser', '--format json')
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job pmm info <job>` and `config --yaml`
2. **Respect shared helpers:** Use `pmmVersion`, `runPython`, `installDocker` instead of reimplementing
3. **Cluster lifecycle:** No OpenShift/EKS in hetzner; use AWS staging for infrastructure testing
4. **Parameters are contract-bound:** Extend parameters but never rename/remove
5. **Use credentials via `withCredentials`:** Always wrap secrets, include `deleteDir()` in `post` blocks

## Validation & Testing

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('pmm/pmm2-e2e-master.groovy'))"

# Python helpers
python3 -m py_compile resources/pmm/do_remove_droplets.py

# Config diff
~/bin/jenkins job pmm config pmm2-e2e-master --yaml > jobs/pmm2-e2e-master.yaml

# Dry run
# Create test job on pmm.cd.percona.com with safe parameters
```

## Parameters & Credentials

- **Credentials:** `pmm-staging-slave`, `pmm-aws`, `aws-jenkins`
- **Common parameters:** `PMM_BRANCH`, `PMM_VERSION`, `PMM_TAG`, `PMM_UI_REF`, `INFRA_BRANCH`
- **Secrets handling:** Never echo AWS keys, kubeconfigs, or SSH keys

## Jenkins Instance

PMM jobs run on: `pmm.cd.percona.com`

```bash
~/bin/jenkins job pmm list
~/bin/jenkins job pmm params pmm3-ami
```

## Related Jobs

- `pmm2-ui-tests` – UI testing
- `pmm2-upgrade-tests` – Upgrade testing
- No OpenShift/EKS jobs in hetzner branch

## Code Owners

See `.github/CODEOWNERS` – PMM pipelines owned by `@ademidoff`, `@puneet0191`

## Hetzner Branch Notes

### Not in Hetzner:
- No OpenShift cluster provisioning
- No EKS high-availability testing
- No `openshiftCluster.*` helper usage

### Available in Hetzner:
- AWS staging infrastructure
- AMI/OVF deployments
- Standard E2E and package testing
