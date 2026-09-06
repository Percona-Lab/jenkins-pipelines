# ARM job regression checks

Run from the repository root with Groovy, Python and Jenkins Job Builder installed:

```sh
groovy pxb/v2/tests/verify-arch-preflight.groovy
groovy pxb/v2/tests/verify-pxc-source.groovy
uv run --no-project python pxb/v2/tests/test_arm_job_definitions.py
```

The Groovy check executes the real Jenkinsfiles up to their pipeline boundary,
without allocating workers or running build steps. The Python tests render the
actual job definitions and check their script paths and architecture parameters.
These are local regression checks, not product-build or full-matrix evidence.
