# PMM API Tests - Dagger Module

This Dagger module replaces the `pmm3-api-tests.groovy` Jenkins pipeline with a portable, reproducible, and cacheable CI pipeline for PMM Server API testing.

## Prerequisites

- [Dagger CLI](https://docs.dagger.io/install) v0.15.1+
- Docker runtime (Docker Desktop, Podman, or similar)
- (Optional) [uv](https://docs.astral.sh/uv/) for Python dependency management

## Quick Start

```bash
cd dagger

# Run tests with defaults (PMM v3, perconalab/pmm-server:3-dev-latest)
dagger call run-tests

# Run with specific PMM server version
dagger call run-tests --docker-version="perconalab/pmm-server:3.0.0"

# Run against a specific branch
dagger call run-tests --git-branch="PMM-12345-fix-api"

# Export JUnit results to host
dagger call run-tests export --path ./junit-results.xml
```

## Available Functions

### `run-tests`

Run the full PMM API test suite and return JUnit XML results.

```bash
dagger call run-tests \
    --git-url="https://github.com/percona/pmm" \
    --git-branch="v3" \
    --docker-version="perconalab/pmm-server:3-dev-latest" \
    export --path ./results.xml
```

**Parameters:**

| Parameter | Default | Description |
|-----------|---------|-------------|
| `--git-url` | `https://github.com/percona/pmm` | PMM repository URL |
| `--git-branch` | `v3` | Branch to test |
| `--git-commit` | (empty) | Specific commit hash (overrides branch) |
| `--docker-version` | `perconalab/pmm-server:3-dev-latest` | PMM Server image |
| `--mysql-image` | `percona:8.0` | MySQL image for tests |
| `--postgres-image` | `postgres:14` | PostgreSQL image for tests |
| `--mongo-image` | `percona/percona-server-mongodb:5.0` | MongoDB image for tests |
| `--docker-username` | (empty) | Docker Hub username |
| `--docker-password` | (empty) | Docker Hub password (secret) |

### `get-logs`

Retrieve PMM server diagnostic logs for debugging.

```bash
dagger call get-logs \
    --docker-version="perconalab/pmm-server:3-dev-latest" \
    export --path ./logs.zip
```

### `health-check`

Quick validation that a PMM server image starts correctly.

```bash
dagger call health-check --docker-version="perconalab/pmm-server:3-dev-latest"
```

## Jenkins Integration

This module is designed to work with the `pmm3-api-tests-dagger.groovy` Jenkins pipeline.

**Jenkins job configuration:**
1. Pipeline script from SCM
2. Repository: `https://github.com/Percona-Lab/jenkins-pipelines.git`
3. Script path: `pmm/v3/pmm3-api-tests-dagger.groovy`

The Jenkins wrapper handles:
- Dagger CLI installation
- Docker Hub credential injection
- JUnit result extraction and publishing
- Artifact archiving (logs.zip)
- Slack notifications

## Architecture

```
+-------------------+       +-------------------+
|   PMM Server      |       |   Test Container  |
|   (Dagger Service)|<------|   (API Tests)     |
|   :8080/:8443     |       |   Go test suite   |
+-------------------+       +-------------------+
```

**Key differences from Groovy pipeline:**

| Aspect | Groovy Pipeline | Dagger Module |
|--------|-----------------|---------------|
| Local testing | Not possible | `dagger call run-tests` |
| Caching | Limited | Built-in layer + function caching |
| Portability | Jenkins only | Any CI with Docker |
| Debugging | Check Jenkins logs | Interactive shell available |
| Language | Groovy/Bash | Python (type-safe) |

## Development

### Project Structure

```
dagger/
├── dagger.json      # Dagger module configuration
├── pyproject.toml   # Python project (uv/pip compatible)
├── main.py          # Main module with test functions
└── README.md        # This file
```

### Local Development

```bash
# Install uv (if not already installed)
curl -LsSf https://astral.sh/uv/install.sh | sh

# Sync dependencies (creates uv.lock)
cd dagger
uv sync

# Run with live reloading during development
dagger call --debug run-tests
```

### Linting

```bash
# Using ruff
uv run ruff check main.py
uv run ruff format main.py
```

## Troubleshooting

### PMM Server won't start

```bash
# Check health with debug output
dagger call --debug health-check --docker-version="perconalab/pmm-server:3-dev-latest"
```

### Tests timeout

Increase the timeout or check network connectivity:

```bash
# The default PMM server wait is 100 seconds
# Check if server is accessible
dagger call health-check --timeout-seconds=300
```

### Docker Hub rate limits

Pass credentials to avoid anonymous pull limits:

```bash
dagger call run-tests \
    --docker-username="myuser" \
    --docker-password=env:DOCKER_PASSWORD
```

## Related Files

- `pmm/v3/pmm3-api-tests.groovy` - Original Jenkins pipeline (being replaced)
- `pmm/v3/pmm3-api-tests-dagger.groovy` - New Jenkins wrapper for this module
- `vars/getPMMBuildParams.groovy` - Shared library for build owner extraction
