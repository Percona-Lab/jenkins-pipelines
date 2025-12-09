# AGENTS.md - PMM Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Monitoring and Management (PMM) CI/CD pipelines. Includes server builds, client testing, UI and API validation, upgrade smoke tests, and infrastructure provisioning for EKS/OpenShift clusters.

## Key Directories

- `v3/` – PMM v3 pipelines (HA/EKS scenarios, AMI/OVF builds, upgrade testing) - See [v3/AGENTS.md](v3/AGENTS.md)
- `openshift/` – OpenShift cluster provisioning + PMM deployment
- `infrastructure/` – Images, RPMs, and supporting build jobs

## Key Files

- `pmm2-e2e-master.groovy` – E2E test orchestrator
- `pmm2-package-testing.groovy` – Package testing across distros
- `v3/pmm3-ha-eks.groovy` – High-availability testing on EKS (see v3/AGENTS.md for full v3 documentation)
- `openshift/openshift_cluster_create.groovy` – OpenShift provisioning

## Product-Specific Patterns

### PMM version helper

```groovy
// Get PMM versions dynamically
def version = pmmVersion('dev-latest')  // Development version
def stable = pmmVersion('list')         // List all available
def rc = pmmVersion('rc')               // Release candidate
```

### OpenShift / EKS operations

```groovy
// Use shared library for OpenShift
openshiftCluster.create(params)
openshiftCluster.deployPMM(params)
openshiftCluster.destroy(params)
```

### Python script integration

PMM pipelines use resources from `resources/pmm/` via `runPython()`:

```groovy
runPython('pmm_version_parser', '--format json')
```

## Agent Workflow

1. **Inspect existing jobs:** `~/bin/jenkins job pmm info <job>` and `config --yaml` for the pipeline you are editing (pmm2-e2e-master, pmm3-ha-eks, etc.) to capture current parameters and downstream triggers.
2. **Respect shared helpers:** Always call `pmmVersion`, `runPython`, `openshiftCluster.*`, and `installDocker` instead of re-implementing logic in-line.
3. **Cluster lifecycle:** Any pipeline that provisions infrastructure (`openshift/`, `v3/`) must call the matching `destroy/cleanup` helper inside `post { always { ... } }`, even when the build fails.
4. **Parameters are contract-bound:** Jobs like `pmm2-e2e-master` are triggered by release automation—extend parameters but never rename/remove them.
5. **Use `pmm-staging-slave`/`pmm-aws` credentials via `withCredentials` and keep `deleteDir()` in `post` blocks for HA jobs that run on long-lived agents.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('pmm/pmm2-e2e-master.groovy'))"`
- **Python helpers:** `python3 -m py_compile resources/pmm/do_remove_droplets.py`
- **Config diff:** `~/bin/jenkins job pmm config pmm2-e2e-master --yaml > jobs/pmm2-e2e-master.yaml`
- **Dry run:** Create/trigger a test job on `pmm.cd.percona.com` with safe parameters or point a forked shared library branch at Jenkins for validation.
- **Cluster cleanup:** Confirm the cleanup job (`pmm3-ha-eks-cleanup`, OpenShift destroy steps) succeeds before closing changes.

## Parameters & Credentials

- **Credentials:** `pmm-staging-slave` (AWS), `pmm-aws` (AWS), `aws-jenkins` (SSH). Wrap access with `withCredentials`/`withAWS`.
- **Common parameters:** `PMM_BRANCH`, `PMM_VERSION`, `PMM_TAG`, `PMM_UI_REF`, `INFRA_BRANCH`. Add new parameters rather than modifying these names.
- **Secrets handling:** Never echo AWS keys, kubeconfigs, or SSH keys; store temporary kubeconfigs under `${WORKSPACE}` and delete them in `post`.

## Jenkins Instance

All PMM jobs run on: `pmm.cd.percona.com`

```bash
# List PMM jobs
~/bin/jenkins job pmm list

# Get job parameters
~/bin/jenkins job pmm params pmm3-ha-eks
```

## Related Jobs

- `pmm2-ui-tests` – UI testing (triggered by E2E master)
- `pmm2-upgrade-tests` – Upgrade path testing
- `openshift-cluster-create` – Creates OpenShift clusters
- `pmm3-ha-eks-cleanup` – Cleanup for HA clusters

## Code Owners

See `.github/CODEOWNERS` – PMM pipelines owned by `@ademidoff`, `@puneet0191`.
