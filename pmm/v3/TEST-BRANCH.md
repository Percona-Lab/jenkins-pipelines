# pmm-arm64-test branch — safe test copies of the ARM64 pipelines

This branch carries the ARM64 pmm-server work in a form that can be registered in
Jenkins **without affecting the team**:

- every job in the test tree references `-test` job names only;
- Docker tags carry a `-test` suffix (`3-dev-latest-test`, `dev-latest-test`, `<ver>-rc-test`),
  so the real `3-dev-latest` / RC tags are never overwritten;
- upstream triggers, crons, Slack notifications, and the GitHub devcontainer dispatch
  are removed on this branch (master still has them);
- `pmm3-aws-staging-start` pins its shared library to `v3lib@pmm-arm64-test` so the
  arch-aware `runSpotInstance` / `setupPMM3Client` from this branch are used;
- RPM upload paths are segregated automatically (they contain `${JOB_NAME}`).

The generic teardown job `aws-staging-stop` is reused as-is (it only acts on the VM
name passed to it).

## Jenkins job registration

**Option A — Jenkins Job Builder (recommended, one command):**

```bash
pip install jenkins-job-builder
cat > jenkins_jobs.ini <<EOF
[jenkins]
url=https://pmm.cd.percona.com
user=<your-jenkins-user>
password=<your-jenkins-API-token>
EOF
jenkins-jobs test pmm/v3/test-jobs-jjb.yml            # dry-run: renders XML locally
jenkins-jobs --conf jenkins_jobs.ini update pmm/v3/test-jobs-jjb.yml
```

`pmm/v3/test-jobs-jjb.yml` defines all 11 jobs (and contains the delete command for
cleanup). First run of each job executes with default parameters (pipelines define
their own `parameters {}`); the parameter form appears from the second run.

**Option B — manually.** Create Pipeline-from-SCM jobs pointing at
`https://github.com/Percona-Lab/jenkins-pipelines.git`, branch `pmm-arm64-test`:

| Jenkins job name (register exactly this) | Script path |
|---|---|
| `arm64-pmm3-server-autobuild-test` | `pmm/v3/pmm3-server-autobuild.groovy` |
| `arm64-pmm3-server-autobuild-amd-test` | `pmm/v3/pmm3-server-autobuild-amd.groovy` |
| `arm64-pmm3-server-autobuild-arm-test` | `pmm/v3/pmm3-server-autobuild-arm.groovy` |
| `arm64-pmm3-watchtower-autobuild-test` | `pmm/v3/pmm3-watchtower-autobuild.groovy` |
| `arm64-pmm3-api-tests-test` | `pmm/v3/pmm3-api-tests.groovy` |
| `arm64-pmm3-image-scanning-test` | `pmm/v3/pmm3-image-scanning.groovy` |
| `arm64-pmm3-aws-staging-start-test` | `pmm/v3/pmm3-aws-staging-start.groovy` |
| `arm64-pmm3-package-testing-test` | `pmm/v3/pmm3-package-testing-amd64.groovy` |
| `arm64-pmm3-package-testing-arm-test` | `pmm/v3/pmm3-package-testing-arm64.groovy` |
| `arm64-pmm3-package-testing-matrix-test` | `pmm/v3/pmm3-package-tests-matrix-amd64.groovy` |
| `arm64-pmm3-package-testing-arm-matrix-test` | `pmm/v3/pmm3-package-tests-matrix-arm64.groovy` |

The job names are load-bearing: the fan-out parent calls
`arm64-pmm3-server-autobuild-amd-test` / `-arm-test` / `arm64-pmm3-api-tests-test`, the package
jobs call `arm64-pmm3-aws-staging-start-test`, and the matrices call the `-test` leaves.

## Prerequisites before the first run

1. **percona/pmm PR (S3 cache arch-namespacing)** must be merged (or the ARM child run
   with `FORCE_REBUILD=1` via a temporary edit) — otherwise the ARM build downloads
   cached **amd64** RPMs and skips building. See the plan, Task 1.
2. `agent-arm64` label must have capacity (it already serves the client ARM builds).
3. For `arm64-pmm3-aws-staging-start-test` with `SERVER_ARCH=arm64`: an arm64 AMI tagged
   `iit-billing-tag=pmm-worker-3` must exist in us-east-2 (plan, Task 11). amd64 runs
   work immediately.

## Suggested test sequence

1. `arm64-pmm3-watchtower-autobuild-test` → verify `docker buildx imagetools inspect
   perconalab/watchtower:dev-latest-test` shows amd64+arm64.
2. `arm64-pmm3-server-autobuild-test` (DESTINATION=experimental) → verify
   `perconalab/pmm-server:3-dev-latest-test` is a 2-platform manifest; API tests
   (amd64+arm64) triggered automatically.
3. `arm64-pmm3-image-scanning-test` with `PMM_SERVER_IMAGE=perconalab/pmm-server:3-dev-latest-test`.
4. `arm64-pmm3-aws-staging-start-test` with `SERVER_ARCH=arm64`,
   `DOCKER_VERSION=perconalab/pmm-server:3-dev-latest-test`,
   `WATCHTOWER_VERSION=perconalab/watchtower:dev-latest-test`.
5. `arm64-pmm3-package-testing-arm-matrix-test` with
   `DOCKER_VERSION=perconalab/pmm-server:3-dev-latest-test`.

## Promoting to master (the final PR)

Build the final PR by re-applying this branch's diff on top of master
(`git diff master...pmm-arm64-test -- pmm/v3/`) with these test-only bits reverted:

- remove the `arm64-` prefix and `-test` suffix from all `build job:` references
  (`grep -rn "arm64-pmm3-" pmm/v3/`) and remove `-test` from all Docker tags
  (`grep -rn "\-test\b" pmm/v3/*.groovy | grep -i tag`);
- keep master's Slack sends, the `pmm3-submodules-rewind` upstream triggers
  (parent + watchtower), the package-testing arm64 cron (`0 2 * * *`), and the
  devcontainer dispatch stage in the server fan-out parent;
- change `v3lib@pmm-arm64-test` back to `v3lib@master` in `pmm3-aws-staging-start.groovy`
  and restore its `NOTIFY` choices order to `['true', 'false']`;
- delete this file.
