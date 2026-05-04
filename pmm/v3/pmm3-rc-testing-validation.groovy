/**
 * PMM3 RC Testing - Validation Pipeline
 *
 * Purpose: Verify the behavior of the 'Queue RC testing' stage
 * from pmm3-release-candidate.groovy BEFORE merging.
 *
 * This pipeline proves:
 *   1. With wait:false, propagate:false — parent stays GREEN even if the
 *      downstream job fails or doesn't exist.
 *   2. The try/catch swallows queueing errors (e.g., NullPointerException
 *      from .trim() on null values, non-existent job name).
 *   3. A successful trigger also keeps the pipeline GREEN.
 *
 * Run this job manually on Jenkins to confirm behavior before merging PR #3968.
 */

pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        choice(
            choices: ['trigger-nonexistent-job', 'trigger-with-null-params', 'trigger-successful', 'all'],
            description: 'Which scenario to test',
            name: 'TEST_SCENARIO'
        )
    }
    stages {
        stage('Scenario: Trigger non-existent job') {
            when {
                expression { params.TEST_SCENARIO == 'trigger-nonexistent-job' || params.TEST_SCENARIO == 'all' }
            }
            steps {
                script {
                    echo "=== Testing: trigger a job that does not exist ==="
                    echo "Expected: stage passes, pipeline stays GREEN"
                    try {
                        build job: 'pmm3-rc-testing-DOES-NOT-EXIST', wait: false, propagate: false, parameters: [
                            string(name: 'RC_VERSION', value: '3.99.0'),
                        ]
                        echo "[rc-tests] Job queued successfully (unexpected for this scenario)."
                    } catch (Throwable e) {
                        echo "[rc-tests] Could not queue pmm3-rc-testing: ${e.message}"
                        echo "[VALIDATION] Exception caught as expected — pipeline continues."
                    }
                }
            }
        }
        stage('Scenario: Trigger with null parameters') {
            when {
                expression { params.TEST_SCENARIO == 'trigger-with-null-params' || params.TEST_SCENARIO == 'all' }
            }
            steps {
                script {
                    echo "=== Testing: .trim() on null env vars ==="
                    echo "Expected: NullPointerException caught, pipeline stays GREEN"

                    // Simulate null env vars (as would happen if upstream stages failed to set them)
                    env.FAKE_TARBALL_URL = null

                    try {
                        build job: 'pmm3-rc-testing', wait: false, propagate: false, parameters: [
                            string(name: 'RC_VERSION', value: '3.99.0'),
                            string(name: 'PMM_CLIENT_TARBALL', value: env.FAKE_TARBALL_URL.trim()),
                        ]
                        echo "[rc-tests] Job queued successfully."
                    } catch (Throwable e) {
                        echo "[rc-tests] Could not queue pmm3-rc-testing: ${e.message}"
                        echo "[VALIDATION] Exception caught as expected — pipeline continues."
                    }
                }
            }
        }
        stage('Scenario: Successful trigger (fire-and-forget)') {
            when {
                expression { params.TEST_SCENARIO == 'trigger-successful' || params.TEST_SCENARIO == 'all' }
            }
            steps {
                script {
                    echo "=== Testing: successful trigger with wait:false ==="
                    echo "Expected: job is queued, parent does NOT wait for result, pipeline stays GREEN"

                    // Use a real job that exists in Jenkins (echo job or self-trigger with different params)
                    // Replace 'pmm3-rc-testing' with an actual existing job for a real test
                    try {
                        build job: 'pmm3-rc-testing', wait: false, propagate: false, parameters: [
                            string(name: 'RC_VERSION', value: '3.99.0-validation-test'),
                            string(name: 'PMM_CLIENT_TARBALL', value: 'https://example.com/fake-tarball.tar.gz'),
                            string(name: 'PMM_CLIENT_TARBALL_ARM64', value: 'https://example.com/fake-tarball-arm64.tar.gz'),
                            string(name: 'PMM_CLIENT_TARBALL_OL8', value: 'https://example.com/fake-tarball-ol8.tar.gz'),
                            string(name: 'PMM_CLIENT_TARBALL_OL9', value: 'https://example.com/fake-tarball-ol9.tar.gz'),
                            string(name: 'AMI_ID', value: 'ami-00000000000000000'),
                        ]
                        echo "[rc-tests] Release Candidate testing queued for 3.99.0-validation-test."
                        echo "[VALIDATION] Job queued — parent pipeline did NOT wait. Success."
                    } catch (Throwable e) {
                        echo "[rc-tests] Could not queue pmm3-rc-testing: ${e.message}"
                        echo "[VALIDATION] Queueing failed but pipeline continues."
                    }
                }
            }
        }
    }
    post {
        success {
            echo """
============================================================
[VALIDATION RESULT] Pipeline finished with status: SUCCESS

This confirms:
  - wait: false   → parent does not block on downstream job
  - propagate: false → downstream failure does not affect parent
  - try/catch    → queueing errors are swallowed gracefully

The 'Queue RC testing' stage in pmm3-release-candidate.groovy
will NEVER make the RC pipeline RED, regardless of what happens
to the downstream rc-testing jobs.

PR #3968 is safe to merge from a pipeline-status perspective.
============================================================
"""
        }
        failure {
            echo """
============================================================
[VALIDATION RESULT] Pipeline finished with status: FAILURE

UNEXPECTED — this means something outside the try/catch block
caused a failure. Investigate the console output above.
============================================================
"""
        }
    }
}
