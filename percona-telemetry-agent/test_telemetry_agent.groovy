pipeline {
    agent none  // We will define the agent at each stage

    environment {
        // location of the test script
        // todo: change this to a stable URL (on the main branch) once https://github.com/percona/telemetry-agent/pull/124 is merged
        TEST_SCRIPT = 'https://raw.githubusercontent.com/percona/telemetry-agent/refs/heads/TEL-61-improved-test-script/packaging/scripts/test-telemetry-agent.sh'
    }

    parameters {
            string(
                defaultValue: 'v1.0.0',
                description: 'Expected telemetry-agent version',
                name: 'TARGET_VERSION',
                trim: true
            )
    }

    stages {
        stage('Run Percona Telemetry Script') {
            parallel {
                stage('Test on Ubuntu Jammy (x64)') {
                    agent { label 'min-jammy-x64' }  // Run on the node with label 'min-jammy-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo apt-get update
                            sudo apt-get install -y sudo wget gnupg2 lsb-release curl systemd

                            # Download and run your script
                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Ubuntu Focal (x64)') {
                    agent { label 'min-focal-x64' }  // Run on the node with label 'min-focal-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo apt-get update
                            sudo apt-get install -y sudo wget gnupg2 lsb-release curl systemd

                            # Download and run your script
                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }
                stage('Test on Ubuntu Noble (x64)') {
                    agent { label 'min-noble-x64' }  // Run on the node with label 'min-focal-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo apt-get update
                            sudo apt-get install -y sudo wget gnupg2 lsb-release curl systemd

                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Debian Bookworm (x64)') {
                    agent { label 'min-bookworm-x64' }  // Run on the node with label 'min-bookworm-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo apt-get update
                            sudo apt-get install -y sudo wget gnupg2 lsb-release curl systemd

                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Debian Bullseye (x64)') {
                    agent { label 'min-bullseye-x64' }  // Run on the node with label 'min-bookworm-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo apt-get update
                            sudo apt-get install -y sudo wget gnupg2 lsb-release curl systemd

                            # Download and run your script
                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Oracle Linux 8 (x64)') {
                    agent { label 'min-ol-8-x64' }  // Run on the node with label 'min-oraclelinux-8-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo yum install -y sudo wget gnupg2 curl systemd

                            # Download and run your script
                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Oracle Linux 9 (x64)') {
                    agent { label 'min-ol-9-x64' }  // Run on the node with label 'min-oraclelinux-9-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo yum install -y sudo wget gnupg2 curl systemd

                            # Download and run your script
                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Amazon Linux 2023 x64') {
                    agent { label 'min-al2023-x64' }
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies

                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Amazon Linux 2023 aarch64') {
                    agent { label 'min-al2023-aarch64' }
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies

                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent.sh
                            sudo ./test-telemetry-agent.sh \${TARGET_VERSION}

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            echo 'Cleaning up'
        }
        success {
            echo 'Tests completed successfully'
        }
        failure {
            echo 'Tests failed'
        }
    }
}
