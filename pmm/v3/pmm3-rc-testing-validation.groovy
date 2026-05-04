pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    parameters {
        choice(
            choices: ['orchestrator', 'child-pass', 'child-fail'],
            description: 'Role: orchestrator triggers children, child-pass/child-fail simulate downstream behavior',
            name: 'ROLE'
        )
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 5, unit: 'MINUTES')
    }
    stages {
        // ======================== CHILD MODES ========================
        stage('[Child] Succeed') {
            when {
                expression { params.ROLE == 'child-pass' }
            }
            steps {
            }
        }
        stage('[Child] Fail intentionally') {
            when {
                expression { params.ROLE == 'child-fail' }
            }
            steps {
                error("Intentional failure to test propagate:false behavior")
            }
        }

        // ======================== ORCHESTRATOR MODE ========================
        stage('[Orchestrator] Trigger child that PASSES') {
            when {
                expression { params.ROLE == 'orchestrator' }
            }
            steps {
                script {
                    echo "=== Triggering self with ROLE=child-pass, wait:false, propagate:false ==="
                    try {
                        build job: env.JOB_NAME, wait: false, propagate: false, parameters: [
                            string(name: 'ROLE', value: 'child-pass'),
                        ]
                    } catch (Throwable e) {
                    }
                }
            }
        }
        stage('[Orchestrator] Trigger child that FAILS') {
            when {
                expression { params.ROLE == 'orchestrator' }
            }
            steps {
                script {
                    echo "=== Triggering self with ROLE=child-fail, wait:false, propagate:false ==="
                    try {
                        build job: env.JOB_NAME, wait: false, propagate: false, parameters: [
                            string(name: 'ROLE', value: 'child-fail'),
                        ]
                    } catch (Throwable e) {
                    }
                }
            }
        }
        stage('Trigger non-existent job') {
            when {
                expression { params.ROLE == 'orchestrator' }
            }
            steps {
                script {
                    echo "=== Triggering a job that does NOT exist ==="
                    try {
                        build job: 'pmm3-rc-testing-THIS-JOB-DOES-NOT-EXIST', wait: false, propagate: false, parameters: [
                            string(name: 'RC_VERSION', value: '0.0.0'),
                        ]
                    } catch (Throwable e) {
                    }
                }
            }
        }
        stage('Trigger with null data') {
            when {
                expression { params.ROLE == 'orchestrator' }
            }
            steps {
                script {
                    echo "=== Calling .trim() on a null env var (simulates missing upstream data) ==="
                    env.FAKE_NULL_VAR = null
                    try {
                        build job: env.JOB_NAME, wait: false, propagate: false, parameters: [
                            string(name: 'ROLE', value: env.FAKE_NULL_VAR.trim()),
                        ]
                    } catch (Throwable e) {
                    }
                }
            }
        }
        stage('Queue RC tests (real trigger)') {
            when {
                expression { params.ROLE == 'orchestrator' }
            }
            steps {
                script {
                    echo "=== Real trigger: 'Queue RC tests' stage ==="
                    try {
                        build job: 'pmm3-rc-testing', wait: false, propagate: false, parameters: [
                                string(name: 'RC_VERSION', value: '3.99.0-validation'),
                                string(name: 'PMM_CLIENT_TARBALL', value: 'https://example.com/pmm-client-3.99.0.tar.gz'),
                                string(name: 'PMM_CLIENT_TARBALL_ARM64', value: 'https://example.com/pmm-client-3.99.0-arm64.tar.gz'),
                                string(name: 'PMM_CLIENT_TARBALL_OL8', value: 'https://example.com/pmm-client-3.99.0-ol8.tar.gz'),
                                string(name: 'PMM_CLIENT_TARBALL_OL9', value: 'https://example.com/pmm-client-3.99.0-ol9.tar.gz'),
                                string(name: 'AMI_ID', value: 'ami-00000000000000000'),
                            ]
                        echo "[rc-tests] Release Candidate testing queued for 3.99.0-validation."
                    } catch (Throwable e) {
                        echo "[rc-tests] Could not queue pmm3-rc-testing: ${e.message}"
                    }
                }
            }
        }
    }
}
