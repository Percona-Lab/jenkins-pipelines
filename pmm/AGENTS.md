# PMM pipelines — guide for AI agents

Tool-neutral entry point (the `AGENTS.md` convention) for `pmm/` in
Percona-Lab/jenkins-pipelines. Read this before editing anything here.

## Scope: `pmm/` and nothing else

This repository holds the Jenkins job definitions for **every** Percona product — `ppg`, `ps`,
`pxc`, `pxb`, `psmdb`, `pbm`, the distributions, `cloud` (k8s operators), release tooling. Each
directory belongs to a different team (`.github/CODEOWNERS`), and PMM owns `pmm/`.

**Stay inside `pmm/`.** Do not edit, lint, report on, or "fix in passing" another product's
directory, and treat the repo-root `vars/` shared library the same way: it is loaded as `lib@master`
by every product's builds, so a change there is a change to everyone's CI. If PMM genuinely needs
something from `vars/`, raise it with the owners rather than editing it — `pmm/v3/vars/` is PMM's
own shared library and is the right home for PMM-only steps.

The same rule applies to findings: problems noticed elsewhere in the repo are not ours to file,
fix, or put in a report.

## What these files are

- `*.groovy` — declarative Jenkins pipelines, one file per job, plus shared-library steps.
- `*.yml` — [Jenkins Job Builder](https://jenkins-job-builder.readthedocs.io/) definitions that
  register a pipeline as a job, with its parameters, triggers and retention.

**Nothing here runs its own product code.** These files are executed by Percona's Jenkins masters
against real cloud resources, so a mistake surfaces as a failed (or expensive) build, not as a
failing test. There is no way to run a job from a checkout — verify by reading carefully and by
running the Groovy linter.

## Layout

| Path | What |
|------|------|
| `pmm/v3/` | All PMM 3 pipelines (`pmm3-*.groovy`): server and client autobuilds, AMI/OVF images, UI / API / upgrade / migration / package tests, HA on EKS and ROSA, release and release-candidate |
| `pmm/v3/vars/` | PMM's own shared library, loaded as `v3lib@master` via `libraryPath: 'pmm/v3/'` |
| `pmm/` (root) | Older PMM jobs (`aws-staging-stop*.groovy`) |
| `pmm/openshift/`, `pmm/infrastructure/` | Adjacent PMM jobs: OpenShift cluster lifecycle, RPM builds |
| `pmm/scripts/` | Helpers for PMM's own tooling (not called from pipelines) |
| `pmm/README.md` | Agent labels (`agent-amd64`, `agent-arm64`, `cli`) and the "Zen of Jenkinsfile" conventions every new PMM pipeline follows: `buildDiscarder`, `deleteDir()` in `post`, Python over long bash |

Pipelines also load the repo-root `vars/` library as `lib@master`. Call its steps freely; do not
change them.

## Repos on the other side of these pipelines

| Repo | Relationship |
|------|--------------|
| [percona/pmm](https://github.com/percona/pmm) | The product. Built by `pmm3-server-autobuild.groovy` and `pmm3-client-autobuild*.groovy` |
| [percona/pmm-qa](https://github.com/percona/pmm-qa) | The e2e / CLI / package test suites. Test pipelines clone it at `PMM_QA_GIT_BRANCH`, rsync it to `/srv/pmm-qa`, then run out of `qa-integration/pmm_qa` and `e2e_tests`. **Test logic lives there, not here** — this repo only provisions and invokes it |
| [Percona-Lab/pmm-submodules](https://github.com/Percona-Lab/pmm-submodules) | Feature builds (FB). `pmm3-submodules.groovy` builds server and client images from a submodules PR, comments the tags back on the PR, triggers `pmm3-api-tests`, and dispatches pmm-qa's `pmm-qa-fb-checks.yml` workflow with those image tags |
| [percona/grafana](https://github.com/percona/grafana) | Percona's Grafana fork, pulled into the PMM server image by the server build |

## Groovy lint

`pmm/.groovylintrc.json` (rules) + `.github/workflows/pmm-groovy-lint.yml` (the gate).

The gate lints **only the `pmm/` `.groovy` files changed in the pull request** — not the rest of
`pmm/`, and never another product's directory. The 62 files here predate any linting and carry a
backlog; a gate over all of them would be red on every PR and would be ignored within a week. New
and edited files are held to the config, and the backlog is paid down as files get touched.

Run the same check locally before pushing (pin the version CI pins, in the workflow):

```bash
npx npm-groovy-lint@18.0.0 --noserver --failon error --config pmm pmm/v3/pmm3-ui-tests.groovy
```

- Only `error` severity fails the gate; `warning` and `info` are advisory and printed for context.
  `pmm/` is clean of errors today, so a red check means the PR introduced one.
- A file that does not parse always fails — Jenkins could not load it either. Note that Groovy
  reports the line where the parser gave up, which is often far from the actual mistake.
- Many stylistic rules are deliberately off because Jenkins DSL structurally violates them; the
  reasoning and the measured counts are in `pmm/.groovylintrc.json`.
- If a rule genuinely does not fit Jenkins pipeline DSL, change `pmm/.groovylintrc.json` in its own
  PR with the reasoning, and re-measure with the workflow's manual `scope=all` run. Do not
  `/* groovylint-disable */` a rule to get one file through, and do not widen the gate beyond `pmm/`.

## Working rules

- **Do not mix pipeline logic with tooling/config changes in one PR.** They have different
  reviewers and very different blast radius.
- Match the surrounding file. These are Jenkins DSL scripts, not general-purpose Groovy — copy the
  idioms already used by the neighbouring `pmm3-*` pipelines.
- Keep comments minimal and only where the intent is non-obvious (a credential quirk, a retry
  reason, an agent-label constraint). Do not narrate what the DSL already says.
- Never hardcode credentials. Use `credentials(...)` / `withCredentials` as the existing pipelines do.
- Commit and PR titles carry the Jira key: `PMM-1234 Short summary`. Check `git log -- pmm/` and
  match what is already there.
