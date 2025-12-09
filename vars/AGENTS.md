# AGENTS.md - Shared Library (vars/)

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: 107 shared Groovy helpers used by ~250 pipelines across all products
**Where**: Loaded via `@Library('jenkins-pipelines@master')` in every pipeline
**Key Categories**: Molecule testing, Docker setup, AWS operations, version parsing
**Watch Out**: Changes affect ALL pipelines - test thoroughly; search for existing helpers before creating new ones

## Quick Reference

| Key | Value |
|-----|-------|
| Helper Count | 107 |
| Pipelines Using | ~250 |
| Top User | ppg (65 pipelines) |
| Load Syntax | `@Library('jenkins-pipelines@master') _` |
| Lint Command | `groovy -e "new GroovyShell().parse(...)"` |

## Scope

Shared Groovy library functions used across all Percona Jenkins pipelines. Contains **107 helper functions** for common operations: Molecule testing, Docker setup, AWS operations, version parsing, and infrastructure management.

## Library Usage by Product

| Product | Pipelines | Notes |
|---------|-----------|-------|
| **ppg** | 65 | PostgreSQL - heaviest user |
| **psmdb** | 46 | MongoDB ecosystem |
| **pmm** | 35 | PMM monitoring |
| **ps** | 23 | Percona Server MySQL |
| **pbm** | 19 | MongoDB backup |
| **pxc** | 14 | XtraDB Cluster |
| **pxb** | 10 | XtraBackup |
| **pdps** | 10 | Distribution: PS |
| **pdpxc** | 9 | Distribution: PXC |
| **pdmdb** | 9 | Distribution: MongoDB |

**Total**: ~250 pipelines load this library

## Key Helper Categories

### Molecule Testing
```groovy
// Run Molecule scenario
moleculeExecuteActionWithScenario(params)

// Parallel execution
moleculeParallelTest()

// ALWAYS cleanup
moleculeParallelPostDestroy()

// Installation variants
installMolecule()
installMoleculeES()
installMoleculeVagrant()

// Product-specific credentials
moleculePbmJenkinsCreds()    // PBM
moleculePdpsJenkinsCreds()   // PDPS
```

### AWS Operations
```groovy
// Spot instances (70% cost savings)
launchSpotInstance(
    instanceType: 'm5.large',
    spotPrice: '0.10'
)

// S3 artifacts
pushArtifactFolder()
pushArtifactS3()

// AWS certs
awsCertificates()
```

### OpenShift/EKS
```groovy
// OpenShift cluster lifecycle (1000+ lines)
openshiftCluster()
openshiftDiscovery()
openshiftS3()
openshiftSSL()
openshiftTools()

// EKS helpers
eksctlCreateCluster()
eksctlDeleteCluster()
```

### Docker
```groovy
installDocker()
buildDockerImage()
pushDockerImage()
```

### Version Helpers
```groovy
pmmVersion('dev-latest')    // PMM
pbmVersion()                // PBM
ppgScenarios()              // PostgreSQL
```

### Build Helpers
```groovy
buildStage()
runPython('script_name', 'args')
```

## Usage Pattern

```groovy
@Library('jenkins-pipelines@master') _

pipeline {
    stages {
        stage('Setup') {
            steps {
                installDocker()
                script {
                    def version = pmmVersion('dev-latest')
                }
            }
        }
    }
    post {
        always {
            moleculeParallelPostDestroy()
            deleteDir()
        }
    }
}
```

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| Creating duplicate helper | Already exists in vars/ | Search first: `rg "funcName" vars/` |
| Not testing across products | Breaks unrelated pipelines | Test on multiple products |
| Changing helper signature | Breaks callers | Add new params with defaults |
| Hardcoding credentials | Security risk | Use `withCredentials` wrapper |
| Missing cleanup | Resources leak | Add cleanup in `post.always` |

## Agent Workflow

1. **Search first**: `rg "function_name" vars/` before writing new logic
2. **Follow conventions**: Self-contained, well-named, minimal side effects
3. **Document params**: Add comments for complex parameters
4. **Test thoroughly**: Changes affect ALL pipelines
5. **Coordinate**: Notify teams when modifying widely-used helpers

## PR Review Checklist

- [ ] No duplicate functionality in existing helpers
- [ ] Backward compatible (new params have defaults)
- [ ] Tested on multiple products
- [ ] Comments for complex parameters
- [ ] No hardcoded credentials
- [ ] Cleanup logic included if creating resources

## Change Impact

| Change Type | Impact | Notification |
|-------------|--------|--------------|
| New helper | Safe (additive) | Document in AGENTS.md |
| Modify signature | Breaks callers | All teams |
| Bug fix | May change behavior | Test thoroughly |
| Delete helper | Breaks callers | Never do |

## Adding New Helpers

```groovy
// vars/newHelper.groovy
/**
 * Description of what this helper does
 * @param param1 Description of param1
 * @param param2 Description of param2 (optional, default: 'value')
 */
def call(String param1, String param2 = 'value') {
    // Implementation
}
```

1. Create `vars/newHelper.groovy` with `call()` method
2. Add documentation comments
3. Test in isolation
4. Test on multiple products
5. Update this AGENTS.md

## Key Files

| Helper | Lines | Purpose | Used By |
|--------|-------|---------|---------|
| `openshiftCluster.groovy` | 1051 | OpenShift lifecycle | cloud |
| `awsCertificates.groovy` | 718 | AWS certs | builds |
| `openshiftSSL.groovy` | 568 | SSL setup | cloud |
| `openshiftTools.groovy` | 576 | Tools setup | cloud |
| `openshiftS3.groovy` | 491 | S3 integration | cloud |
| `openshiftDiscovery.groovy` | 426 | Discovery | cloud |
| `launchSpotInstance.groovy` | 179 | Spot instances | builds |
| `pmmVersion.groovy` | 108 | PMM versions | pmm |

## Library Branches

```groovy
// Production (default)
@Library('jenkins-pipelines@master') _

// Feature branch testing
@Library('jenkins-pipelines@feature-branch') _

// Alternative syntax
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])
```

## Validation

```bash
# Lint helper
groovy -e "new GroovyShell().parse(new File('vars/helperName.groovy'))"

# Find all callers
rg -l 'helperName(' --glob '*.groovy'

# Find definition
rg -n 'def call' vars/helperName.groovy

# List all helpers
ls vars/*.groovy | wc -l
```

## Jenkins CLI

```bash
# Search for helper usage
rg -l 'helperName(' --glob '*.groovy'

# Count callers
rg -c 'helperName(' --glob '*.groovy' | sort -t: -k2 -rn

# Find helper with pattern
fd -e groovy . vars/ | xargs grep -l "pattern"
```

## Credentials

Helpers assume the caller wraps steps with `withCredentials`. Exception: credential-modeling helpers like:
- `moleculePbmJenkinsCreds()` - PBM SSH keys
- `moleculePdpsJenkinsCreds()` - PDPS SSH keys

## Related

- Root `AGENTS.md` - Repository-wide patterns
- All product `AGENTS.md` files - Product-specific helpers usage
