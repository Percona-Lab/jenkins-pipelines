# AGENTS.md - PMM v3 Pipelines (Hetzner Branch)

Extends: [../AGENTS.md](../AGENTS.md) → [../../AGENTS.md](../../AGENTS.md)

## Scope

PMM v3 pipelines for Hetzner infrastructure: AMI builds for AWS deployment, OVF image generation for VM environments, upgrade testing (2.x → 3.x), package validation, client autobuild (amd64/arm64), comprehensive UI/API/CLI test suites, and legacy AWS staging environment management.

Note: Hetzner branch does NOT include EKS HA testing or OpenShift Helm testing (removed for Hetzner-optimized infrastructure).

## Key File Categories

### AMI (Amazon Machine Image) Jobs
- `pmm3-ami.groovy` / `pmm3-release-ami.groovy` – AMI image builds
- `pmm3-ami-test.groovy` – AMI validation
- `pmm3-ami-upgrade-tests.groovy` / `pmm3-ami-upgrade-tests-matrix.groovy` – Upgrade validation
- `pmm3-ami-staging-start.groovy` / `pmm3-ami-staging-stop.groovy` – Staging lifecycle

### OVF (Open Virtualization Format) Jobs
- `pmm3-ovf.groovy` – OVF image builds
- `pmm3-ovf-image-test.groovy` – OVF validation
- `pmm3-ovf-upgrade-tests.groovy` / `pmm3-ovf-upgrade-tests-matrix.groovy` – OVF upgrades
- `pmm3-ovf-staging-start.groovy` / `pmm3-ovf-staging-stop.groovy` – Staging lifecycle

### Client Builds (Multi-Architecture)
- `pmm3-client-autobuild.groovy` – Generic client builds
- `pmm3-client-autobuild-amd.groovy` – AMD64/x86_64 clients
- `pmm3-client-autobuild-arm.groovy` – ARM64/aarch64 clients

### Server & Watchtower
- `pmm3-server-autobuild.groovy` – PMM Server autobuild
- `pmm3-watchtower-autobuild.groovy` – Watchtower component

### Testing Suites
- `pmm3-ui-tests.groovy` / `pmm3-ui-tests-matrix.groovy` / `pmm3-ui-tests-nightly.groovy` – UI testing
- `pmm3-api-tests.groovy` – API endpoint testing
- `pmm3-cli-tests.groovy` – CLI tool testing
- `pmm3-testsuite.groovy` – Comprehensive test suite
- `pmm3-migration-tests.groovy` – PMM 2.x → 3.x migration

### Package Testing
- `pmm3-package-testing.groovy` / `pmm3-package-tests-matrix.groovy` – Package validation

### Upgrade Testing
- `pmm3-upgrade-tests.groovy` / `pmm3-upgrade-tests-matrix.groovy` – Upgrade validation

### Release & Submodule Management
- `pmm3-release.groovy` / `pmm3-release-candidate.groovy` – Release orchestration
- `pmm3-release-tests.groovy` – Pre-release validation
- `pmm3-submodules.groovy` / `pmm3-submodules-rewind.groovy` – Git submodules
- `pmm3-rewind-submodules-fb.groovy` – Feature branch submodule rewinding

### Security & Staging
- `pmm3-image-scanning.groovy` – Security vulnerability scanning
- `pmm3-aws-staging-start.groovy` – AWS staging environment
- `pmm3-aws-staging-start-old.groovy` – Legacy AWS staging (Hetzner addition)

## Product-Specific Patterns

### PMM v3 Version Handling

```groovy
version: '3.x.x'
upgradeFrom: '2.x.x'  // Supports 2.x → 3.x migration
```

### Multi-Architecture Client Builds

```groovy
// Separate jobs for different architectures
job: 'pmm3-client-autobuild-amd'  // x86_64
job: 'pmm3-client-autobuild-arm'  // aarch64
```

### Upgrade Testing Matrix

```groovy
upgradeMatrix: [
    '2.39.0 → 3.0.0',
    '2.40.0 → 3.0.0',
    '2.41.0 → 3.1.0',
    // Comprehensive version coverage
]
```

## Agent Workflow

1. **Inspect PMM v3 jobs:** `~/bin/jenkins job pmm config pmm3-ami --yaml` to capture parameters like `PMM_VERSION`, `AMI_ID`, `OVF_VERSION`.

2. **AMI/OVF lifecycle:**
   - AMI and OVF jobs follow: build → test → staging start → testing → staging stop
   - Staging environments must be stopped to avoid costs
   - Use matrix jobs for comprehensive version coverage

3. **Multi-architecture considerations:**
   - Update both AMD and ARM client variants together
   - Test client compatibility across architectures
   - ARM builds support Apple Silicon and ARM servers

4. **Upgrade path validation:**
   - PMM v3 supports upgrades from PMM 2.x
   - Upgrade tests validate data migration and configuration
   - Matrix jobs cover all supported 2.x versions

