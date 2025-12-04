"""
PMM API Tests Dagger Module

This module replaces the pmm3-api-tests.groovy Jenkins pipeline with a portable,
cacheable Dagger pipeline that can run both locally and in CI.

Usage:
    # Run tests with defaults
    dagger call run-tests

    # Run with custom PMM server version
    dagger call run-tests --docker-version="perconalab/pmm-server:3-dev-latest"

    # Export JUnit results
    dagger call run-tests export --path ./junit-results.xml

    # Get server logs for debugging
    dagger call get-logs export --path ./logs.zip
"""

from __future__ import annotations

import dagger
from dagger import dag, function, object_type


@object_type
class PmmApiTests:
    """PMM API Tests - portable CI pipeline for PMM Server API testing."""

    @function
    async def run_tests(
        self,
        git_url: str = "https://github.com/percona/pmm",
        git_branch: str = "v3",
        git_commit: str = "",
        docker_version: str = "perconalab/pmm-server:3-dev-latest",
        mysql_image: str = "percona:8.0",
        postgres_image: str = "postgres:14",
        mongo_image: str = "percona/percona-server-mongodb:5.0",
        docker_username: str = "",
        docker_password: dagger.Secret | None = None,
    ) -> dagger.File:
        """
        Run PMM API tests and return JUnit XML results.

        Orchestrates:
        1. Clone PMM repository
        2. Build test image
        3. Start PMM server service
        4. Run API tests
        5. Return JUnit XML file

        Args:
            git_url: PMM repository URL
            git_branch: Branch to test
            git_commit: Specific commit hash (optional, overrides branch)
            docker_version: PMM Server image to test against
            mysql_image: MySQL image for test database
            postgres_image: PostgreSQL image for test database
            mongo_image: MongoDB image for test database
            docker_username: Docker Hub username for authenticated pulls
            docker_password: Docker Hub password as Dagger secret

        Returns:
            JUnit XML test results file
        """
        # 1. Clone PMM repository
        if git_commit:
            source = dag.git(git_url).commit(git_commit).tree()
        else:
            source = dag.git(git_url).branch(git_branch).tree()

        # 2. Get checks directory for mounting into PMM server
        checks_dir = source.directory("managed/testdata/checks")

        # 3. Create PMM server service
        pmm_server = self._pmm_server_service(
            docker_version=docker_version,
            checks_dir=checks_dir,
            docker_username=docker_username,
            docker_password=docker_password,
        )

        # 4. Build the API test image from PMM repo
        test_image = self._build_test_image(source)

        # 5. Run tests with PMM server bound
        test_container = (
            test_image
            .with_service_binding("pmm-server", pmm_server)
            .with_env_variable("PMM_SERVER_URL", "https://admin:admin@pmm-server:8443")
            .with_env_variable("PMM_SERVER_INSECURE_TLS", "1")
            .with_env_variable("PMM_RUN_UPDATE_TEST", "0")
            .with_env_variable("PMM_RUN_ADVISOR_TESTS", "0")
            # Add cache buster to ensure fresh run
            .with_env_variable("CACHE_BUSTER", str(dag.default_platform()))
        )

        # Wait for PMM server to be ready, then run tests
        test_result = await (
            test_container
            .with_exec(
                ["sh", "-c", """
                    # Wait for PMM server
                    echo "Waiting for PMM server..."
                    timeout 100 bash -c 'until curl -skf https://pmm-server:8443/ping; do sleep 1; done'
                    echo "PMM server is ready"

                    # Run API tests
                    cd /go/src/github.com/percona/pmm/api-tests
                    go test -v -timeout 30m ./... -json 2>&1 | tee test-output.json || true

                    # Convert to JUnit XML using go-junit-report if available,
                    # otherwise create minimal JUnit file
                    if command -v go-junit-report &> /dev/null; then
                        cat test-output.json | go-junit-report > pmm-api-tests-junit-report.xml
                    else
                        # Fallback: create basic JUnit XML from test output
                        echo '<?xml version="1.0" encoding="UTF-8"?>' > pmm-api-tests-junit-report.xml
                        echo '<testsuites>' >> pmm-api-tests-junit-report.xml
                        echo '  <testsuite name="pmm-api-tests" tests="1">' >> pmm-api-tests-junit-report.xml
                        if grep -q '"Action":"fail"' test-output.json 2>/dev/null; then
                            echo '    <testcase name="api-tests"><failure>See logs</failure></testcase>' >> pmm-api-tests-junit-report.xml
                        else
                            echo '    <testcase name="api-tests"/>' >> pmm-api-tests-junit-report.xml
                        fi
                        echo '  </testsuite>' >> pmm-api-tests-junit-report.xml
                        echo '</testsuites>' >> pmm-api-tests-junit-report.xml
                    fi
                """]
            )
            .file("/go/src/github.com/percona/pmm/api-tests/pmm-api-tests-junit-report.xml")
        )

        return test_result

    @function
    async def get_logs(
        self,
        docker_version: str = "perconalab/pmm-server:3-dev-latest",
        docker_username: str = "",
        docker_password: dagger.Secret | None = None,
    ) -> dagger.File:
        """
        Get PMM server logs.zip for debugging.

        Starts a PMM server instance and retrieves the diagnostic logs archive.

        Args:
            docker_version: PMM Server image version
            docker_username: Docker Hub username for authenticated pulls
            docker_password: Docker Hub password as Dagger secret

        Returns:
            logs.zip file from PMM server
        """
        # Create PMM server container
        pmm_container = dag.container()

        # Add registry auth if credentials provided
        if docker_username and docker_password:
            pmm_container = pmm_container.with_registry_auth(
                "docker.io",
                docker_username,
                docker_password,
            )

        pmm_container = (
            pmm_container
            .from_(docker_version)
            .with_env_variable("PMM_DEBUG", "1")
            .with_exposed_port(8080)
            .with_exposed_port(8443)
        )

        # Start as service and fetch logs
        pmm_service = pmm_container.as_service()

        # Use a helper container to fetch logs
        logs_file = await (
            dag.container()
            .from_("curlimages/curl:latest")
            .with_service_binding("pmm-server", pmm_service)
            .with_exec(
                ["sh", "-c", """
                    # Wait for PMM server
                    timeout 120 sh -c 'until curl -skf https://pmm-server:8443/ping; do sleep 2; done'
                    # Download logs
                    curl -sk https://admin:admin@pmm-server:8443/logs.zip -o /tmp/logs.zip
                """]
            )
            .file("/tmp/logs.zip")
        )

        return logs_file

    @function
    async def health_check(
        self,
        docker_version: str = "perconalab/pmm-server:3-dev-latest",
        timeout_seconds: int = 100,
    ) -> str:
        """
        Check if PMM server is healthy and return status.

        Useful for validating PMM server images before running full test suite.

        Args:
            docker_version: PMM Server image to check
            timeout_seconds: Maximum time to wait for server

        Returns:
            Health check status message
        """
        pmm_server = (
            dag.container()
            .from_(docker_version)
            .with_env_variable("PMM_DEBUG", "1")
            .with_exposed_port(8443)
            .as_service()
        )

        result = await (
            dag.container()
            .from_("curlimages/curl:latest")
            .with_service_binding("pmm-server", pmm_server)
            .with_exec(
                ["sh", "-c", f"""
                    timeout {timeout_seconds} sh -c 'until curl -skf https://pmm-server:8443/ping; do sleep 1; done'
                    curl -sk https://pmm-server:8443/ping
                """]
            )
            .stdout()
        )

        return f"PMM Server healthy: {result}"

    def _pmm_server_service(
        self,
        docker_version: str,
        checks_dir: dagger.Directory,
        docker_username: str = "",
        docker_password: dagger.Secret | None = None,
    ) -> dagger.Service:
        """
        Create PMM Server as a Dagger service.

        Replicates the docker run command from pmm3-api-tests.groovy:
            docker run -d \
                -e PMM_DEBUG=1 \
                -e PMM_DEV_PERCONA_PLATFORM_ADDRESS=https://check-dev.percona.com \
                -e PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY=... \
                -p 80:8080 -p 443:8443 \
                -v ${PWD}/managed/testdata/checks:/srv/checks \
                ${DOCKER_VERSION}

        PMM Server requires /srv to be writable by UID 1000 (pmm user).
        We handle this by:
        1. Mounting checks to a temp location
        2. Running as root to copy and fix permissions
        3. Running the PMM entrypoint
        """
        container = dag.container()

        # Add registry auth if credentials provided
        if docker_username and docker_password:
            container = container.with_registry_auth(
                "docker.io",
                docker_username,
                docker_password,
            )

        return (
            container
            .from_(docker_version)
            .with_env_variable("PMM_DEBUG", "1")
            .with_env_variable(
                "PMM_DEV_PERCONA_PLATFORM_ADDRESS",
                "https://check-dev.percona.com"
            )
            .with_env_variable(
                "PMM_DEV_PERCONA_PLATFORM_PUBLIC_KEY",
                "RWTg+ZmCCjt7O8eWeAmTLAqW+1ozUbpRSKSwNTmO+exlS5KEIPYWuYdX"
            )
            # Mount checks to temp location first
            .with_directory("/tmp/checks", checks_dir)
            .with_exposed_port(8080)
            .with_exposed_port(8443)
            # Use as_service with args to run setup + entrypoint as a single command
            # This ensures the service process stays in foreground
            .as_service(args=["sh", "-c", """
                # Run as root to set up /srv/checks and fix permissions
                mkdir -p /srv/checks
                cp -r /tmp/checks/* /srv/checks/ 2>/dev/null || true
                chown -R 1000:0 /srv
                chmod -R g+rwX /srv
                # Exec the original entrypoint - must stay in foreground
                exec /opt/entrypoint.sh
            """])
        )

    def _build_test_image(
        self,
        source: dagger.Directory,
    ) -> dagger.Container:
        """
        Build the PMM API test image from source.

        Replicates: docker build -t local/pmm-api-tests .
        """
        return (
            dag.container()
            .build(source)
        )
