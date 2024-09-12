pipeline {
    agent none  // We will define the agent at each stage

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
                            wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                            chmod +x your-script.sh
                            sudo ./your-script.sh

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
                            wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                            chmod +x your-script.sh
                            sudo ./your-script.sh

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

                            # Download and run your script
                            wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                            chmod +x your-script.sh
                            sudo ./your-script.sh

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

                            # Download and run your script
                            wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                            chmod +x your-script.sh
                            sudo ./your-script.sh

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
                            wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                            chmod +x your-script.sh
                            sudo ./your-script.sh

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
                            wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                            chmod +x your-script.sh
                            sudo ./your-script.sh

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
                            wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                            chmod +x your-script.sh
                            sudo ./your-script.sh

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
