# AGENTS.md - Cloud Pipelines

Extends: [../AGENTS.md](../AGENTS.md) | [../vars/AGENTS.md](../vars/AGENTS.md)

## TL;DR (read this first)

- **Main pipelines live in** `cloud/jenkins/*.groovy` (45 scripts).
- **Jobs are JJB-managed**: `cloud/jenkins/*.yml` defines job name/params + `script-path` → `cloud/jenkins/*.groovy` (don’t edit via Jenkins UI).
- **Four K8s operators tested**: PXC (pxco), MongoDB (psmdbo), PostgreSQL (pgo), MySQL (pso).
- **Six platforms**: EKS, GKE, AKS, OpenShift (ROSA), Minikube, DOKS.
- **No shared library**; pipelines use inline helpers + direct tool installation.
- **Clusters cost $150-300/day**: ALWAYS cleanup in `post.always` blocks.
- **Weekly scheduled tests**: Saturday/Sunday runs across all platforms.
- **Orphaned resource cleanup**: Python (AWS) and Go (GCP) scripts in `aws-functions/` and `gcp-functions/`.

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins instance | `cloud` |
| URL | https://cloud.cd.percona.com |
| Total jobs | ~60+ |
| Common job prefixes | `pxco-*`, `psmdbo-*`, `pgo-*`, `pso-*`, `weekly-*`, `*-docker-build`, `build-*-images` |
| Primary script root | `cloud/jenkins/` |
| JJB configs | `cloud/jenkins/*.yml` |
| AWS region | `us-east-2` |
| GCP region | Varies by test |
| Slack channel | `#cloud-dev-ci` |

## Dynamics Snapshot (repo scan + git)

Derived from a local scan of `cloud/**/*.groovy` and `git log --since='12 months ago' -- cloud`:

- **Most active pipelines (by commits)**: `pgo_openshift.groovy` (16), `pgo_aks.groovy` (16), `pgo_gke.groovy` (15), `pgo_eks.groovy` (14), `pso_openshift.groovy` (12).
- **PostgreSQL Operator (pgo)** is the most actively maintained operator in cloud/.
- **Platform coverage varies by operator**: pxco and pgo have DOKS support; psmdbo and pso do not.
- **Weekly schedulers trigger platform jobs**: `weekly_*.groovy` run on Saturday/Sunday and trigger 3x parallel runs per platform.
- **Ownership signal**: use `git shortlog -sn --since='12 months ago' HEAD -- cloud` (treat as a hint, not strict ownership).

## Job Dependency Graph (LLM-Optimized)

Data source: Codebase scan of `build job:` and `triggerJobMultiple()` calls. Last updated: 2025-12.

### Node Metrics

| Job | Type | Platforms | Cron | Notes |
|-----|------|-----------|------|-------|
| weekly-pxco | scheduler | EKS,GKE,AKS,OpenShift | `0 8 * * 6` (Sat 8AM) | Triggers 3x per platform |
| weekly-psmdbo | scheduler | EKS,GKE,AKS,OpenShift | `0 15 * * 6` (Sat 3PM) | Triggers 3x per platform |
| weekly-pgo | scheduler | EKS,GKE,AKS,OpenShift | `0 15 * * 0` (Sun 3PM) | Triggers 3x per platform |
| weekly-pso | scheduler | EKS,GKE,OpenShift | `0 8 * * 0` (Sun 8AM) | No AKS support |
| pxco-eks-1 | e2e test | EKS | - | 6 parallel clusters |
| pgo-eks-1 | e2e test | EKS | - | 6 parallel clusters |
| psmdbo-eks-1 | e2e test | EKS | - | 6 parallel clusters |
| pso-eks-1 | e2e test | EKS | - | 6 parallel clusters |

### Explicit Edges (from weekly schedulers)

```
weekly-pxco (Sat 8AM)
├── pxco-gke-1 (3x)
├── pxco-eks-1 (3x)
├── pxco-aks-1 (3x)
└── pxco-openshift-1 (3x)

weekly-psmdbo (Sat 3PM)
├── psmdbo-gke-1 (3x)
├── psmdbo-eks-1 (3x)
├── psmdbo-aks-1 (3x)
└── psmdbo-openshift-1 (3x)

weekly-pgo (Sun 3PM)
├── pgo-gke-1 (3x)
├── pgo-eks-1 (3x)
├── pgo-aks-1 (3x)
└── pgo-openshift-1 (3x)

weekly-pso (Sun 8AM)
├── pso-gke-1 (3x)
├── pso-eks-1 (3x)
└── pso-openshift-1 (3x)
```

