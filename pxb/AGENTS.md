# AGENTS.md - PXB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona XtraBackup (PXB) pipelines: build artifacts, package validation, Molecule/functional backup + restore testing, and compatibility checks with PS and PXC releases.

## Key Files

- `pxb80-build.groovy` - PXB 8.0 build pipeline
- `pxb80-package-testing.groovy` - Package testing
- `pxb-molecule.groovy` - Molecule-based scenario runner

## Product-Specific Patterns

### Backup testing coverage

PXB pipelines must validate:
- Full, incremental, compressed, and encrypted backups
- Streaming backups (SSH/S3/remote storage)
- Physical + logical restore flows

### Compatibility matrix

Always test against:
- Percona Server (PS) releases in maintenance
- Percona XtraDB Cluster (PXC) releases
- Supported MySQL Community Server versions

## Agent Workflow

1. **Inspect job configs:** `~/bin/jenkins job pxb config pxb80-build --yaml` before editing to capture parameters such as `PXB_BRANCH`, `BUILD_TYPE`, and downstream triggers.
2. **Keep compatibility lists in sync:** Update the distro/version matrices in both build and Molecule jobs when adding/removing support.
3. **Reuse helpers:** Pipelines rely on `installMolecule*`, `launchSpotInstance`, `moleculeExecuteActionWithScenario`, and `ccache*` helpers from `vars/`.
4. **Cleanup artifacts:** Upload/download ccache, delete temporary S3 buckets, and remove build directories with `deleteDir()` in `post`.
5. **Coordinate with PS/PXC teams** when changing shared parameters to avoid breaking their chained jobs.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('pxb/pxb80-build.groovy'))"`
- **Molecule smoke:** Locally run representative scenarios: `molecule test -s ubuntu-jammy` inside the relevant Molecule directory (mirrors Jenkins steps).
- **Package checks:** Use `checkRPM.groovy` helper or `rpm -qp --requires` to ensure dependency updates are reflected.
- **Jenkins dry-run:** Trigger `pxb80-package-testing` on staging with `DRY_RUN=true` parameters before merging large changes.

## Credentials & Parameters

- **Credentials:** `pxb-staging` (AWS), plus SSH keys used for PS/PXC hosts. Always use `withCredentials([aws(...)])` wrappers.
- **Key parameters:** `PXB_BRANCH`, `MYSQL_VERSION`, `TARGET_OS`, `PXB_BUILD_TYPE`. Treat them as API contracts for downstream jobs.
- **Artifacts:** Store build artifacts in existing S3 paths; avoid introducing new buckets without coordination.

# Jenkins

Instance: `pxb` | URL: `https://pxb.cd.percona.com`

## CLI
```bash
~/bin/jenkins job pxb list                          # All jobs
~/bin/jenkins job pxb list | grep 'pxb80'           # PXB 8.0 jobs
~/bin/jenkins params pxb/<job>                      # Parameters
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://pxb.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name'
```

## Job Patterns
`pxb80-*`, `pxb24-*`, `pxb-*-testing`

## Credentials
`pxb-staging` (AWS). Always use `withCredentials`.

## Related Jobs

- `pxb80-build` - Build artifacts
- `pxb80-package-testing` - Package validation
- PS/PXC integration jobs that consume PXB builds
