# AGENTS.md Template

Template for creating product-specific AGENTS.md files in jenkins-pipelines repository.

---

# AGENTS.md - [Product Name] Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: [Brief description - builds, testing, purpose]
**Where**: Jenkins `[instance]` | `https://[instance].cd.percona.com` | Jobs: `[pattern]*`
**Key Helpers**: [Top 2-3 vars/ functions used]
**Watch Out**: [Critical warnings - costs, downstream dependencies, cleanup requirements]

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `[instance]` |
| URL | https://[instance].cd.percona.com |
| Job Patterns | `[prefix]*`, `[pattern]-*` |
| Default Credential | `[credential-helper]()` or `[aws-cred-id-partial]*` |
| AWS Region | `us-east-2` or product-specific |
| Groovy Files | [count] (jenkins/) + [count] (v2/) |
| Last Updated | 2025-12 |

## Job Dependency Graph

```
[parent-job].groovy (orchestrator, fanout: [N])
   │
   ├── [child-job-1] ([description])
   ├── [child-job-2] ([description])
   └── [child-job-3] ([description])

[standalone-job].groovy (standalone)
   │
   └── [Description of execution pattern]
```

**Fanout Pattern**: [Describe parallel/sequential execution]
**Triggers**: [Cron, upstream, manual]

## Directory Map

```
[product]/                              # [total-lines] lines total
├── AGENTS.md                           # This file
├── jenkins/                            # [count] files
│   ├── [file1].groovy          ([lines]) # [Purpose]
│   ├── [file2].groovy          ([lines]) # [Purpose]
│   └── [fileN].groovy          ([lines]) # [Purpose]
├── [other-dirs]/                       # [Description]
└── *.yml                               # JJB configs
```

## Key Jobs from Jenkins

| Job | Builds | Status | Purpose |
|-----|--------|--------|---------|
| `[job-name]` | [count] | [STATUS] | [Description] |
| `[job-name]` | - | [STATUS] | [Description] |

## Version Matrix

| Version | Status | ARM64 | Pipeline |
|---------|--------|-------|----------|
| [X.Y] | [Active/LTS/Maintenance] | [Yes/No] | `[file].groovy` |

## Credentials

| ID | Purpose | Used In |
|----|---------|---------|
| `[partial-id]*` | [Description] | [jobs] |
| `[CREDENTIAL_NAME]` | [Description] | [jobs] |

## Agent Labels

| Label | Purpose | Files |
|-------|---------|-------|
| `[label-name]` | [Description] | [files] |

## Key Jira Tickets

| Ticket | Summary | Status |
|--------|---------|--------|
| [PROJ-NNN] | [Description] | [Done/Open/etc] |

## Current Initiatives

### [Initiative Name] ([Ticket])
- [Key point 1]
- [Key point 2]

### [Initiative Name 2]
- [Key point 1]

## [Product-Specific Section]

Add product-specific sections as needed:
- Backup Coverage (for backup tools)
- Platform Matrix (for multi-platform products)
- Components Tested (for distributions)
- Storage Backends (for backup/storage tools)
- Compatibility Matrix (for tools with version dependencies)

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| [Anti-pattern] | [Impact] | [Solution] |

## Jenkins CLI Quick Reference

```bash
# List jobs
~/bin/jenkins job [instance] list | rg -i [pattern]

# Get job status
~/bin/jenkins status [instance]/[job-name]

# Get parameters
~/bin/jenkins params [instance]/[job-name]

# Trigger a build
~/bin/jenkins build [instance]/[job-name] -p KEY=value

# View logs with build number
~/bin/jenkins logs [instance]/[job-name] -b [number]

# Check history
~/bin/jenkins history [instance]/[job-name]
```

## Local Validation

```bash
# Groovy syntax check
groovy -e "new GroovyShell().parse(new File('[product]/[file].groovy'))"

# Product-specific validation commands
[molecule/docker/test commands]
```

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| [What changed] | [Downstream effects] | [Team/person] |

## Related

- [[dependency]/AGENTS.md](../[dependency]/AGENTS.md) - [Relationship description]
- [[consumer]/AGENTS.md](../[consumer]/AGENTS.md) - [Relationship description]
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers ([helper-names])

## GitHub Repositories

| Repository | Purpose |
|------------|---------|
| [org/repo](https://github.com/[org]/[repo]) | [Description] |
| [Percona-QA/testing-repo](https://github.com/Percona-QA/[repo]) | Test scenarios |

---

## Template Notes

**Required sections**: TL;DR, Quick Reference, Job Dependency Graph, Jenkins CLI Quick Reference, Related
**Optional sections**: Add based on product needs (Version Matrix, Credentials, Platform Matrix, etc.)
**Style**: Terse, table-heavy, LLM-optimized
**Cross-refs**: Always bidirectional (if A links to B, B should link to A)
**Credential IDs**: Partially redact (first 8 chars + `*`)
**Line counts**: Only include if meaningful (directory maps, large orchestrators)