### Operator-Platform Coverage Matrix

| Operator | EKS | GKE | AKS | OpenShift | Minikube | DOKS |
|----------|:---:|:---:|:---:|:---------:|:--------:|:----:|
| pxco (PXC) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| psmdbo (MongoDB) | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| pgo (PostgreSQL) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| pso (MySQL) | ✓ | ✓ | - | ✓ | ✓ | - |

### Cost Tiers

| Platform | Daily Cost | Risk Level |
|----------|------------|------------|
| OpenShift (ROSA) | ~$300/day | CRITICAL |
| EKS | ~$200/day | HIGH |
| GKE | ~$150/day | HIGH |
| AKS | ~$100/day | MEDIUM |
| Minikube | Free | LOW |
| DOKS | ~$50/day | LOW |

## Where Things Live (directory map)

```
cloud/
  jenkins/                           # Pipelines (45 .groovy) + JJB job configs (.yml)
    pxco_*.groovy                    # PXC Operator tests (6 platforms)
    psmdbo_*.groovy                  # MongoDB Operator tests (5 platforms)
    pgo_*.groovy                     # PostgreSQL Operator tests (6 platforms)
    pso_*.groovy                     # MySQL Operator tests (4 platforms)
    pgo_v1_*.groovy                  # Legacy PGO v1 pipelines (9 files)
    weekly_*.groovy                  # Weekly scheduled test runners (4 files)
    *_docker_build.groovy            # Docker image builds (11 files)
    *.yml                            # Jenkins Job Builder configs (job name, params, script-path)
  aws-functions/                     # AWS orphan cleanup (Python)
    orphaned_eks_clusters.py
    orphaned_openshift_instances.py
    orphaned_cloudformation.py
    orphaned_vpcs.py
    orphaned_oidc.py
    orphaned_openshift_eip.py
    orphaned_openshift_users.py
    utils.py
  gcp-functions/                     # GCP orphan cleanup (Go)
    cmd/clusters.go
    cmd/disks.go
    cmd/orphanedNeg.go
    cmd/orphanedResources.go
  local/
    checkout                         # Git clone/checkout helper script
  AGENTS.md                          # This file
```

## "What file do I edit?" (fast index)

Cloud jobs are mostly managed via Jenkins Job Builder (JJB):
- **Job config** (name/params/triggers + `script-path`): `cloud/jenkins/*.yml`
- **Pipeline logic** (Declarative pipeline): `cloud/jenkins/*.groovy`

Keep `triggers { cron(...) }` / `buildDiscarder(...)` consistent between the `.yml` and the `.groovy` when you touch schedules/retention.

### JJB Job Configs (source of truth for job name + script-path)

- Example: `pgo-eks-1` → `cloud/jenkins/pgo-eks-1.yml` → `script-path: cloud/jenkins/pgo_eks.groovy`
- Example: `weekly-pgo` → `cloud/jenkins/weekly-pgo.yml` → `script-path: cloud/jenkins/weekly_pgo.groovy`
- Example: `pgo-docker-build` → `cloud/jenkins/pgo-docker.yml` → `script-path: cloud/jenkins/pgo_docker_build.groovy`
- Freestyle “poll + trigger” wrappers: `cloud/jenkins/build-*-image*.yml` (example: `cloud/jenkins/build-pgo-image.yml` triggers `pgo-docker-build`)

### Operator E2E Tests by Platform

**PXC Operator (pxco)**:
- EKS: `cloud/jenkins/pxco_eks.groovy`
- GKE: `cloud/jenkins/pxco_gke.groovy`
- AKS: `cloud/jenkins/pxco_aks.groovy`
- OpenShift: `cloud/jenkins/pxco_openshift.groovy`
- Minikube: `cloud/jenkins/pxco_minikube.groovy`
- DOKS: `cloud/jenkins/pxco_doks.groovy`

**MongoDB Operator (psmdbo)**:
- EKS: `cloud/jenkins/psmdbo_eks.groovy`
- GKE: `cloud/jenkins/psmdbo_gke.groovy`
- AKS: `cloud/jenkins/psmdbo_aks.groovy`
- OpenShift: `cloud/jenkins/psmdbo_openshift.groovy`
- Minikube: `cloud/jenkins/psmdbo_minikube.groovy`

