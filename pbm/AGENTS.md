# AGENTS.md - PBM Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Backup for MongoDB (PBM) pipelines: agent builds, package testing, functional backup/restore on replica sets and sharded clusters, Molecule scenarios, and PBM-PSMDB integration.

## Key Files

- `pbm-build.groovy` - PBM build pipeline
- `pbm-package-testing.groovy` - Package testing
- `pbm-molecule.groovy` - Molecule scenarios (install, upgrade, functional)

## Product-Specific Patterns

### Backup testing

Ensure coverage for:
- Logical & physical backups
- Point-in-time recovery (PITR)
- Selective collection restore
- Multiple storage backends (S3-compatible, filesystem)

### MongoDB topology support

Pipelines must exercise:
- Standalone nodes
- Replica sets
- Sharded clusters with config servers

### Molecule credentials

```groovy
// PBM-specific molecule credentials
moleculePbmJenkinsCreds()
```

## Agent Workflow

1. **Fetch job definitions:** `~/bin/jenkins job psmdb config pbm-package-testing --yaml` to inspect parameters such as `PBM_BRANCH`, `PSMDB_BRANCH`, `LAYOUT`, `STORAGE`.
2. **Respect topology matrices:** When adding or removing scenarios, update both Molecule configs and Jenkins parameters (`TOPOLOGY`, `STORAGE_BACKEND`).
3. **Use helpers:** Call `moleculeExecuteActionWithScenario`, `runMoleculeCommandParallel*`, `pbmVersion`, and `installMolecule` functions from `vars/`.
4. **Cleanup resources:** Molecule scenarios create EC2 instances and S3 buckets; wrap them with `try/finally` and `moleculeParallelPostDestroy`.
5. **Coordinate with PSMDB:** PBM pipelines share credentials and artifacts with `psmdb` jobs - keep version parameters aligned.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('pbm/pbm-build.groovy'))"`
- **Molecule smoke:** `cd molecule/pbm && molecule test -s aws-sharded`
- **PBM CLI tests:** Run `pbm backup` / `pbm restore` locally in Docker containers for quick sanity checks before editing Jenkins steps.
- **Jenkins dry-run:** Trigger `pbm-functional-aws-rs` or `pbm-functional-aws-sharded` with limited OS list for validation.

## Credentials & Parameters

- **Credentials:** `pbm-staging` or `psmdb-staging` AWS creds, `moleculePbmJenkinsCreds()` for Molecule SSH keys. Always guard with `withCredentials`.
- **Key parameters:** `PBM_BRANCH`, `PSMDB_BRANCH`, `LAYOUT_TYPE`, `STORAGE_BACKEND`, `ENABLE_PITR`.
- **Storage buckets:** Reuse existing staged buckets; never hardcode S3 keys.

## Jenkins CLI

Instance: `psmdb` | URL: `https://psmdb.cd.percona.com`

```bash
# jenkins CLI
~/bin/jenkins job psmdb list | grep pbm             # All PBM jobs
~/bin/jenkins params psmdb/<job>                    # Parameters
~/bin/jenkins build psmdb/<job> -p KEY=val          # Build

# curl (see root AGENTS.md for auth)
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("pbm"))'
```

Job patterns: `pbm-*`, `pbm-functional-*`, `pbm-docker*`

## Related Jobs

- `pbm-build` - Build artifacts
- `pbm-package-testing` - Package validation
- `psmdb-*` pipelines for server compatibility testing
