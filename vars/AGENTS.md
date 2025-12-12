# AGENTS.md - Shared Library (vars/)

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR (read this first)

- `vars/*.groovy` defines **107 global pipeline steps** (shared library “vars steps”).
- **Most pipelines load this library explicitly** via `library ... retriever: modernSCM(...)` (usually `lib@master`, sometimes a pinned branch); a small legacy set uses `@Library('jenkins-pipelines') _`.
- High fan-in steps are **contracts**: keep them backward compatible (`pushArtifactFolder`, `popArtifactFolder`, `upload{DEB,RPM}fromAWS`, `sync2ProdAutoBuild`, `slackNotify`).
- Several helpers run on `node('master')`, use `sudo`, and/or SSH to `repo.ci.percona.com` → treat as privileged and keep workspace cleanup (`deleteDir()`).
- **Ask first** before adding new global helpers in `vars/` (repo-wide blast radius).

## Quick Reference

| Key | Value |
|-----|-------|
| Helper definitions | `vars/*.groovy` (107 files) |
| Helpers referenced in repo scripts | 86/107 (as of 2025-12) |
| Pipeline scripts calling at least one helper | 226 `.groovy` files (as of 2025-12) |
| Preferred load pattern | `library ... identifier: 'lib@<branch>' ... modernSCM(...)` |
| Legacy load pattern | `@Library('jenkins-pipelines') _` (mainly `pmm/openshift`) |
| Artifact stash bucket | `s3://percona-jenkins-artifactory/` (via `AWS_STASH`) |
| Upload/sign host | `repo.ci.percona.com` (SSH credential `repo.ci.percona.com`) |
| Common stashes | `uploadPath`, `rpms`, `debs` |

## Library Loading (how steps get into a pipeline)

Preferred explicit load (most jobs):
```groovy
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])
```

Legacy annotation (only a few jobs; verify Jenkins config supports it):
```groovy
@Library('jenkins-pipelines') _
```

Quick checks:
```bash
rg -n "retriever: modernSCM\\(" --glob '*.groovy'
rg -n "@Library\\('jenkins-pipelines'\\)" --glob '*.groovy'
```

## Dynamics Snapshot (repo scan + git)

Top helpers by call occurrences in pipelines (as of 2025-12):
- `pushArtifactFolder` (921), `popArtifactFolder` (824)
- `uploadDEBfromAWS` (403), `uploadRPMfromAWS` (256)
- `slackNotify` (312)
- `moleculeExecuteActionWithScenario` (120)
- `sync2ProdAutoBuild` (80), `signRPM` (70), `signDEB` (66)

Highest-churn helpers (commits touching the file in the last 12 months):
- `vars/moleculeParallelTest.groovy` (AMI map churn)
- `vars/pmmVersion.groovy` (release/version map churn)
- `vars/openshiftCluster.groovy` (platform/installer churn)
- `vars/*OperatingSystems*.groovy` (OS matrix churn)

Refresh locally:
```bash
git log --since='12 months ago' --name-only --pretty=format: -- vars | sed '/^$/d' | sort | uniq -c | sort -nr | head
```

## “What file do I edit?” (fast index)

**Stash artifacts to/from S3** (cross-node / cross-job handoff):
- `vars/pushArtifactFolder.groovy`
- `vars/popArtifactFolder.groovy`

**Upload packages to `repo.ci.percona.com`** (expects stashes):
- RPMs: `vars/uploadRPM.groovy` (requires `stash 'rpms'` + `stash 'uploadPath'`)
- DEBs: `vars/uploadDEB.groovy` (requires `stash 'debs'` + `stash 'uploadPath'`)

**Upload packages from S3 stash** (runs on `node('master')` and SSHes to repo host):
- RPMs: `vars/uploadRPMfromAWS.groovy` (S3 + `uploadPath`)
- DEBs: `vars/uploadDEBfromAWS.groovy` (S3 + `uploadPath`)

**Sign repo artifacts** (remote `signpackage`):
- RPMs: `vars/signRPM.groovy` (requires `SIGN_PASSWORD` + `uploadPath`)
- DEBs: `vars/signDEB.groovy` (requires `SIGN_PASSWORD` + `uploadPath`)

**Promote/sync to prod repos** (remote repo mutation + signing):
- `vars/sync2ProdAutoBuild.groovy`
- `vars/sync2PrivateProdAutoBuild.groovy`

**Molecule setup + execution**:
- Install: `vars/installMolecule*.groovy`
- Run single scenario: `vars/moleculeExecuteActionWithScenario.groovy`
- Parallel OS matrix: `vars/moleculeParallelTest.groovy`, `vars/moleculeParallelPostDestroy.groovy`

**OpenShift helpers** (large, shared across products):
- `vars/openshiftCluster.groovy`, `vars/openshiftTools.groovy`, `vars/openshiftS3.groovy`, `vars/openshiftDiscovery.groovy`, `vars/openshiftSSL.groovy`

**Python helpers bundled in the library**:
- Runner: `vars/runPython.groovy` (loads `resources/pmm/<name>.py`)
- Scripts: `resources/pmm/*.py`

