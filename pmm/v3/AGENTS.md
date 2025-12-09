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

## Jenkins Instance

PMM v3 jobs run on: `pmm.cd.percona.com`

```bash
~/bin/jenkins job pmm list | grep pmm3
```

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
