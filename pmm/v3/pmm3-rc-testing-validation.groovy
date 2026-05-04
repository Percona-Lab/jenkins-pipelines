pipeline {
    agent {
        label 'agent-amd64-ol9'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 5, unit: 'MINUTES')
    }
    stages {
        stage('Queue RC tests') {
            steps {
                script {
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