**Notifications**:
- `vars/slackNotify.groovy`

## Contracts & Side Effects (treat as invariants)

**S3 artifact stash**
- `pushArtifactFolder(FOLDER_NAME, AWS_STASH_PATH)` and `popArtifactFolder(FOLDER_NAME, AWS_STASH_PATH)` always use bucket `percona-jenkins-artifactory` and AWS credential `AWS_STASH`.

**Repo upload flow**
- Upload helpers read the remote destination from `uploadPath` (typically created + stashed earlier in the pipeline).
- `upload*fromAWS` runs on `node('master')`, calls `popArtifactFolder(...)`, then SSH/SCPs packages to `repo.ci.percona.com`.
- Some helpers modify `/etc/hosts` to force-resolve `repo.ci.percona.com` to a specific IP; keep that behavior stable unless you validate it end-to-end on Jenkins.

**Signing + sync**
- `sync2ProdAutoBuild.groovy` runs remote commands with `set -o xtrace` while interpolating signing secrets; avoid adding extra debug output around secret-bearing commands.

## How to Change Helpers Safely

- **Search call sites first** (blast radius is repo-wide):
  - `rg -n "\\b<helperName>\\(" --glob '*.groovy' --glob '!vars/**'`
- **Stay backward compatible**: only add optional params; avoid renames/removals.
- **Avoid leaking secrets**: don’t echo credentials; prefer env vars + Jenkins masking; be especially careful in helpers that already enable `xtrace`.
- **Keep workspaces clean**: many helpers call `deleteDir()` internally; pipelines should still use `post { always { deleteDir() } }` on long-lived agents.

## Validation (local)

```bash
# Groovy syntax check (helper)
groovy -e "new GroovyShell().parse(new File('vars/<helper>.groovy'))"

# Find callers
rg -l '\\b<helper>\\(' --glob '*.groovy' --glob '!vars/**'

# Python resource sanity (if you touched resources/)
python3 -m py_compile resources/pmm/<script>.py
```

## Molecule Patterns

| Cred Helper | AWS ID | Products |
|-------------|--------|----------|
| moleculeDistributionJenkinsCreds | 4462f2e5-* | PPG, PREL |
| moleculePbmJenkinsCreds | 4462f2e5-* + GCP | PBM, PDMDB |
| moleculePdpsJenkinsCreds | 5d78d9c7-* | PDPS |
| moleculePdpxcJenkinsCreds | 7e252458-* | PDPXC |
| moleculepxbJenkinsCreds | c42456e5-* | PXB |
| moleculepxcJenkinsCreds | c42456e5-* | PXC, ProxySQL |

**Shared**: `MOLECULE_AWS_PRIVATE_KEY` (all products)
**Variants**: Base, PDPS, PPG (`~/virtenv`), PXB (skip logic)
**Files**: 22 molecule*.groovy (execute, parallel, cleanup, creds)

## Platform Matrix Helpers

| Function | OS Coverage | Products |
|----------|-------------|----------|
| pdpsOperatingSystems | Oracle 8/9, RHEL 8/9/10, Debian 11/12, Ubuntu Focal/Jammy/Noble | PDPS |
| pdpxcOperatingSystems | Same as PDPS | PDPXC |
| pdmdbOperatingSystems | Version-based (4.0-8.0), ARM, AL2023 | PDMDB, PSMDB, PBM |
| ps80telemOperatingSystems | Full matrix + ARM | PS 8.0 Telemetry |
| pcsmOperatingSystems | Debian 11/12, RHEL 8/9, Ubuntu Jammy/Noble + ARM, AL2023 | PCSM |
| ps80ProOperatingSystems | Oracle 9, RHEL 10, Debian 12, Ubuntu Jammy/Noble + ARM, AL2023 | PS 8.0 Pro |

**AMIs**: 18 AMIs in moleculeParallelTest.groovy (AL2023, Debian, RHEL, Rocky, Ubuntu)

## OpenShift Helpers

| Function | Purpose | Products |
|----------|---------|----------|
| openshiftCluster | Full lifecycle (create, destroy, list, PMM deploy) | PMM |
| openshiftSSL | Let's Encrypt/cert-manager, ACM | PMM |
| openshiftS3 | S3 state management | PMM, openshiftCluster |
| openshiftDiscovery | Cluster discovery (S3 + Resource Groups API) | PMM |
| openshiftTools | CLI install (oc, openshift-install, helm) | PMM |

**Credentials**: AWS, SSH keys, Red Hat pull secrets
**Used by**: PMM OpenShift testing pipelines

## Related

- Root rules: [AGENTS.md](../AGENTS.md)
- Infrastructure: [IaC/AGENTS.md](../IaC/AGENTS.md)
- Helper scripts: [resources/AGENTS.md](../resources/AGENTS.md)
- Product guides: [pmm/AGENTS.md](../pmm/AGENTS.md), [cloud/AGENTS.md](../cloud/AGENTS.md), etc.

