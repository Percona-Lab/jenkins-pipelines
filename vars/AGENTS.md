# AGENTS.md - Shared Library (`vars/`)

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Groovy shared library functions automatically available to every Jenkins pipeline that imports the library. Changes here affect all products (PMM, PXC, PS, etc.), so treat updates as breaking API changes.

## How the shared library works

```groovy
library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
])

// Functions in vars/ become global steps
pmmVersion('dev-latest')
runPython('script_name', 'args')
installDocker()
```

## Key Function Groups

- **Version helpers:** `pmmVersion.groovy`, `pbmVersion.groovy`
- **Environment setup:** `installDocker.groovy`, `installMolecule*.groovy`, `installHelm.groovy`
- **Python integration:** `runPython.groovy` (executes scripts from `resources/`)
- **Cloud operations:** `launchSpotInstance.groovy`, `awsCertificates.groovy`, `openshiftCluster.groovy`, `eksCluster.groovy`
- **Caching:** `ccacheDownload.groovy`, `ccacheUpload.groovy`
- **Testing helpers:** `moleculeExecuteActionWithScenario*.groovy`, `checkRPM.groovy`

## Agent Workflow

1. **Search before coding:** `rg -n "functionName" vars/` to ensure you aren’t duplicating existing helpers.
2. **Assess consumers:** Use `rg -n "helperName(" -g '*.groovy'` across the repo to understand how widely a helper is used before making breaking changes.
3. **Keep functions idempotent:** Jenkins pipelines call helpers multiple times; guard against repeated installation or cleanup steps.
4. **Parameter hygiene:** Provide sensible defaults and document optional parameters with inline comments. Avoid positional arguments when a map would be clearer.
5. **Return structured data:** Prefer maps/objects instead of strings when returning complex results so pipelines can branch on them.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('vars/installMolecule.groovy'))"`
- **Unit-style smoke:** Add temporary pipelines under `sandbox/` to call new helpers, or reuse existing Jenkins jobs targeting your branch by overriding `library identifier: 'lib@<branch>'`.
- **Python helpers:** When a Groovy helper shells out to Python (`runPython`), also `python3 -m py_compile` the scripts it invokes.
- **Documentation:** Update the relevant `AGENTS.md` or README when adding new helpers so consumers know how to call them.

## Boundaries

- **Ask first:** Creating brand-new helpers, changing function signatures, or adding external dependencies (pip/npms) requires coordination with product teams.
- **Never:** Hardcode credentials, silent-catch exceptions, or run destructive commands outside the workspace.
- **Deprecation policy:** Introduce new helpers alongside existing ones, mark the old helper as deprecated in comments, and remove only after consumers migrate.

## Creating new functions

### File naming

```
vars/myFunction.groovy  ->  myFunction('arg')
```

### Function structure

```groovy
// vars/myFunction.groovy
def call(String arg1, String arg2 = 'default') {
    // Implementation
    sh "echo ${arg1} ${arg2}"
}
```

### Best practices

1. Use descriptive function names.
2. Provide default values for optional parameters.
3. Document inputs/outputs with concise comments.
4. Keep functions focused (single responsibility).
5. Handle errors with `try/catch` and rethrow with actionable messages.

## Testing changes

1. Create a test pipeline (in a fork or separate Jenkins job).
2. Load the shared library from your feature branch:
   ```groovy
   library identifier: 'lib@feature/my-helper', retriever: modernSCM([...])
   ```
3. Exercise new helpers across at least one PMM/PXC/PS pipeline before merging.

# Jenkins

The shared library is loaded via `@Library('jenkins-pipelines@hetzner')`.

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
@Library('jenkins-pipelines@hetzner') _  // Hetzner branch
@Library('jenkins-pipelines@master') _   // Legacy jobs
```

## Credentials
Helpers assume the caller wrapped steps with `withCredentials`. Do not reference Jenkins credential IDs directly inside `vars/` unless the helper exists solely to model that credential (e.g., `moleculePbmJenkinsCreds()`).