**PostgreSQL Operator (pgo)**:
- EKS: `cloud/jenkins/pgo_eks.groovy`
- GKE: `cloud/jenkins/pgo_gke.groovy`
- AKS: `cloud/jenkins/pgo_aks.groovy`
- OpenShift: `cloud/jenkins/pgo_openshift.groovy`
- Minikube: `cloud/jenkins/pgo_minikube.groovy`
- DOKS: `cloud/jenkins/pgo_doks.groovy`

**MySQL Operator (pso)**:
- EKS: `cloud/jenkins/pso_eks.groovy`
- GKE: `cloud/jenkins/pso_gke.groovy`
- OpenShift: `cloud/jenkins/pso_openshift.groovy`
- Minikube: `cloud/jenkins/pso_minikube.groovy`

### Weekly Schedulers

- **PXC weekly**: `cloud/jenkins/weekly_pxco.groovy` (Sat 8AM)
- **MongoDB weekly**: `cloud/jenkins/weekly_psmdbo.groovy` (Sat 3PM)
- **PostgreSQL weekly**: `cloud/jenkins/weekly_pgo.groovy` (Sun 3PM)
- **MySQL weekly**: `cloud/jenkins/weekly_pso.groovy` (Sun 8AM)

### Docker Image Builds

- PXC Operator: `cloud/jenkins/pxc_docker_build.groovy`
- MongoDB Operator: `cloud/jenkins/psmdb_docker_build.groovy`
- PostgreSQL Operator: `cloud/jenkins/pgo_docker_build.groovy`, `pgov2_docker_build.groovy`
- MySQL Operator: `cloud/jenkins/psmo_docker_build.groovy`
- PBM: `cloud/jenkins/pbm_docker_build.groovy`
- Fluent Bit: `cloud/jenkins/fluentbit_docker_build.groovy`
- Version Service: `cloud/jenkins/version_service_docker_build.groovy`
- Container images: `pxc_containers_docker_build.groovy`, `ps_containers_docker_build.groovy`, `pg_containers_docker_build.groovy`

### Legacy PGO v1 Pipelines

- EKS: `cloud/jenkins/pgo_v1_operator_eks.groovy`
- GKE: `cloud/jenkins/pgo_v1_operator_gke_version.groovy`
- OpenShift: `cloud/jenkins/pgo_v1_operator_aws_openshift-4.groovy`
- PG12/13 variants: `pgo_v1_pg12_*.groovy`, `pgo_v1_pg13_*.groovy`

### Orphaned Resource Cleanup

**AWS (Python)**:
- EKS clusters: `cloud/aws-functions/orphaned_eks_clusters.py`
- OpenShift instances: `cloud/aws-functions/orphaned_openshift_instances.py`
- CloudFormation stacks: `cloud/aws-functions/orphaned_cloudformation.py`
- VPCs: `cloud/aws-functions/orphaned_vpcs.py`
- OIDC providers: `cloud/aws-functions/orphaned_oidc.py`
- OpenShift EIPs: `cloud/aws-functions/orphaned_openshift_eip.py`
- OpenShift users: `cloud/aws-functions/orphaned_openshift_users.py`

**GCP (Go)**:
- GKE clusters: `cloud/gcp-functions/cmd/clusters.go`
- Orphaned disks: `cloud/gcp-functions/cmd/disks.go`
- NEGs: `cloud/gcp-functions/cmd/orphanedNeg.go`
- General resources: `cloud/gcp-functions/cmd/orphanedResources.go`

## Shared Libraries

### No shared library import

Cloud pipelines do NOT use `@Library` or `library` declarations. Instead they use:
- Inline helper functions (`prepareNode()`, `createCluster()`, `shutdownCluster()`)
- Direct tool installation in stages
- Local checkout script (`cloud/local/checkout`)
  - Note: some Docker build pipelines download `cloud/local/checkout` from `master` (example: `cloud/jenkins/version_service_docker_build.groovy`), so branch-only changes to `cloud/local/checkout` may not be exercised unless you update those jobs too.

### Common Inline Helpers

```groovy
// These helpers are usually defined per-file (not shared across the repo).
void prepareNode() { /* clone sources + install tools */ }

void createCluster(String CLUSTER_SUFFIX) {
    sh """
        tee cluster-${CLUSTER_SUFFIX}.yaml << EOF
        # eksctl cluster config...
        EOF
        eksctl create cluster -f cluster-${CLUSTER_SUFFIX}.yaml
    """
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    sh "eksctl delete cluster -f cluster-${CLUSTER_SUFFIX}.yaml --wait --force ... || true"
}
```

