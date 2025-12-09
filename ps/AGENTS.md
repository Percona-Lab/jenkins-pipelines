# AGENTS.md - PS Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Server for MySQL (PS) pipelines: server builds, package testing, Molecule-based distro coverage, MTR suites, upgrade checks, and integration with PXB/PXC jobs.

## Key Files

- `ps80-build.groovy` - PS 8.0 build pipeline
- `ps80-package-testing.groovy` - Package testing
- `ps-molecule.groovy` - Molecule-based testing entrypoint

## Product-Specific Patterns

### MySQL version matrix

Active branches:
- PS 5.7 (legacy maintenance)
- PS 8.0 (LTS)
- PS 8.4 (innovation/preview)

### Build configurations

```groovy
// Build variants
sh "./build.sh --type=release --with-rocksdb"
sh "./build.sh --type=debug   --with-asan"
```

Keep debug/release toggles, plugin lists, and sanitizer flags aligned with downstream performance tests.

## Agent Workflow

1. **Inspect job definitions:** `~/bin/jenkins job ps80 config ps80-package-testing --yaml` to capture parameters (`PS_BRANCH`, `MYSQL_VERSION`, `MTR_SUITE`, `TARGET_OS`).
2. **Reuse shared helpers:** Use `installMolecule*`, `moleculeParallelTest`, `moleculeParallelPostDestroy`, and `installDocker` from `vars/` - don't shell out manually.
3. **Matrix updates:** When adjusting OS or architecture coverage, apply the same change to Molecule, package-testing, and release pipelines to avoid drift.
4. **Integration hooks:** Coordinate with PXB/PXC teams before changing artifact names or repository URLs - they consume PS builds downstream.
5. **Long-running jobs:** Always wrap with `timeout` and `ansiColor`, and add `deleteDir()` for agents with cached toolchains.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('ps/jenkins/ps80-build.groovy'))"`
- **Molecule smoke:** `cd molecule/ps && molecule test -s ubuntu-jammy`
- **MTR subset:** Run representative suites locally (`./mtr --suite=ps --parallel=4`) before expanding coverage in Jenkins.
- **Jenkins dry-run:** Trigger `ps80-package-testing` with a narrowed distro list (single OS) when testing new steps.

## Credentials & Parameters

- **Credentials:** `ps-staging` (AWS), `aws-jenkins` (SSH). Always wrap with `withCredentials`.
- **Key parameters:** `PS_BRANCH`, `MYSQL_VERSION`, `MTR_SUITE`, `BUILD_TYPE`, `ENABLE_ASAN`. Treat them as contracts for release automation.
- **Repositories:** Package publishing paths (apt/yum) are shared with release engineering; verify changes with releng before editing.

# Jenkins

Instance: `ps80` | URL: `https://ps80.cd.percona.com`

## CLI
```bash
~/bin/jenkins job ps80 list                         # All jobs
~/bin/jenkins job ps80 list | grep 'ps8'            # PS 8.x jobs
~/bin/jenkins params ps80/<job>                     # Parameters
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://ps80.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name'
```

## Job Patterns
`ps80-*`, `ps8*-build`, `ps-*-testing`

## Credentials
`ps-staging` (AWS), `aws-jenkins` (SSH). Always use `withCredentials`.

## Related Jobs

- `ps80-build` - Build artifacts
- `ps80-package-testing` - Package validation
- PXB integration tests (consume PS builds)
