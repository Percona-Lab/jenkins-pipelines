# ARM job regression checks

Run from the repository root with Groovy, Python and Jenkins Job Builder installed:

```sh
groovy pxb/v2/tests/verify-arch-preflight.groovy
groovy pxb/v2/tests/verify-pxc-source.groovy
groovy pxb/v2/tests/verify_default_matrix.groovy
uv run --no-project python pxb/v2/tests/test_arm_job_definitions.py
```

The Groovy check executes the real Jenkinsfiles up to their pipeline boundary,
without allocating workers or running build steps. The Python tests render the
actual job definitions and check their script paths and architecture parameters.
These are local regression checks, not product-build or full-matrix evidence.

The default-matrix check renders all 27 definitions and evaluates the actual
Groovy filters. It preserves the existing x86 platform/build-type combinations
and both database targets, adding only these RelWithDebInfo combinations:

| Jenkins family | Platform | Architecture |
|---|---|---|
| 8.0 | oraclelinux:9 | aarch64 |
| 8.1 | oraclelinux:9 | aarch64 |
| 9.x | amazonlinux:2023 | aarch64 |
| 9.x | amazonlinux:2023 | x86_64 |

The expected compile/test cell counts are 17/34, 17/34 and 14/28 respectively.
Non-default combinations remain selectable in the individual Pipeline jobs,
not in the filtered matrix parents. PXB 2.4 stays x86-only. These checks do not
measure product runtime or establish that the selected images and helpers work.
They are manual regression checks, not automatically run by repository CI.