## Agents / Node Labels (used by cloud jobs)

| Label | Purpose | Used In |
|-------|---------|---------|
| `docker` | Standard Docker-capable agent | Most operator test pipelines |
| `docker-32gb` | High-memory for minikube | `*_minikube.groovy` |
| `docker-x64` | x64 Docker builds | Container image builds |
| `docker-x64-min` | Minimal x64 Docker | Operator Docker builds |

**Hetzner support** (conditional labels):
```groovy
agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker' }
agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
```

## Credentials (IDs you'll see in cloud pipelines)

### AWS Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `AMI/OVF` | AmazonWebServicesCredentialsBinding | General AWS (S3, ECR, queries) |
| `eks-cicd` | AmazonWebServicesCredentialsBinding | EKS cluster create/delete |
| `openshift-cicd` | AmazonWebServicesCredentialsBinding | OpenShift on AWS |
| `eks-conf-file` | file | EKS configuration file |

### GCP Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `GCP_PROJECT_ID` | string | GCP project identifier |
| `gcloud-key-file` | file | GCP service account key |
| `gcloud-alpha-key-file` | file | GCP alpha key |

### Azure Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `PERCONA-OPERATORS-SP` | azureServicePrincipal | AKS authentication |

### DigitalOcean Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `DOKS_TOKEN` | string | DigitalOcean API token |
| `DOKS_PROJECT_ID` | string | DigitalOcean project ID |

### Docker/Registry Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `hub.docker.com` | usernamePassword | Docker Hub push/pull |
| `docker.io` | usernamePassword | Docker.io read access |
| `DOCKER_REPOSITORY_PASSPHRASE` | string | Image signing |
| `DOCKER_REPO_KEY` | file | Repository key |

### OpenShift Credentials

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `aws-openshift-41-key-pub` | file | OpenShift node public key |
| `openshift4-secrets` | file | OpenShift 4 secrets |
| `openshift-secret-file` | file | OpenShift secret file |

### Cloud Secret Files

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `cloud-secret-file` | file | General cloud secrets (PXC, PGO) |
| `cloud-secret-file-psmdb` | file | PSMDB-specific secrets |
| `cloud-secret-file-ps` | file | PS-specific secrets |
| `cloud-minio-secret-file` | file | MinIO backup test secrets |

### API/Security Tokens

| Credential ID | Type | Purpose |
|---------------|------|---------|
| `GITHUB_API_TOKEN` | string | GitHub API access |
| `SNYK_ID` | string | Snyk vulnerability scanning |
| `SYSDIG-API-KEY` | string | Sysdig monitoring |
| `PMM-CHECK-DEV-TOKEN` | string | PMM telemetry token |

## Scheduled Jobs (cron) in `cloud/`

| Pipeline | Cron | Schedule | Targets |
|----------|------|----------|---------|
| `weekly_pxco.groovy` | `0 8 * * 6` | Saturday 8:00 AM | EKS, GKE, AKS, OpenShift (3x each) |
| `weekly_psmdbo.groovy` | `0 15 * * 6` | Saturday 3:00 PM | EKS, GKE, AKS, OpenShift (3x each) |
| `weekly_pgo.groovy` | `0 15 * * 0` | Sunday 3:00 PM | EKS, GKE, AKS, OpenShift (3x each) |
| `weekly_pso.groovy` | `0 8 * * 0` | Sunday 8:00 AM | EKS, GKE, OpenShift (3x each) |

**Weekend schedule rationale**: Expensive cluster tests run on weekends to minimize interference with weekday development.

## Fast Navigation (grep recipes)

```bash
# Find all operator tests for a platform
rg -l "_eks\.groovy" cloud/jenkins
rg -l "_openshift\.groovy" cloud/jenkins

# Find the JJB YAML for a Jenkins job name
rg -n "name:\\s+pxco-eks-1\\b" cloud/jenkins/*.yml

# See its script-path
rg -n "script-path:" cloud/jenkins/pxco-eks-1.yml

# Find all credential usage
rg -n "credentialsId:" cloud/jenkins

# Find cluster cleanup logic
rg -n "eksctl delete|shutdownCluster|deleteCluster" cloud/jenkins

# Find S3 cache/marker logic (skip reruns)
rg -n "percona-jenkins-artifactory|PARAMS_HASH|head-object" cloud/jenkins

# Find checkout script usage (some jobs wget it from GitHub raw)
rg -n "cloud/local/checkout|raw\\.githubusercontent\\.com/.*/cloud/local/checkout" cloud/jenkins

# Find weekly scheduler targets
rg -n "triggerJobMultiple" cloud/jenkins

# Find all Docker build pipelines
rg -l "docker_build" cloud/jenkins
```

