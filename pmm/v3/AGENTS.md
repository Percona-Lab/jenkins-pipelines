# AGENTS.md - PMM v3 Pipelines

Extends: [../AGENTS.md](../AGENTS.md) → [../../AGENTS.md](../../AGENTS.md)

## Scope

PMM v3 specific pipelines: High-availability testing on EKS clusters, AMI builds for AWS deployment, OVF image generation for VM environments, upgrade testing (2.x → 3.x), package validation, Helm chart testing on OpenShift, client autobuild (amd64/arm64), and comprehensive UI/API/CLI test suites.

## Key File Categories

### AMI (Amazon Machine Image) Jobs
- `pmm3-ami.groovy` / `pmm3-release-ami.groovy` – AMI image builds
- `pmm3-ami-test.groovy` – AMI validation testing
- `pmm3-ami-upgrade-tests.groovy` / `pmm3-ami-upgrade-tests-matrix.groovy` – Upgrade path validation
- `pmm3-ami-staging-start.groovy` / `pmm3-ami-staging-stop.groovy` – Staging environment lifecycle

### High Availability & EKS (Master Branch Only)
- `pmm3-ha-eks.groovy` – EKS high-availability cluster testing
- `pmm3-ha-eks-cleanup.groovy` – EKS cluster cleanup automation

Note: These HA/EKS jobs do NOT exist in hetzner branch.

### OpenShift & Helm (Master Branch Only)
- `pmm3-openshift-helm-tests.groovy` – OpenShift Helm chart testing

Note: OpenShift support removed in hetzner branch.

### OVF (Open Virtualization Format) Jobs
- `pmm3-ovf.groovy` – OVF image builds for VM deployment
- `pmm3-ovf-image-test.groovy` – OVF validation testing
- `pmm3-ovf-upgrade-tests.groovy` / `pmm3-ovf-upgrade-tests-matrix.groovy` – OVF upgrade validation
- `pmm3-ovf-staging-start.groovy` / `pmm3-ovf-staging-stop.groovy` – Staging environment lifecycle

### Client Builds (Multi-Architecture)
- `pmm3-client-autobuild.groovy` – Generic client builds
- `pmm3-client-autobuild-amd.groovy` – AMD64/x86_64 client builds
- `pmm3-client-autobuild-arm.groovy` – ARM64/aarch64 client builds

### Server & Watchtower
- `pmm3-server-autobuild.groovy` – PMM Server autobuild
- `pmm3-watchtower-autobuild.groovy` – Watchtower component autobuild

### Testing Suites
- `pmm3-ui-tests.groovy` / `pmm3-ui-tests-matrix.groovy` / `pmm3-ui-tests-nightly.groovy` – UI testing
- `pmm3-ui-tests-nightly-gssapi.groovy` – GSSAPI authentication UI testing (Master Branch Only)
- `pmm3-api-tests.groovy` – API endpoint testing
- `pmm3-cli-tests.groovy` – CLI tool testing
- `pmm3-testsuite.groovy` – Comprehensive test suite
- `pmm3-migration-tests.groovy` – PMM 2.x → 3.x migration validation

### Package Testing
- `pmm3-package-testing.groovy` / `pmm3-package-tests-matrix.groovy` – Package installation validation

### Upgrade Testing (Master Branch Only)
- `pmm3-upgrade-tests.groovy` / `pmm3-upgrade-tests-matrix.groovy` – Upgrade validation matrix
- `pmm3-upgrade-test-runner.groovy` – Upgrade test orchestration

Note: Upgrade test runner removed in hetzner branch.

### Release & Submodule Management
- `pmm3-release.groovy` / `pmm3-release-candidate.groovy` – Release orchestration
- `pmm3-release-tests.groovy` – Pre-release validation
- `pmm3-submodules.groovy` / `pmm3-submodules-rewind.groovy` – Git submodule management
- `pmm3-rewind-submodules-fb.groovy` – Feature branch submodule rewinding

### Security & Staging
- `pmm3-image-scanning.groovy` – Security vulnerability scanning
- `pmm3-aws-staging-start.groovy` – AWS staging environment (Master Branch Only)

## Product-Specific Patterns

### PMM v3 Version Handling

```groovy
// PMM v3 versions follow semantic versioning
version: '3.x.x'
upgradeFrom: '2.x.x'  // Supports 2.x → 3.x migration
```

### EKS High Availability Pattern (Master Branch Only)

```groovy
// EKS cluster creation with HA configuration
eksCluster.create(
    clusterName: params.CLUSTER_NAME,
    region: params.AWS_REGION,
    nodeCount: 3,  // HA requires minimum 3 nodes
    haEnabled: true
)

// Always cleanup in post block
post {
    always {
        eksCluster.cleanup(params.CLUSTER_NAME)
    }
}
```

### Multi-Architecture Client Builds

```groovy
// Separate jobs for amd64 and arm64
// Supports cross-platform PMM client deployment
job: 'pmm3-client-autobuild-amd'  // x86_64
job: 'pmm3-client-autobuild-arm'  // aarch64
```

### Upgrade Testing Matrix

