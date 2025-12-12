# AGENTS.md

Instructions for AI coding agents working with the Percona `jenkins-pipelines` repository. Per the [AGENTS.md specification](https://agents.md), treat this as the README for agents: the closest `AGENTS.md` on disk wins, and this root file provides org-wide rules that subdirectories extend but never relax.

## How to Use These Instructions

1. **Locate scope:** Identify the directories/files you will touch and open the nearest `AGENTS.md` (`pmm/AGENTS.md`, `vars/AGENTS.md`, etc.). Update the relevant file if you discover new constraints—this documentation is a living artifact.
2. **Inspect existing jobs first:** Use `~/bin/jenkins job <instance> info|config` to understand real parameters, SCM paths, `script-path` (job → pipeline mapping), and downstream triggers before editing any pipeline.
3. **Mirror existing patterns:** Prefer `rg`/`rg --files` for search, follow the same helper functions (`vars/`) and agent labels already used in that product.
4. **Validate locally:** Lint Groovy, byte-compile Python helpers, and—when possible—perform Jenkins dry-runs or fork-based tests before submitting changes.
5. **Keep secrets safe:** Never echo credentials; wrap everything with `withCredentials`, and clean workspaces with `deleteDir()` to avoid residue on long-lived agents.
6. **Keep docs high-signal:** Prefer stable facts (job names, `script-path`, parameter contracts, cleanup rules) and avoid hardcoding contributor lists; use `git log --pretty=format:'%h %cd %s' --date=short -- <path>` for recent context.

## Setup Commands

### Tool prerequisites

```bash
# macOS (Homebrew)
brew install groovy python@3 jq yq awscli ripgrep

# Debian/Ubuntu
sudo apt-get update && sudo apt-get install -y groovy python3 python3-pip jq yq awscli ripgrep

# Install uv (needed because ~/bin/jenkins uses `uv run --script`)
curl -LsSf https://astral.sh/uv/install.sh | sh
```

- Ensure the Jenkins CLI dependencies are available locally: `python3 -m pip install requests click pyyaml lxml pydantic`.
- Keep `kubectl`, `aws`, `eksctl`, and `oc` handy when working on cloud/operator jobs.

### Jenkins CLI bootstrap

```bash
chmod +x ~/bin/jenkins
~/bin/jenkins list-instances                      # verify connectivity
~/bin/jenkins job pmm list | head                 # sanity-check auth
export JENKINS_INSTANCE=pmm                       # optional default
```

The CLI (a Python script executed via `uv run --script`) can list jobs, fetch configs, tail logs, inspect queue items, and convert YAML job definitions to XML automatically when you pass `--config <file>` to `job create|update`.

### Jenkins job configs (JJB YAML)

Many Jenkins jobs are managed via Jenkins Job Builder (JJB) YAML checked into this repo (often under `*/jenkins/*.yml`):
- YAML is the source of truth for **job name**, **parameters**, **triggers/retention**, and for Pipeline jobs the `script-path` pointing at a `.groovy` file.
- Job names often don’t match Groovy filenames; always confirm the `script-path` before editing/renaming.
- If you change params/cron/retention/script-path, update the YAML and keep the pipeline’s `triggers { ... }` / `buildDiscarder(...)` consistent.
- Avoid editing JJB-managed jobs in the Jenkins UI; export and diff via `~/bin/jenkins job <instance> config <job> -f yaml`.

Quick find:
```bash
rg -n "name:\\s+<job-name>\\b" --glob '*.yml'
rg -n "script-path:" --glob '*.yml'
```

## Project Overview

Jenkins pipeline definitions and shared libraries for Percona's CI/CD infrastructure. Contains 100+ pipeline definitions across 15+ products (PMM, PXC, PSMDB, PXB, PS, etc.).

**Tech Stack:** Groovy (Jenkins Declarative Pipelines), Python, Bash, AWS CloudFormation, AWS CDK.

## Project Structure

```
jenkins-pipelines/
├── pmm/                 # PMM pipelines (50+ jobs)
│   ├── v3/              # PMM v3 specific (HA/EKS, AMI/OVF, upgrades)
│   └── openshift/       # OpenShift deployments
├── ppg/                 # Percona Distribution for PostgreSQL (69 files, 65 using library)
├── ps/                  # Percona Server MySQL - See ps/AGENTS.md
├── pxc/                 # Percona XtraDB Cluster - See pxc/AGENTS.md
├── psmdb/               # Percona Server MongoDB - See psmdb/AGENTS.md
├── pxb/                 # Percona XtraBackup - See pxb/AGENTS.md
├── pbm/                 # Percona Backup MongoDB - See pbm/AGENTS.md
├── pdmdb/               # Distribution: Percona Server MongoDB - See pdmdb/AGENTS.md
├── pdps/                # Distribution: Percona Server - See pdps/AGENTS.md
├── pdpxc/               # Distribution: XtraDB Cluster - See pdpxc/AGENTS.md
├── proxysql/            # ProxySQL (2.x) - See proxysql/AGENTS.md
├── prel/                # Percona Release tool - See prel/AGENTS.md
├── pcsm/                # Percona Cloud Service Manager
├── percona-telemetry-agent/  # Telemetry Agent - See percona-telemetry-agent/AGENTS.md
├── cloud/               # Cloud/operator pipelines - See cloud/AGENTS.md
├── vars/                # Shared library functions (~100 helpers) - See vars/AGENTS.md
├── resources/           # Python helper scripts - See resources/AGENTS.md
│   ├── pmm/              # PMM-specific (DigitalOcean cleanup)
│   └── cloud/aws-functions/  # AWS cleanup scripts (7 Lambda-ready)
├── IaC/                 # Infrastructure as Code - See IaC/AGENTS.md
│   ├── cdk/              # CDK stacks (Lambda cleanup)
│   ├── *.cd/             # Jenkins instance configs (12 directories)
│   └── *.yml             # CloudFormation templates (14 stacks)
└── docs/                # Documentation
```

**Navigation:** Each directory with an AGENTS.md file contains product-specific patterns, conventions, and workflows. Start with this root file, then drill into subdirectories as needed. Nested AGENTS.md files extend their parents.

**Auto-discovery:** Agents automatically read the nearest AGENTS.md in the directory tree.

## Repo Dynamics (last 12 months)

Snapshot (as of 2025-12) derived from `git log --since='12 months ago'`:

- **Mostly product-scoped changes:** most commits touch a single top-level directory; cross-directory edits are the exception (most commonly `pmm/` + `vars/`).
- **Most active areas (by commits touching the path):** `pmm/` (96), `cloud/` (81), `vars/` (69), `IaC/` (52), `ps/` (42), `pxc/` (40), `pxb/` (40).
- **Common co-changes:** `pmm/` + `vars/` and packaging families (`ps/`, `pxb/`, `pxc/`) are the most frequent multi-directory edits; treat them as coupled when changing shared helpers or packaging/test flows.
- **Churn hotspots:** pipeline hotspots are `pmm/v3/pmm3-ui-tests-nightly.groovy` and `cloud/jenkins/pgo_*.groovy`; shared-library hotspots are `vars/moleculeParallelTest.groovy` (AMI map) and `vars/pmmVersion.groovy` (release/version map).
- **Major milestones (examples):**
  - `CLOUD-875`: consolidated and deleted duplicated cloud pipelines (2024-12 → 2025-03).
  - `PMM-14154`: removed PMM v2 pipelines (large deletion commit).
  - Dec 2025: expanded `AGENTS.md` documentation across products with LLM-optimized job dependency graphs.
- **Hetzner migration:** PSMDB and PBM jobs migrating to Hetzner infrastructure (jobs prefixed `hetzner-*`).
- **Release cadence signal:** operator versions are frequently tagged (`*-operator-*`); use tags as a quick "what changed recently" index.

Useful commands:
```bash
git log --since='12 months ago' --pretty=format:'%h %cd %s' --date=short -- <path>
git log --since='12 months ago' --name-only --pretty=format: -- <path> | sed '/^$/d' | sort | uniq -c | sort -nr | head
git tag -l '*-operator-*' --sort=-creatordate | head -n 20
```

# Jenkins

## Instances

| Instance | URL | Products Hosted |
|----------|-----|-----------------|
| pmm | https://pmm.cd.percona.com | PMM, telemetry agent |
| psmdb | https://psmdb.cd.percona.com | PSMDB, PBM, PCSM, PDMDB |
| ps80 | https://ps80.cd.percona.com | PS, PDPS, ProxySQL |
| pxc | https://pxc.cd.percona.com | PXC, PDPXC, PT |
| pxb | https://pxb.cd.percona.com | XtraBackup |
| pg | https://pg.cd.percona.com | PostgreSQL testing |
| rel | https://rel.cd.percona.com | Releases, PPG builds |
| cloud | https://cloud.cd.percona.com | K8s operators |

**Notes:**
- `psmdb` hosts MongoDB ecosystem: PSMDB + PBM + PCSM + PDMDB
- `ps80` hosts MySQL ecosystem: PS + PDPS + ProxySQL + perf tests
- `pxc` hosts Galera ecosystem: PXC + PDPXC + some PXB/PT jobs
- `rel` is the main release instance
- `pg` and `pxb` are dedicated single-product instances

## Product Dependencies

**MongoDB family:** PSMDB → PBM → PDMDB → PSMDBO operator
**MySQL family:** PS → PXB → PDPS → PSO operator
**Galera family:** PXC → ProxySQL → PDPXC → PXCO operator
**Cross-family:** PXB consumed by PS, PXC, PDPS, PDPXC

When modifying upstream products, verify downstream jobs still pass.

## Hetzner Migration

| Product | Status | Jobs | Agent Labels |
|---------|--------|------|--------------|
| PSMDB | Complete (branch) | hetzner-psmdb-* | docker-x64-min |
| PBM | Complete (branch) | hetzner-pbm-* | docker-x64 |
| PXC 5.7 | Partial (param) | pxc57-pipeline CLOUD=Hetzner | docker-x64, launcher-x64 |
| Operators | Optional (param) | JENKINS_AGENT=Hetzner | docker-x64-min |

**Branch**: `hetzner` branch for PSMDB/PBM (diverged from master)
**Pattern**: Hetzner default for PSMDB/PBM, AWS default for others

## PRO Build Infrastructure

| Product | Private Repo | Credential | Instance |
|---------|-------------|------------|----------|
| PS | percona-server-private-build | PS_PRIVATE_REPO_ACCESS | ps80.cd |
| PXC | percona-xtradb-cluster-private-build | PS_PRIVATE_REPO_ACCESS | pxc.cd |
| PXB | percona-xtrabackup-private-build | PS_PRIVATE_REPO_ACCESS | pxb.cd |
| ProxySQL | proxysql-packaging (private) | PS_PRIVATE_REPO_ACCESS | pxc.cd |
| PSMDB | percona-server-mongodb-private | PSMDB_PRIVATE_REPO_ACCESS | psmdb.cd |

**Pattern**: Private repos for PRO/Enterprise, separate release pipelines on rel.cd
**Credentials**: 2 families (PS vs PSMDB)

## JJB (Jenkins Job Builder)

| Product | YAML Files | Pattern |
|---------|-----------|---------|
| cloud | 25 | script-path → Groovy |
| pxc | 20 | Job name ≠ file name |
| ps | 14 | Check script-path |
| psmdb | 12 | YAML = source of truth |
| pxb | 10 | params, triggers, retention |

**Quick find**:
```bash
rg -n "name:\\s+<job>\\b" --glob '*.yml'    # Find job config
rg -n "script-path:" --glob '*.yml'          # Find pipeline mapping
```

**Warning**: Job names often don't match Groovy filenames - always check `script-path`

## Platform Adoption

| Platform | Status | Products |
|----------|--------|----------|
| RHEL 10 | Active | PS80 Pro, PDPS, PDPXC, PPG, PXB, PXC |
| Ubuntu Noble (24.04) | Widespread | All active products |
| Debian 13 (Trixie) | Emerging | PPG, PXB, PXC |
| Amazon Linux 2023 | Growing | PCSM, PDMDB 6.0+, PS80 Pro |
| Ubuntu 20.04 (Focal) | Deprecated | Removed (EOL May 2025) |

## CLI
```bash
~/bin/jenkins job <inst> list                     # List all jobs
~/bin/jenkins job <inst> list | grep <pattern>    # Filter jobs
~/bin/jenkins params <inst>/<job>                 # Show parameters
~/bin/jenkins build <inst>/<job> -p KEY=val       # Trigger build
~/bin/jenkins logs <inst>/<job> -f                # Follow logs
~/bin/jenkins job <inst> config <job> -f yaml     # Export config as YAML
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
# Config: ~/.config/jenkins-cli/config.json (if using jenkins CLI)

# List jobs (note: URL-encode brackets as %5B %5D)
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name'

# Get job config (XML)
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/job/JOB_NAME/config.xml"

# Get job parameters
curl -su "USER:TOKEN" "https://psmdb.cd.percona.com/job/JOB_NAME/api/json?tree=property%5BparameterDefinitions%5Bname,defaultParameterValue%5Bvalue%5D%5D%5D"

# Trigger build (POST)
curl -su "USER:TOKEN" -X POST "https://psmdb.cd.percona.com/job/JOB_NAME/buildWithParameters?KEY=val"
```

## Job Patterns
| Pattern | Instance | Description |
|---------|----------|-------------|
| `psmdb*`, `pbm*` | psmdb | MongoDB, PBM |
| `pxc*`, `proxysql*` | pxc | XtraDB Cluster |
| `ps-*`, `ps8*` | ps80 | Percona Server |
| `pxb*` | pxb | XtraBackup |
| `pmm*` | pmm | PMM |
| `*-operator-*` | cloud | K8s operators |
| `ppg*`, `pg_*` | rel, pg | PostgreSQL |

## Validation & Testing

1. **Groovy syntax check:**
   ```bash
   groovy -e "new GroovyShell().parse(new File('path/to/pipeline.groovy'))"
   ```
2. **Python helper validation:**
   ```bash
   python3 -m py_compile resources/script.py
   ```
3. **Config diffing:** Export live configs to YAML via `~/bin/jenkins job <instance> config <job> -f yaml > jobs/<job>.yaml` and diff against local edits before pushing.
4. **Dry run:** Prefer Jenkins test jobs with `echo` placeholders or a forked library branch that points to your pipelines.
5. **Runtime validation:** For major changes, trigger a build with safe parameters and monitor via `~/bin/jenkins job <instance> logs <job> --follow`.

There is no fully automated test suite, so local linting + Jenkins dry-runs are the safety net.

## Code Style

### Pipeline Structure (Declarative)

```groovy
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])

pipeline {
    agent { label 'agent-amd64-ol9' }

    parameters {
        string(name: 'BRANCH', defaultValue: 'main', description: 'Git branch', trim: true)
        choice(name: 'VERSION', choices: ['1.0', '2.0'], description: 'Version to build')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))  // REQUIRED
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    environment {
        CLUSTER_NAME = "test-${BUILD_NUMBER}"
    }

    stages {
        stage('Build') {
            steps {
                // ...
            }
        }
    }

    post {
        always {
            deleteDir()  // REQUIRED for long-lived agents
        }
    }
}
```

### Agent Labels

| Label | Use Case | Lifecycle |
|-------|----------|-----------|
| `agent-amd64-ol9` | Default builds (Oracle Linux 9, x86_64) | Short-lived (dies after task) |
| `agent-arm64-ol9` | ARM64 builds | Short-lived |
| `cli` | AWS CLI, kubectl commands only | Long-lived, read-only filesystem |

### Credential Patterns

```groovy
// AWS credentials (preferred)
withCredentials([aws(
    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
    credentialsId: 'pmm-staging-slave',
    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
)]) {
    sh 'aws s3 ls'
}

// SSH credentials
withCredentials([sshUserPrivateKey(
    credentialsId: 'aws-jenkins',
    keyFileVariable: 'KEY_PATH',
    usernameVariable: 'USER'
)]) {
    sh "ssh -i ${KEY_PATH} ${USER}@host"
}
```

### Shared Library Usage

```groovy
// Use existing helpers from vars/
def version = pmmVersion('dev-latest')  // Get PMM version
runPython('script_name', 'args')        // Run Python from resources/
installDocker()                         // Setup Docker
```

### Common Patterns

**Job chaining**

```groovy
build job: 'downstream-job', parameters: [
    string(name: 'PARAM', value: env.VALUE)
], wait: true
```

**Parallel stages**

```groovy
stage('Tests') {
    parallel {
        stage('Unit') { steps { /* ... */ } }
        stage('Integration') { steps { /* ... */ } }
    }
}
```

**Python integration**

```groovy
// 1. Create script in resources/pmm/your_script.py
// 2. Call via helper (runPython loads from resources/pmm/)
def result = runPython('your_script', '--arg value')
```

## AWS Infrastructure

### Regions
| Region | Primary Use |
|--------|-------------|
| `us-east-1` | Some legacy workloads |
| `us-east-2` | Primary region for most builds and testing |
| `us-west-2` | Secondary region for some cloud tests |
| `eu-west-1` | EU-based testing |

**Default**: Most pipelines use `us-east-2` unless explicitly configured otherwise.

### Services Used
- **EC2**: Build agents, spot instances for cost optimization
- **S3**: Artifact storage, build cache, package repositories
- **EKS**: Kubernetes operator testing
- **ECR**: Container image storage
- **Lambda**: Cleanup automation (orphaned resources)
- **CloudFormation**: Jenkins master infrastructure (in `IaC/`)
- **Route53**: DNS for test environments

### Common AWS Patterns

```groovy
// Set region explicitly
environment {
    AWS_DEFAULT_REGION = 'us-east-2'
}

// S3 artifact upload
sh "aws s3 cp artifact.tar.gz s3://bucket-name/path/ --region us-east-2"

// EC2 spot instances: search for existing product-specific flows first
// (look for `request-spot-instances` in pipelines/helpers before changing behavior).

// EKS cluster operations
sh "eksctl create cluster --name \${CLUSTER_NAME} --region us-east-2"
```

### Cost Considerations
- **Spot instances**: Use for non-critical builds (70% cost savings)
- **EKS clusters**: ~$200/day if left running - ALWAYS cleanup in `post` blocks
- **S3 lifecycle**: Artifacts auto-expire after 90 days in most buckets
- **Resource tagging**: Tag all resources with `owner`, `purpose`, `expiry` for cleanup automation

## Security & Secrets

- Never hardcode credentials or tokens. Wrap every secret access in `withCredentials`.
- Use short-lived agents when possible; always call `deleteDir()` in `post { always { ... } }` for long-lived nodes.
- Keep `buildDiscarder(logRotator(numToKeepStr: '10'))` in every pipeline to prevent unbounded storage growth.
- Avoid `sh "set +x"` leaks by passing secrets via environment variables instead of interpolated strings.
- Clean up temporary infrastructure (clusters, spot instances, Molecule environments) even on failure.

## Boundaries

### Always Do

- Include `buildDiscarder(logRotator(numToKeepStr: '10'))` in `options`.
- Add `deleteDir()` in `post.always` for long-lived agents.
- Use `withCredentials()` wrappers and shared library helpers instead of duplicating logic.
- Follow existing patterns in the same product directory.

### Ask First

- Creating new shared library functions in `vars/`.
- Modifying `IaC/` CloudFormation or CDK stacks.
- Adding new Jenkins instances, credentials, or IAM policies.
- Changes that affect multiple products or Jenkins masters.

### Never Do

- Hardcode credentials or secrets.
- Remove existing parameters (downstream jobs depend on them).
- Switch pipelines to scripted syntax (keep Declarative).
- Modify `init.groovy.d/` without explicit approval.
- Delete jobs or IaC resources without tracing dependencies.

## Product AGENTS.md Files

Enhanced AGENTS.md files include: job dependency graphs, directory maps with line counts, credentials tables, Jenkins CLI quick reference, and key Jira tickets.

| File | Status | Keywords |
|------|--------|----------|
| [pmm/AGENTS.md](pmm/AGENTS.md) | Enhanced | pmm, monitoring, grafana, victoriametrics, prometheus, clickhouse, qan, eks-ha, ami, ovf, docker, ui-tests, playwright |
| [cloud/AGENTS.md](cloud/AGENTS.md) | Enhanced | operator, eks, gke, aks, openshift, kubernetes, k8s, pxc-operator, psmdb-operator, pg-operator, ps-operator |
| [ps/AGENTS.md](ps/AGENTS.md) | Enhanced | ps, percona-server, mysql, ps80, ps84, innodb, rocksdb, mysqld, replication, gtid, molecule |
| [pxc/AGENTS.md](pxc/AGENTS.md) | Enhanced | pxc, xtradb, galera, cluster, wsrep, sst, ist, hetzner, pxc80, pxc84, pxc57 |
| [pxb/AGENTS.md](pxb/AGENTS.md) | Enhanced | pxb, xtrabackup, backup, mysql-backup, incremental, full-backup, prepare, restore, kmip |
| [pbm/AGENTS.md](pbm/AGENTS.md) | Enhanced | pbm, percona-backup-mongodb, mongo-backup, pitr, s3-storage, gcs, azure, logical-backup, physical-backup |
| [proxysql/AGENTS.md](proxysql/AGENTS.md) | Enhanced | proxysql, proxy, load-balancer, query-routing, connection-pooling, mysql-proxy, admin-interface |
| [psmdb/AGENTS.md](psmdb/AGENTS.md) | Hetzner | psmdb, mongodb, percona-server-mongodb, replicaset, sharding (see hetzner branch) |
| [vars/AGENTS.md](vars/AGENTS.md) | Reference | vars, helpers, shared-library, groovy, pushArtifactFolder, popArtifactFolder, uploadDEBfromAWS, uploadRPMfromAWS, slackNotify, moleculeParallelTest, sync2ProdAutoBuild, runPython |
| [percona-telemetry-agent/AGENTS.md](percona-telemetry-agent/AGENTS.md) | Basic | telemetry, percona-telemetry-agent, pmm, agent, packaging, rpm, deb |
| [pdps/AGENTS.md](pdps/AGENTS.md) | Basic | pdps, distribution, percona-distribution-ps, orchestrator, proxysql, toolkit |
| [pdpxc/AGENTS.md](pdpxc/AGENTS.md) | Basic | pdpxc, distribution, percona-distribution-pxc, haproxy, proxysql, garbd |
| [pdmdb/AGENTS.md](pdmdb/AGENTS.md) | Basic | pdmdb, distribution, percona-distribution-mongodb, pbm, mongosh, mongo-tools |
| [prel/AGENTS.md](prel/AGENTS.md) | Basic | release, prel, sync2prod, publishing, repo, packages, rpm, deb, tarballs |

## Reference Files

- `vars/` – Shared library functions (Groovy)
- `resources/` – Python helpers invoked via `runPython`
- `IaC/` – Infrastructure-as-code definitions for Jenkins masters
- `~/.config/jenkins-cli/config.json` – Jenkins CLI instance definitions
- `.github/CODEOWNERS` – Product-specific reviewers/approvers