5. **No EKS/OpenShift in Hetzner:**
   - EKS HA jobs removed (pmm3-ha-eks*)
   - OpenShift Helm tests removed
   - GSSAPI UI tests removed
   - Upgrade test runner removed
   - Use legacy AWS staging instead

## Validation & Testing

```bash
# Groovy validation
groovy -e "new GroovyShell().parse(new File('pmm/v3/pmm3-ami.groovy'))"

# AMI testing
aws ec2 describe-images --image-ids ami-xxxxx

# Client testing
pmm-admin --version
pmm-admin status

# Jenkins dry-run
~/bin/jenkins build pmm/pmm3-ami \
  -p PMM_VERSION=3.1.0 \
  --watch
```

## Credentials & Parameters

- **Credentials:**
  - `pmm-aws` – AMI operations
  - `pmm-staging-slave` – Staging access
  - `aws-jenkins` – Server access
  - `hub.docker.com` – Docker publishing

- **Key parameters:**
  - `PMM_VERSION` – PMM v3 version
  - `PMM_BRANCH` – Git branch
  - `AMI_ID` – Amazon Machine Image ID
  - `OVF_VERSION` – OVF image version
  - `ENABLE_TESTING` – Test execution toggle
  - `CLEANUP` – Resource cleanup toggle

## Jenkins CLI

Instance: `pmm` | URL: `https://pmm.cd.percona.com`

```bash
# jenkins CLI
~/bin/jenkins job pmm list | grep pmm3              # All v3 jobs
~/bin/jenkins job pmm list | grep 'pmm3-ami'        # AMI builds/tests
~/bin/jenkins job pmm list | grep 'pmm3-ovf'        # OVF builds/tests
~/bin/jenkins params pmm/pmm3-ami                   # Parameters
~/bin/jenkins build pmm/pmm3-ami -p PMM_BRANCH=main # Build

# curl (see root AGENTS.md for auth)
curl -su "USER:TOKEN" "https://pmm.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(startswith("pmm3"))'
```

Job patterns: `pmm3-ami*`, `pmm3-ovf*`, `pmm3-client*`, `pmm3-*-upgrade*`, `pmm3-ui*`

## Job Inventory

**Total v3 Jobs:** 40 | **Groovy Files:** 38 | **Coverage:** 95%

### Complete Job-to-File Mapping

#### AMI Jobs (6 jobs, 6 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-ami | `pmm3-ami.groovy` | Active | PMM_BRANCH, PMM_SERVER_IMAGE, RELEASE_CANDIDATE |
| pmm3-ami-staging-start | `pmm3-ami-staging-start.groovy` | Active | DOCKER_VERSION, CLIENT_VERSION, DAYS |
| pmm3-ami-staging-stop | `pmm3-ami-staging-stop.groovy` | Active | INSTANCE_ID, TERMINATE |
| pmm3-ami-test | `pmm3-ami-test.groovy` | Active | AMI_ID, INSTANCE_TYPE, AWS_REGION |
| pmm3-ami-upgrade-tests | `pmm3-ami-upgrade-tests.groovy` | Failed | PMM_VERSION, UPGRADE_FROM |
| pmm3-ami-upgrade-tests-matrix | `pmm3-ami-upgrade-tests-matrix.groovy` | Failed | Matrix parameters |

#### OVF Jobs (6 jobs, 6 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-ovf | `pmm3-ovf.groovy` | Active | PMM_BRANCH, OVF_VERSION |
| pmm3-ovf-staging-start | `pmm3-ovf-staging-start.groovy` | Active | DOCKER_VERSION |
| pmm3-ovf-staging-stop | `pmm3-ovf-staging-stop.groovy` | Active | OVF_PATH, CLEANUP |
| pmm3-ovf-image-test | `pmm3-ovf-image-test.groovy` | Active | OVF_ID, TEST_SUITE |
| pmm3-ovf-upgrade-tests | `pmm3-ovf-upgrade-tests.groovy` | Failed | UPGRADE_FROM, OVF_VERSION |
| pmm3-ovf-upgrade-tests-matrix | `pmm3-ovf-upgrade-tests-matrix.groovy` | Failed | Matrix parameters |

#### Client Builds (3 jobs, 3 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-client-autobuild | `pmm3-client-autobuild.groovy` | Failed | GIT_BRANCH, BUILD_TYPE |
| pmm3-client-autobuild-amd | `pmm3-client-autobuild-amd.groovy` | Failed | GIT_BRANCH, CLIENT_VERSION |
| pmm3-client-autobuild-arm | `pmm3-client-autobuild-arm.groovy` | Aborted | GIT_BRANCH, CLIENT_VERSION |

#### Server & Components (2 jobs, 2 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-server-autobuild | `pmm3-server-autobuild.groovy` | Active | PMM_BRANCH, DOCKER_TAG |
| pmm3-watchtower-autobuild | `pmm3-watchtower-autobuild.groovy` | Active | WATCHTOWER_BRANCH, VERSION |

