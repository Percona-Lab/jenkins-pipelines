pipeline {
    agent none  // We will define the agent at each stage

    environment {
        // location of the test script
        TEST_SCRIPT = 'https://raw.githubusercontent.com/percona/telemetry-agent/6e2d5526c4596ea9e149114eb5529041036bd707/packaging/scripts/test-telemetry-agent.sh'
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
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

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
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

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
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

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
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

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
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

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
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

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
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

                            # Verify service behavior
                            pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                            '''
                        }
                    }
                }

                stage('Test on Amazon Linux 2') {
                    agent { label 'min-amazon-2-x64' }  // Run on the node with label 'min-amazon-2-x64'
                    steps {
                        script {
                            sh '''
                            # Update package list and install necessary dependencies
                            sudo yum install -y sudo wget gnupg2 curl systemd

                            wget \${TEST_SCRIPT} -O test-telemetry-agent.sh
                            chmod +x test-telemetry-agent
                            sudo ./test-telemetry-agent

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
