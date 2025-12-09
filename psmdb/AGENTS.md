# AGENTS.md - PSMDB Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Percona Server for MongoDB (PSMDB) pipelines: server builds, package testing, replica set & sharding validation, encryption/FIPS coverage, backup/restore flows, and PBM integration.

## Key Files

- `psmdb-build.groovy` - PSMDB build pipeline
- `psmdb-package-testing.groovy` - Package testing
- `psmdb-molecule.groovy` - Molecule-based scenarios

## Product-Specific Patterns

### MongoDB version matrix

Actively maintained PSMDB versions:
- 6.0
- 7.0
- 8.0

### Replication/sharding testing

Molecule scenarios validate:
- Replica sets (with/without encryption)
- Sharded clusters w/ config servers
- PITR & PBM restore workflows

## Agent Workflow

1. **Pull live config:** `~/bin/jenkins job psmdb config psmdb-build --yaml` before editing to capture parameters such as `PSMDB_BRANCH`, `PSMDB_VERSION`, `PBM_BRANCH`.
2. **Share logic:** Use helpers like `moleculeExecuteActionWithScenario`, `moleculeParallelPostDestroy`, `pbmVersion`, and `installMolecule*` from `vars/`.
3. **PBM integration:** When pipelines interact with PBM, keep credentials in sync with `pbm/AGENTS.md` and ensure `moleculePbmJenkinsCreds()` is applied.
4. **Encryption/FIPS:** Jobs toggling FIPS or KMIP must propagate flags to both build and test stages; keep parameter names consistent (`ENABLE_FIPS`, `KMS_CONFIG`).
5. **Cleanup clusters:** Always destroy Molecule environments and delete any `kubeconfig`/`aws` temp files in `post`.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('psmdb/psmdb-build.groovy'))"`
- **Python helpers:** `python3 -m py_compile resources/pmm/do_remove_droplets.py` (shared cleanup script)
- **Molecule smoke:** `cd molecule/psmdb && molecule test -s ubuntu-jammy`
- **PBM end-to-end:** Trigger `pbm-pkg-upgrade` or `pbm-functional-*` jobs after major backup-related updates.

## Credentials & Parameters

- **Credentials:** `psmdb-staging` (AWS), `pbm-staging` (when using PBM), `aws-jenkins` (SSH). Wrap with `withCredentials`.
- **Key parameters:** `PSMDB_BRANCH`, `PSMDB_TAG`, `PBM_BRANCH`, `LAYOUT_TYPE`, `ENCRYPTION`, `SCENARIO`.
- **Artifacts:** Tarball & package naming (`percona-server-mongodb-<ver>`) is consumed by PBM - do not change without coordination.

## Jenkins Instance

PSMDB jobs run on: `psmdb.cd.percona.com`

```bash
~/bin/jenkins job psmdb list
```

## Related Jobs

- `psmdb-build` - Build artifacts
- `psmdb-package-testing` - Package validation
- PBM (Percona Backup for MongoDB) integration tests