#### Testing Suites (8 jobs, 8 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-ui-tests | `pmm3-ui-tests.groovy` | Active | GIT_BRANCH, DOCKER_VERSION, TAG |
| pmm3-ui-tests-matrix | `pmm3-ui-tests-matrix.groovy` | Active | Matrix parameters |
| pmm3-ui-tests-nightly | `pmm3-ui-tests-nightly.groovy` | Failed | GIT_BRANCH, DOCKER_VERSION |
| pmm3-api-tests | `pmm3-api-tests.groovy` | Active | DOCKER_VERSION, TEST_SUITE |
| pmm3-cli-tests | `pmm3-cli-tests.groovy` | Active | DOCKER_VERSION, CLI_VERSION |
| pmm3-testsuite | `pmm3-testsuite.groovy` | Active | DOCKER_VERSION |
| pmm3-migration-tests | `pmm3-migration-tests.groovy` | Active | FROM_VERSION, TO_VERSION |
| pmm3-release-tests | `pmm3-release-tests.groovy` | Active | PMM_VERSION |

#### Package Testing (4 jobs, 2 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-package-testing | `pmm3-package-testing.groovy` | Failed | GIT_BRANCH, PMM_VERSION, TESTS |
| pmm3-package-tests-matrix | `pmm3-package-tests-matrix.groovy` | Failed | Matrix parameters |
| pmm3-package-testing-arm | (no file) | Failed | ARM64 variant |
| pmm3-package-testing-arm-matrix | (no file) | Active | ARM64 matrix |

#### Upgrade Testing (4 jobs, 2 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-upgrade-tests | `pmm3-upgrade-tests.groovy` | Failed | PMM_VERSION, UPGRADE_FROM |
| pmm3-upgrade-tests-matrix | `pmm3-upgrade-tests-matrix.groovy` | Failed | Matrix parameters |
| pmm3-upgrade-ovf-test-runner | (no file) | Failed | OVF upgrade runner |
| pmm3-upgrade-ami-test-runner | (no file) | Failed | AMI upgrade runner |

#### Release & Submodules (6 jobs, 6 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-release | `pmm3-release.groovy` | Failed | PMM_VERSION, BRANCH |
| pmm3-release-candidate | `pmm3-release-candidate.groovy` | Active | PMM_VERSION, RC_NUMBER |
| pmm3-submodules | `pmm3-submodules.groovy` | Active | GIT_BRANCH, UPDATE_DEPTH |
| pmm3-submodules-rewind | `pmm3-submodules-rewind.groovy` | Yellow | GIT_BRANCH |
| pmm3-rewind-submodules-fb | `pmm3-rewind-submodules-fb.groovy` | Yellow | FEATURE_BRANCH |
| pmm3-image-scanning | `pmm3-image-scanning.groovy` | Active | DOCKER_IMAGE, SCAN_TYPE |

#### AWS Staging (2 jobs, 2 files)

| Job | File | Status | Key Parameters |
|-----|------|--------|----------------|
| pmm3-aws-staging-start | `pmm3-aws-staging-start.groovy` | Active | DOCKER_VERSION, CLIENT_VERSION |
| pmm3-aws-staging-start-old | `pmm3-aws-staging-start-old.groovy` | Active | Legacy staging (Hetzner) |

### Jobs Without Source Files (Jenkins-Only)

These jobs exist in Jenkins but were removed from Hetzner branch:
- `pmm3-ha-eks` - EKS HA cluster testing
- `pmm3-ha-eks-test` - EKS HA validation
- `pmm3-ha-eks-cleanup` - EKS cleanup (disabled)
- `pmm3-ha-rosa-test` - ROSA cluster testing
- `pmm3-openshift-helm-tests` - OpenShift Helm
- `pmm3-ui-tests-nightly-gssapi` - GSSAPI auth tests
- `pmm3-update-labels` - Jenkins admin job

## Related Jobs

- `pmm2-*` – PMM 2.x jobs (upgrade source)
- `pmm3-migration-tests` – Migration validation
- Cloud infrastructure (no Kubernetes/OpenShift in hetzner)

## Code Owners

- PMM team: `@ademidoff`, `@puneet0191`
- Infrastructure: `@evgeniypatlan`

## Hetzner Branch Notes

### Not in Hetzner (Removed from Master):
- ❌ EKS high-availability testing (pmm3-ha-eks, pmm3-ha-eks-cleanup)
- ❌ OpenShift Helm testing (pmm3-openshift-helm-tests)
- ❌ GSSAPI UI tests (pmm3-ui-tests-nightly-gssapi)
- ❌ Upgrade test runner (pmm3-upgrade-test-runner)

### Hetzner Additions:
- ✅ Legacy AWS staging (pmm3-aws-staging-start-old)

### Common (Both Branches):
- AMI builds and testing
- OVF builds and testing
- Client autobuilds (amd64/arm64)
- Server autobuilds
- API/CLI/UI testing
- Package testing
- Upgrade testing (matrix)
- Release orchestration
