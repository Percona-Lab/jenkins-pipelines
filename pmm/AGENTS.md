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

# Jenkins

Instance: `pmm` | URL: `https://pmm.cd.percona.com`

## CLI
```bash
~/bin/jenkins job pmm list | grep pmm3              # PMM v3 jobs
~/bin/jenkins job pmm list | grep pmm2              # PMM v2 jobs (legacy)
~/bin/jenkins params pmm/<job>                      # Parameters
~/bin/jenkins build pmm/<job> -p KEY=val            # Build
~/bin/jenkins logs pmm/<job> -f                     # Follow logs
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://pmm.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("pmm3"))'
```

## Job Patterns
`pmm3-ami*`, `pmm3-ovf*`, `pmm3-client*`, `pmm3-*-tests`

## Credentials
`pmm-staging-slave`, `pmm-aws`, `aws-jenkins`. Always use `withCredentials`.

## Job Inventory

**Total Jobs:** 74 | **Groovy Files:** 83 | **Coverage:** 90%

### PMM v2 Jobs (Legacy)

| Job | File | Description |
|-----|------|-------------|
| pmm2-ami | `pmm2-ami.groovy` | Build PMM v2 AMI |
| pmm2-ami-staging-start | `pmm2-ami-staging-start.groovy` | Start AMI staging |
| pmm2-ami-staging-stop | `pmm2-ami-staging-stop.groovy` | Stop AMI staging |
| pmm2-ami-test | `pmm2-ami-test.groovy` | Validate AMI |
| pmm2-ami-upgrade-tests | `pmm2-ami-upgrade-tests.groovy` | AMI upgrade validation |
| pmm2-ovf | `pmm2-ovf.groovy` | Build OVF image |
| pmm2-ovf-staging-start | `pmm2-ovf-staging-start.groovy` | Start OVF staging |
| pmm2-ovf-staging-stop | `pmm2-ovf-staging-stop.groovy` | Stop OVF staging |
| pmm2-client-autobuild | `pmm2-client-autobuild.groovy` | Generic client build |
| pmm2-client-autobuild-amd | `pmm2-client-autobuild-amd.groovy` | AMD64 client |
| pmm2-client-autobuild-arm | `pmm2-client-autobuild-arm.groovy` | ARM64 client |
| pmm2-server-autobuild | `pmm2-server-autobuild.groovy` | Server Docker build |
| pmm2-ui-tests | `pmm2-ui-tests.groovy` | Selenium UI tests |
| pmm2-api-tests | `pmm2-api-tests.groovy` | API endpoint tests |
| pmm2-cli-tests | `pmm2-cli-tests.groovy` | pmm-admin CLI tests |
| pmm2-package-testing | `pmm2-package-testing.groovy` | Package validation |
| pmm2-e2e-master | `pmm2-e2e-master.groovy` | E2E test orchestrator |
| pmm2-release | `pmm2-release.groovy` | Release pipeline |
| pmm2-release-candidate | `pmm2-release-candidate.groovy` | RC build |

### PMM v3 Jobs (Active)

See [v3/AGENTS.md](v3/AGENTS.md) for complete v3 job mappings. Summary:

| Category | Jobs | Key Files |
|----------|------|-----------|
| AMI Builds | 6 | `v3/pmm3-ami*.groovy` |
| OVF Builds | 6 | `v3/pmm3-ovf*.groovy` |
| Client Builds | 3 | `v3/pmm3-client-autobuild*.groovy` |
| Server/Components | 2 | `v3/pmm3-server-autobuild.groovy`, `v3/pmm3-watchtower-autobuild.groovy` |
| Testing (UI/API/CLI) | 8 | `v3/pmm3-ui-tests*.groovy`, `v3/pmm3-api-tests.groovy` |
| Package Testing | 4 | `v3/pmm3-package-testing*.groovy` |
| Upgrade Testing | 4 | `v3/pmm3-upgrade-tests*.groovy` |
| Release | 4 | `v3/pmm3-release*.groovy` |
| Submodules | 3 | `v3/pmm3-submodules*.groovy` |

### Infrastructure Jobs

| Job | File | Description |
|-----|------|-------------|
| aws-staging-start | `aws-staging-start.groovy` | Start AWS staging |
| aws-staging-stop | `aws-staging-stop.groovy` | Stop AWS staging |
| aws-staging-stop-robot | `aws-staging-stop-robot.groovy` | Auto cleanup |
| rpm-build | `infrastructure/rpm-build.groovy` | RPM packaging |
| rpm-build-3 | `infrastructure/rpm-build-3.groovy` | RPM v3 packaging |

### Jobs Without Source Files (Jenkins-Only)

These jobs exist in Jenkins but have no corresponding groovy file:
- `pmm3-ha-eks*` - EKS HA testing (removed from Hetzner branch)
- `openshift-cluster-*` - OpenShift provisioning (removed from Hetzner)
- `spot-price-auto-updater` - Infrastructure admin
- `test-*` - Development/test jobs

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