## Git History (fast)

```bash
# Recent cloud changes
git log --oneline --max-count 50 -- cloud

# Churn hotspots
git log --since='12 months ago' --name-only --pretty=format: HEAD -- cloud \
  | sort | uniq -c | sort -rn | head

# Who touched cloud pipelines recently (names only; omit emails)
git shortlog -sn --since='12 months ago' HEAD -- cloud | head

# Operator release tags (cadence signal)
git tag -l '*-operator-*' --sort=-creatordate | head -n 20

# Follow a specific operator pipeline
git log --follow -p -- cloud/jenkins/pgo_eks.groovy
```

Recent structural changes (from `git log -- cloud`):
- `CLOUD-875` (2024-12 → 2025-03): consolidated and deleted duplicated cloud pipelines (watch for legacy names in older logs; use `git log --follow`).
- PostgreSQL Operator (pgo) tests most actively maintained.
- Hetzner agent support added across pipelines.
- DOKS (DigitalOcean) added for pxco and pgo.
- GCP cleanup functions added in Go.

## Local Validation

```bash
# Groovy syntax check
groovy -e "new GroovyShell().parse(new File('cloud/jenkins/pgo_eks.groovy'))"

# Python cleanup scripts
python3 -m py_compile cloud/aws-functions/orphaned_eks_clusters.py
python3 -m py_compile cloud/aws-functions/orphaned_openshift_instances.py

# Go cleanup utilities
cd cloud/gcp-functions && go build ./...
```

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| No cluster cleanup | $200+/day leaked | Always cleanup in `post.always` |
| Wrong credential wrapper | Multi-cloud auth fails | Match wrapper to cloud (AWS/GCP/Azure) |
| Missing timeout | Jobs hang forever | Add `timeout(time: 2, unit: 'HOURS')` |
| Hardcoded cluster names | Name collisions | Use `${BUILD_NUMBER}` suffix |
| No resource tagging | Cleanup automation fails | Tag with `delete-cluster-after-hours` |

## Notes / Known "gotchas"

- **EKS tests use 6 parallel clusters** (cluster1-cluster6) for test parallelization—ensure all are cleaned up.
- **OpenShift costs $300/day**: Most expensive platform. Verify cleanup thoroughly.
- **Minikube tests need `docker-32gb`**: High memory required for local K8s.
- **No shared library**: Unlike other products, cloud pipelines are self-contained with inline helpers.
- **S3 “skip rerun” marker contract**: many tests write marker objects to `s3://percona-jenkins-artifactory/$JOB_NAME/$GIT_SHORT_COMMIT/` using `PARAMS_HASH`; changing the key format changes rerun behavior.
- **Hetzner conditional labels**: Some pipelines support Hetzner agents via parameter switch.
- **Legacy PGO v1**: 9 legacy pipelines exist for PGO v1 compatibility—separate from current pgo_*.groovy.
- **Orphan cleanup is critical**: Run `aws-functions/*.py` and `gcp-functions/` utilities regularly to avoid cost leaks.

## Related AGENTS.md Files

- `pmm/AGENTS.md` - PMM HA on EKS (`pmm/v3/pmm3-ha-eks.groovy`)
- `vars/AGENTS.md` - Shared helpers (eksctl*, openshift* functions)
- `IaC/AGENTS.md` - CloudFormation for Jenkins infrastructure

## Related GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [percona/percona-xtradb-cluster-operator](https://github.com/percona/percona-xtradb-cluster-operator) | PXC Operator source |
| [percona/percona-server-mongodb-operator](https://github.com/percona/percona-server-mongodb-operator) | MongoDB Operator source |
| [percona/percona-postgresql-operator](https://github.com/percona/percona-postgresql-operator) | PostgreSQL Operator source |
| [percona/percona-server-mysql-operator](https://github.com/percona/percona-server-mysql-operator) | MySQL Operator source |
| [Percona-Lab/percona-dbaas-cli](https://github.com/Percona-Lab/percona-dbaas-cli) | DBaaS CLI tool |