```groovy
// Test all upgrade paths
upgradeMatrix: [
    '2.39.0 → 3.0.0',
    '2.40.0 → 3.0.0',
    '2.41.0 → 3.1.0',
    // ... comprehensive version coverage
]
```

## Agent Workflow

1. **Inspect PMM v3 jobs:** `~/bin/jenkins job pmm config pmm3-ha-eks --yaml` to capture v3-specific parameters like `PMM_VERSION`, `CLUSTER_NAME`, `AMI_ID`, `EKS_REGION`.

2. **EKS HA workflow (Master Branch Only):**
   - Always pair `pmm3-ha-eks` with `pmm3-ha-eks-cleanup`
   - Ensure cleanup runs even on failure (use `post { always { ... } }`)
   - EKS clusters are expensive; validate cleanup logic thoroughly

3. **AMI/OVF lifecycle:**
   - AMI and OVF jobs follow: build → test → staging start → testing → staging stop
   - Staging environments must be stopped to avoid cost accumulation
   - Use matrix jobs for comprehensive version coverage

4. **Multi-architecture considerations:**
   - When updating client builds, modify both AMD and ARM variants
   - Test client compatibility across architectures
   - ARM builds support Apple Silicon and ARM servers

5. **Upgrade path validation:**
   - PMM v3 supports upgrades from PMM 2.x (not 1.x)
   - Upgrade tests validate data migration, configuration preservation, and functionality
   - Matrix jobs cover all supported 2.x versions

## Validation & Testing

```bash
# Groovy syntax validation
groovy -e "new GroovyShell().parse(new File('pmm/v3/pmm3-ha-eks.groovy'))"

# EKS cluster validation (Master Branch Only)
eksctl get cluster --name pmm3-test-cluster
kubectl get nodes

# AMI testing
aws ec2 describe-images --image-ids ami-xxxxx

# OVF validation
# Import OVF into VMware/VirtualBox
# Verify PMM v3 boots and functions

# Client testing
pmm-admin --version  # Verify client version
pmm-admin status     # Verify connection to server

# Jenkins dry-run (EKS HA - Master Branch Only)
~/bin/jenkins build pmm/pmm3-ha-eks \
  -p CLUSTER_NAME=test-ha-cluster \
  -p PMM_VERSION=3.1.0 \
  -p EKS_REGION=us-east-2 \
  --watch
```

## Credentials & Parameters

- **Credentials:**
  - `pmm-aws` (AWS) – EKS cluster management, AMI operations
  - `pmm-staging-slave` (AWS) – Staging environment access
  - `aws-jenkins` (SSH) – Server access
  - `hub.docker.com` (Docker) – Container image publishing

- **Key parameters:**
  - `PMM_VERSION` – PMM v3 version (e.g., '3.1.0')
  - `PMM_BRANCH` – Git branch (e.g., 'v3', 'main')
  - `CLUSTER_NAME` – EKS cluster identifier (Master Branch Only)
  - `EKS_REGION` – AWS region for EKS (e.g., 'us-east-2') (Master Branch Only)
  - `AMI_ID` – Amazon Machine Image identifier
  - `OVF_VERSION` – OVF image version
  - `ENABLE_TESTING` – Boolean for test execution
  - `CLEANUP` – Boolean for resource cleanup (critical for cost control)

## Jenkins Instance

PMM v3 jobs run on: `pmm.cd.percona.com`

```bash
# List PMM v3 jobs
~/bin/jenkins job pmm list | grep pmm3

# Get job parameters
~/bin/jenkins job pmm params pmm3-ha-eks

# Check build status
~/bin/jenkins job pmm status pmm3-server-autobuild
```

## Related Jobs

- `pmm2-*` – PMM 2.x jobs (upgrade source)
- `pmm3-migration-tests` – Validates 2.x → 3.x migration
- `cloud/pmm-*` – Kubernetes/cloud infrastructure
- OpenShift Helm testing (Master Branch Only)

## Code Owners

See `.github/CODEOWNERS` – PMM v3 pipelines maintained by:
- PMM team: `@ademidoff`, `@puneet0191`
- Infrastructure: `@evgeniypatlan`

Primary contact: `@pmm-team`

## Branch-Specific Notes

### Master Branch Includes:
- EKS high-availability testing (`pmm3-ha-eks`, `pmm3-ha-eks-cleanup`)
- OpenShift Helm testing (`pmm3-openshift-helm-tests`)
- GSSAPI authentication UI tests (`pmm3-ui-tests-nightly-gssapi`)
- Upgrade test runner (`pmm3-upgrade-test-runner`)
- AWS staging start (`pmm3-aws-staging-start`)

### Hetzner Branch Excludes:
- All EKS HA jobs (removed)
- All OpenShift jobs (removed)
- GSSAPI UI tests (removed)
- Upgrade test runner (removed)
- Adds alternative staging (`pmm3-aws-staging-start-old`)

### Common (Both Branches):
- AMI builds and testing
- OVF builds and testing
- Client autobuilds (amd64/arm64)
- Server autobuilds
- API/CLI testing
- Package testing
- Release orchestration
