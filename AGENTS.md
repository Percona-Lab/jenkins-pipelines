# AGENTS.md

Instructions for AI coding agents working with the Percona `jenkins-pipelines` repository. Per the [AGENTS.md specification](https://agents.md), treat this as the README for agents: the closest `AGENTS.md` on disk wins, and this root file provides org-wide rules that subdirectories extend but never relax.

## How to Use These Instructions

1. **Locate scope:** Identify the directories/files you will touch and open the nearest `AGENTS.md` (`pmm/AGENTS.md`, `vars/AGENTS.md`, etc.). Update the relevant file if you discover new constraints—this documentation is a living artifact.
2. **Inspect existing jobs first:** Use `~/bin/jenkins job <instance> info|config` to understand real parameters, SCM paths, and downstream triggers before editing any pipeline.
3. **Mirror existing patterns:** Prefer `rg`/`rg --files` for search, follow the same helper functions (`vars/`) and agent labels already used in that product.
4. **Validate locally:** Lint Groovy, byte-compile Python helpers, and—when possible—perform Jenkins dry-runs or fork-based tests before submitting changes.
5. **Keep secrets safe:** Never echo credentials; wrap everything with `withCredentials`, and clean workspaces with `deleteDir()` to avoid residue on long-lived agents.

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

## Project Overview

Jenkins pipeline definitions and shared libraries for Percona's CI/CD infrastructure. Contains 100+ pipeline definitions across 15+ products (PMM, PXC, PSMDB, PXB, PS, etc.).

**Tech Stack:** Groovy (Jenkins Declarative Pipelines), Python, Bash, AWS CloudFormation, AWS CDK.

## Project Structure

```
jenkins-pipelines/
├── pmm/                 # PMM pipelines (50+ jobs) - See pmm/AGENTS.md
│   ├── v3/              # PMM v3 specific (HA/EKS, AMI/OVF, upgrades) - See pmm/v3/AGENTS.md
│   └── openshift/       # OpenShift deployments
├── ppg/                 # Percona Distribution for PostgreSQL (69 files) - See ppg/AGENTS.md
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
├── pcsm/                # Percona Cloud Service Manager - See pcsm/AGENTS.md
├── percona-telemetry-agent/  # Telemetry Agent - See percona-telemetry-agent/AGENTS.md
├── cloud/               # Cloud/operator pipelines - See cloud/AGENTS.md
├── vars/                # Shared library functions (~70 helpers) - See vars/AGENTS.md
├── resources/           # Python scripts for pipelines
├── IaC/                 # Infrastructure as Code - See IaC/AGENTS.md
│   └── *.cd/            # Product-specific CloudFormation (pmm.cd, ps80.cd, psmdb.cd, pg.cd, etc.)
└── docs/                # Documentation
```

**Navigation:** Each directory with an AGENTS.md file contains product-specific patterns, conventions, and workflows. Start with this root file, then drill into subdirectories as needed. Nested AGENTS.md files extend their parents.

**Auto-discovery:** Agents automatically read the nearest AGENTS.md in the directory tree.

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

## CLI
```bash
~/bin/jenkins job <inst> list                     # List all jobs
~/bin/jenkins job <inst> list | grep <pattern>    # Filter jobs
~/bin/jenkins params <inst>/<job>                 # Show parameters
~/bin/jenkins build <inst>/<job> -p KEY=val       # Trigger build
~/bin/jenkins logs <inst>/<job> -f                # Follow logs
~/bin/jenkins job <inst> config <job> --yaml      # Export config
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
3. **Config diffing:** Export live configs to YAML via `~/bin/jenkins job <instance> config <job> --yaml > jobs/<job>.yaml` and diff against local edits before pushing.
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
// 1. Create script in resources/your_script.py
// 2. Call via helper
def result = runPython('your_script', '--arg value')
```

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

## Reference Files

- `pmm/README.md` – PMM-specific best practices.
- `pmm/`, `pxc/`, `pxb/`, `psmdb/`, `ps/`, `pbm/`, `cloud/`, `vars/`, `IaC/` each contain their own `AGENTS.md` with tighter scope.
- `vars/` – Shared library functions (Groovy).
- `resources/` – Python helpers invoked via `runPython`.
- `IaC/` – Infrastructure-as-code definitions for Jenkins masters.
- `~/.config/jenkins-cli/config.json` – Jenkins CLI instance definitions and `_default` selection.
- `/.github/CODEOWNERS` – Product-specific reviewers/approvers.
