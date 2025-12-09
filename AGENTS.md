# AGENTS.md (Hetzner Branch)

Instructions for AI coding agents working with the Percona `jenkins-pipelines` repository (Hetzner branch). Per the [AGENTS.md specification](https://agents.md), treat this as the README for agents: the closest `AGENTS.md` on disk wins, and this root file provides org-wide rules that subdirectories extend but never relax.

## How to Use These Instructions

1. **Locate scope:** Identify directories/files you'll touch and open the nearest `AGENTS.md`. Update the file if you discover new constraints.
2. **Inspect existing jobs first:** Use `~/bin/jenkins job <instance> info|config` to understand parameters, SCM paths, and triggers.
3. **Mirror existing patterns:** Use `rg` for search, follow helper functions from `vars/`, and match agent labels already in use.
4. **Validate locally:** Lint Groovy, validate Python, and perform Jenkins dry-runs before submitting.
5. **Keep secrets safe:** Never echo credentials; use `withCredentials`, clean workspaces with `deleteDir()`.

## Setup Commands

### Tool Prerequisites

```bash
# macOS (Homebrew)
brew install groovy python@3 jq yq awscli ripgrep

# Install uv (~/bin/jenkins uses uv run --script)
curl -LsSf https://astral.sh/uv/install.sh | sh
```

Keep `kubectl`, `aws`, and `oc` handy when working on cloud/operator jobs.

### Jenkins CLI Bootstrap

```bash
chmod +x ~/bin/jenkins
~/bin/jenkins list-instances
~/bin/jenkins job pmm list | head
```

## Project Overview

Jenkins pipeline definitions and shared libraries for Percona's CI/CD infrastructure on Hetzner Cloud. 15+ products with ARM64 architecture support and dual-cloud capability (Hetzner + AWS).

**Tech Stack:** Groovy (Jenkins Declarative Pipelines), Python, Bash, AWS CloudFormation.

## Project Structure

```
jenkins-pipelines/ (Hetzner Branch)
├── pmm/                 # PMM pipelines - See pmm/AGENTS.md
│   └── v3/              # PMM v3 (AMI/OVF, no EKS/OpenShift) - See pmm/v3/AGENTS.md
├── ppg/                 # PostgreSQL (138+ files, ARM64 support) - See ppg/AGENTS.md
├── psmdb/               # Percona Server MongoDB - See psmdb/AGENTS.md
├── pbm/                 # Backup MongoDB - See pbm/AGENTS.md
├── pcsm/                # Cloud Service Manager (ARM64 support) - See pcsm/AGENTS.md
├── vars/                # Shared library (~70 helpers) - See vars/AGENTS.md
├── IaC/                 # Infrastructure (Hetzner integration) - See IaC/AGENTS.md
│   └── *.cd/            # CloudFormation + htz.cloud.groovy files
├── resources/           # Python scripts
└── docs/                # Documentation

# Components without AGENTS.md in hetzner branch (see master branch):
# ps/, pxc/, pxb/, pdmdb/, pdps/, pdpxc/, proxysql/, prel/,
# percona-telemetry-agent/, cloud/
```

**Navigation:** Each AGENTS.md contains product-specific patterns and workflows. Start here, then drill into subdirectories. Nested AGENTS.md files extend parents.

**Auto-discovery:** Agents read the nearest AGENTS.md in the directory tree.

# Jenkins

## Instances

| Instance | URL | Products |
|----------|-----|----------|
| pmm | https://pmm.cd.percona.com | PMM v2/v3 |
| psmdb | https://psmdb.cd.percona.com | MongoDB, PBM, PCSM |
| pg | https://pg.cd.percona.com | PostgreSQL |
| rel | https://rel.cd.percona.com | Release builds |
| ps80 | https://ps80.cd.percona.com | Percona Server |
| pxc | https://pxc.cd.percona.com | XtraDB Cluster |
| pxb | https://pxb.cd.percona.com | XtraBackup |

## CLI
```bash
~/bin/jenkins job <inst> list                     # List all jobs
~/bin/jenkins job <inst> list | grep hetzner      # Filter Hetzner jobs
~/bin/jenkins params <inst>/<job>                 # Show parameters
~/bin/jenkins build <inst>/<job> -p KEY=val       # Trigger build
~/bin/jenkins logs <inst>/<job> -f                # Follow logs
~/bin/jenkins job <inst> config <job> --yaml      # Export config
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
# Config: ~/.config/jenkins-cli/config.json

# List jobs (URL-encode brackets as %5B %5D)
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name'

# Filter Hetzner jobs
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("hetzner"))'

# Get job config
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/job/JOB_NAME/config.xml"

# Trigger build
curl -su "USER:TOKEN" -X POST "https://psmdb.cd.percona.com/job/JOB_NAME/buildWithParameters?KEY=val"
```

## Job Patterns

| Pattern | Instance | Description |
|---------|----------|-------------|
| `hetzner-*-RELEASE` | rel | Release builds |
| `hetzner-*-docker*` | rel, psmdb | Docker images (x64/arm) |
| `hetzner-*-arm*` | rel | ARM64 builds |
| `hetzner-psmdb*` | psmdb | MongoDB |
| `hetzner-pbm*` | psmdb | PBM backup |
| `hetzner-pcsm*` | psmdb | ClusterSync |
| `hetzner-pg*`, `hetzner-ppg*` | rel, pg | PostgreSQL |

## Agent Labels

| Label | Use Case |
|-------|----------|
| `docker-x64-min` | x64 builds (Hetzner) |
| `docker-aarch64` | ARM64 builds (Hetzner) |
| `cli-hetzner` | Long-lived CLI agent |

```groovy
// Conditional label based on CLOUD parameter
agent { label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker' }
```

## Credentials

Always use `withCredentials()`:
```groovy
withCredentials([aws(credentialsId: 'pmm-staging-slave', ...)]) {
    sh 'aws s3 ls'
}
```

Common credentials: `pmm-staging-slave`, `psmdb-staging`, `pbm-staging`, `aws-jenkins`

## Library

```groovy
@Library('jenkins-pipelines@hetzner') _  // Hetzner branch
@Library('jenkins-pipelines@master') _   // Legacy jobs
```

---

## Validation

```bash
# Groovy syntax
groovy -e "new GroovyShell().parse(new File('path/to/file.groovy'))"

# Python
python3 -m py_compile resources/pmm/script.py

# Config diff
~/bin/jenkins job <inst> config <job> --yaml > jobs/<job>.yaml
```

## Shared Library (`vars/`)

70+ helpers. Always prefer existing helpers over reimplementation.

Common: `pmmVersion()`, `installDocker()`, `moleculeExecute()`, `cleanUpWS()`, `pushArtifactFolder()`, `runPython()`

See [vars/AGENTS.md](vars/AGENTS.md).

## Hetzner Branch Differences

**Added:** ARM64 support (ppg, pcsm), Hetzner Cloud integration, ProxySQL 3.x, dual cloud selection

**Removed:** EKS HA testing, OpenShift provisioning, `openshiftCluster.*` helpers

## Boundaries

**Always:** Use `withCredentials()`, cleanup in `post` blocks, follow existing patterns

**Ask first:** New shared library functions, IaC changes, multi-product changes

**Never:** Hardcode credentials, remove parameters, switch to scripted pipelines

