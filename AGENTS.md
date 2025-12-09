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

Jenkins pipeline definitions and shared libraries for Percona's CI/CD infrastructure on Hetzner Cloud. Contains 100+ pipeline definitions across 15+ products with ARM64 architecture support and dual-cloud capability (Hetzner + AWS).

**Tech Stack:** Groovy (Jenkins Declarative Pipelines), Python, Bash, AWS CloudFormation.

## Project Structure

```
jenkins-pipelines/ (Hetzner Branch)
├── pmm/                 # PMM pipelines - See pmm/AGENTS.md
│   └── v3/              # PMM v3 (AMI/OVF, no EKS/OpenShift) - See pmm/v3/AGENTS.md
├── ppg/                 # PostgreSQL (138+ files, ARM64 support) - See ppg/AGENTS.md
├── ps/                  # Percona Server MySQL - See ps/AGENTS.md
├── pxc/                 # XtraDB Cluster - See pxc/AGENTS.md
├── psmdb/               # Percona Server MongoDB - See psmdb/AGENTS.md
├── pxb/                 # XtraBackup - See pxb/AGENTS.md
├── pbm/                 # Backup MongoDB - See pbm/AGENTS.md
├── pdmdb/               # Distribution: MongoDB - See pdmdb/AGENTS.md
├── pdps/                # Distribution: Percona Server - See pdps/AGENTS.md
├── pdpxc/               # Distribution: XtraDB Cluster - See pdpxc/AGENTS.md
├── proxysql/            # ProxySQL (2.x + 3.x) - See proxysql/AGENTS.md
├── prel/                # Percona Release - See prel/AGENTS.md
├── pcsm/                # Cloud Service Manager (ARM64 support) - See pcsm/AGENTS.md
├── percona-telemetry-agent/  # Telemetry - See percona-telemetry-agent/AGENTS.md
├── cloud/               # Cloud/operator pipelines - See cloud/AGENTS.md
├── vars/                # Shared library (~70 helpers) - See vars/AGENTS.md
├── resources/           # Python scripts
├── IaC/                 # Infrastructure (Hetzner integration) - See IaC/AGENTS.md
│   └── *.cd/            # CloudFormation + htz.cloud.groovy files
└── docs/                # Documentation
```

**Navigation:** Each AGENTS.md contains product-specific patterns and workflows. Start here, then drill into subdirectories. Nested AGENTS.md files extend parents.

**Auto-discovery:** Agents read the nearest AGENTS.md in the directory tree.

## Jenkins CLI & Common Commands

### Core Operations

```bash
# List jobs
~/bin/jenkins job <instance> list

# Get job details
~/bin/jenkins job <instance> info <job-name>
~/bin/jenkins job <instance> params <job-name>

# Export config
~/bin/jenkins job <instance> config <job-name> --yaml

# Update job
~/bin/jenkins job <instance> update <job-name> --config job.yaml
```

**Instances:** `pmm`, `ps80`, `psmdb`, `pxc`, `pxb`, `pt`, `ps57`, `ps56`, `ps3`, `fb`, `cloud`, `rel`, `pg`

## Validation & Testing

### Groovy Syntax Check
```bash
groovy -e "new GroovyShell().parse(new File('path/to/file.groovy'))"
```

### Python Validation
```bash
python3 -m py_compile resources/pmm/script.py
```

### Config Diff
```bash
~/bin/jenkins job <instance> config <job-name> --yaml > jobs/<job>.yaml
```

## Shared Library (`vars/`)

70+ helper functions. Always prefer existing helpers over reimplementation.

Common helpers:
- `pmmVersion()`, `installDocker()`, `moleculeExecute()`
- `cleanUpWS()`, `pushArtifactFolder()`, `runPython()`
- `ppgScenarios()`, `buildStage()`, `withCredentials()`

See [vars/AGENTS.md](vars/AGENTS.md) for complete list.

## Agent Labels

### Hetzner Cloud Labels
- `docker-x64-min` – x64 builds (Hetzner)
- `docker-aarch64` – ARM64 builds (Hetzner)
- `cli-hetzner` – Long-lived CLI agent (Hetzner)

### Conditional Labels
```groovy
// Many jobs use CLOUD parameter for provider selection
agent {
    label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
}
```

## Hetzner Branch Differences

### Architecture Support
- **ARM64**: 27 additional ARM jobs in ppg/ (*-arm.groovy)
- **Multi-arch**: PCSM, Docker images support both x64 and ARM64

### Cloud Infrastructure
- **Hetzner Cloud**: Primary infrastructure
- **AWS**: Secondary/legacy support via CLOUD parameter
- **Storage**: Hetzner Object Storage (not AWS S3)

### Removed Features
- ❌ EKS high-availability testing
- ❌ OpenShift cluster provisioning
- ❌ `openshiftCluster.*` helpers (removed from vars/)

### Added Features
- ✅ ARM64 architecture support (ppg, pcsm)
- ✅ Hetzner Cloud integration (10 htz.cloud.groovy files)
- ✅ ProxySQL 3.x support
- ✅ Dual cloud provider selection

## Credentials

Wrap all credential access:
```groovy
withCredentials([aws(...)]) {
    // Access AWS resources
}
```

Never hardcode secrets. Clean workspaces in `post { always { deleteDir() } }`.

## Boundaries

### Always Do
- Use `withCredentials()` wrappers
- Clean up infrastructure in `post` blocks
- Follow existing patterns
- Validate locally before pushing

### Ask First
- Creating shared library functions
- Modifying IaC stacks
- Changes affecting multiple products
- Adding credentials or IAM policies

### Never Do
- Hardcode credentials
- Remove existing parameters (breaking changes)
- Switch to scripted pipelines
- Delete resources without tracing dependencies

## Parameters

Treat job parameters as API contracts:
- Extend via additional parameters
- Never rename or remove existing parameters
- Downstream automation depends on stable interfaces

## Library Version

```groovy
// Hetzner branch uses separate library for new jobs
@Library('jenkins-pipelines@hetzner') _

// Legacy jobs may still use master
@Library('jenkins-pipelines@master') _
```
