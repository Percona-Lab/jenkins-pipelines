# AGENTS.md - Shared Library (vars/)

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Shared Groovy library functions used across all Percona Jenkins pipelines. Contains 70+ helper functions for common operations: Molecule testing, Docker setup, AWS operations, version parsing, and infrastructure management.

## Key Categories

### Infrastructure Helpers
- `installDocker()` - Docker installation
- `launchSpotInstance()` - AWS spot instance provisioning
- `cleanUpWS()` - Workspace cleanup

### Molecule Testing
- `moleculeExecuteActionWithScenario()` - Run Molecule scenarios
- `moleculeParallelTest()` - Parallel Molecule execution
- `moleculeParallelPostDestroy()` - Cleanup Molecule environments
- `installMolecule*()` - Molecule installation variants

### Version Helpers
- `pmmVersion()` - PMM version parsing
- `pbmVersion()` - PBM version parsing
- `ppgScenarios()` - PostgreSQL scenario generation

### Build Helpers
- `buildStage()` - Generic build stage wrapper
- `pushArtifactFolder()` - Artifact upload
- `runPython()` - Python script execution

## Usage Pattern

```groovy
// In pipeline
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])

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

## Agent Workflow

1. **Check existing helpers:** Before writing new logic, search `vars/` with `rg "function_name"` to find existing implementations.
2. **Follow conventions:** Helper functions should be self-contained, well-named, and have minimal side effects.
3. **Document parameters:** Add comments for complex parameters; other pipelines depend on stable interfaces.
4. **Test thoroughly:** Changes to shared helpers affect all pipelines - test across multiple products before merging.
5. **Coordinate changes:** Notify affected teams when modifying widely-used helpers.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('vars/helperName.groovy'))"`
- **Usage search:** `rg "helperName" --type groovy` to find all callers before modifying.
- **Jenkins dry-run:** Test changes on a fork or staging job before merging to main library.

## Key Files

| Helper | Purpose | Used By |
|--------|---------|---------|
| `installDocker.groovy` | Docker setup | All products |
| `moleculeExecuteActionWithScenario.groovy` | Molecule testing | PS, PXC, PXB, PSMDB |
| `pmmVersion.groovy` | PMM versioning | PMM pipelines |
| `launchSpotInstance.groovy` | AWS spot instances | Build jobs |
| `pushArtifactFolder.groovy` | S3 uploads | Release jobs |

## Library Branches

```groovy
// Production (master branch)
library identifier: 'lib@master'

// Development/testing
library identifier: 'lib@feature-branch'
```

## Adding New Helpers

1. Create `vars/newHelper.groovy` with a `call()` method
2. Add documentation comments at the top
3. Test in isolation before integrating
4. Update this AGENTS.md if adding a major new category

# Jenkins

The shared library is loaded via `@Library('jenkins-pipelines@master')`.

## CLI
```bash
# Search for helper usage across all pipelines
rg -l 'helperName(' --glob '*.groovy'

# Find helper definition
rg -n 'def call' vars/helperName.groovy

# List all helper functions
ls vars/*.groovy | xargs -I{} basename {} .groovy
```

## Library
```groovy
@Library('jenkins-pipelines@master') _   // Production
@Library('jenkins-pipelines@feature') _  // Development/testing
```

## Credentials
Helpers assume the caller wrapped steps with `withCredentials`. Do not reference Jenkins credential IDs directly inside `vars/` unless the helper exists solely to model that credential (e.g., `moleculePbmJenkinsCreds()`).
