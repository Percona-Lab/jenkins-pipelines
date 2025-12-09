# AGENTS.md - PXC Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona XtraDB Cluster (PXC) CI/CD pipelines: Galera cluster builds, package testing across distros/architectures, Molecule multi-node tests, and upgrade/compatibility validation.

## Key Files

- `pxc80-build.groovy` - PXC 8.0 build pipeline
- `pxc80-package-testing.groovy` - Package validation
- `pxc-molecule.groovy` - Molecule-based multi-node testing

## Product-Specific Patterns

### Molecule testing

```groovy
// Execute molecule scenarios
moleculeExecuteActionWithScenario(params)
moleculeParallelPostDestroy()
```

### Distribution matrix

PXC testing spans:
- RHEL/Oracle Linux 8 & 9
- Debian 11/12
- Ubuntu 20.04/22.04/24.04
- ARM64 jobs for selected platforms

## Agent Workflow

1. **Understand the scenario:** `~/bin/jenkins job pxc info pxc80-package-testing` (or the job you touch) to capture parameters like `PXC_VERSION`, `PXCLUSTER_SIZE`, `OS_MATRIX`.
2. **Keep Galera requirements:** Ensure `wsrep` + SST settings remain consistent; reuse shared snippets from `vars/galera*` helpers when possible.
3. **Molecule hygiene:** Always pair `moleculeExecuteActionWithScenario` with `moleculeParallelPostDestroy` in `post` blocks; do not leave clusters running.
4. **Upgrade jobs:** When editing upgrade flows (`57to80`, etc.), update both the Groovy pipeline and the YAML job definitions to keep parameter defaults aligned.
5. **Performance tuning:** Keep `innodb_buffer_pool_size`, `wsrep_provider_options`, and `pxc_strict_mode` overrides in sync between build/test jobs.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('pxc/pxc80-package-testing.groovy'))"`
- **Molecule smoke (local):** `cd percona-qa/molecule/pxc && molecule test -s ubuntu-jammy`
- **Galera functional test:** Use `pxc-site-check.groovy` or `qa-*` jobs for verifying replication/IST/SST flows.
- **Jenkins dry-run:** Trigger `pxc80-build` in staging with `DRY_RUN=true` plus a limited distro list before pushing major matrix updates.

## Credentials & Parameters

- **Credentials:** `pxc-staging` (AWS), `aws-jenkins` (SSH). Always wrap with `withCredentials`.
- **Key parameters:** `PXC_BRANCH`, `GALERA_REV`, `MTR_SUITE`, `MATRIX`. Do not rename or remove; extend via additional parameters.
- **Artifacts:** Builds are consumed by PS/PXB jobs - keep artifact naming (`percona-xtradb-cluster-<ver>-<os>.tar.gz`) stable.

# Jenkins

Instance: `pxc` | URL: `https://pxc.cd.percona.com`

## CLI
```bash
~/bin/jenkins job pxc list                          # All jobs
~/bin/jenkins job pxc list | grep 'pxc80'           # PXC 8.0 jobs
~/bin/jenkins params pxc/<job>                      # Parameters
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://pxc.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name'
```

## Job Patterns
`pxc80-*`, `pxc57-*`, `pxc-*-testing`

## Credentials
`pxc-staging` (AWS), `aws-jenkins` (SSH). Always use `withCredentials`.

## Related Jobs

- `pxc80-build` - Build artifacts
- `pxc80-package-testing` - Package validation
- Molecule scenarios in `percona-qa` repository (invoked via shared library)
