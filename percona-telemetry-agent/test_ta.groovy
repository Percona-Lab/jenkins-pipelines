pipeline {
    agent { label 'micro-amazon' }

    stages {
        stage('Install Docker') {
            steps {
                script {
                    // Install Docker and configure permissions
                    sh '''
                    # Install Docker on Amazon Linux or Ubuntu
                    if [ -f /etc/system-release ]; then
                        # Amazon Linux
                        sudo yum update -y
                        sudo amazon-linux-extras install docker -y
                        sudo service docker start
                        sudo usermod -aG docker ec2-user
                    elif [ -f /etc/lsb-release ]; then
                        # Ubuntu
                        sudo apt-get update
                        sudo apt-get install -y apt-transport-https ca-certificates curl software-properties-common
                        curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
                        sudo add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"
                        sudo apt-get update
                        sudo apt-get install -y docker-ce
                        sudo systemctl start docker
                        sudo usermod -aG docker $(whoami)
                    else
                        echo "Unsupported OS"
                        exit 1
                    fi

                    # Set proper permissions for Docker socket
                    sudo chmod 666 /var/run/docker.sock

                    # Verify Docker installation
                    docker --version
                    '''
                }
            }
        }

        stage('Run Percona Telemetry Script') {
            steps {
                script {
                    def osList = ['ubuntu:focal', 'ubuntu:noble', 'ubuntu:jammy', 'debian:bookworm', 'debian:bullseye', 'oraclelinux:8', 'oraclelinux:9']
                    def parallelStages = [:]

                    osList.each { osImage ->
                        parallelStages[osImage] = {
                            stage("Test on ${osImage}") {
                                script {
                                    // Run Docker container with the current OS image
                                    docker.image(osImage).inside('--privileged --user root') {
                                        sh """
                                        # Install necessary dependencies
                                        case "${osImage}" in
                                            "ubuntu:focal"|"ubuntu:jammy"|"ubuntu:noble")
                                                 apt-get update
                                                 apt-get install -y sudo wget gnupg2 lsb-release curl systemd
                                                ;;
                                            "debian:bookworm"|"debian:bullseye")
                                                 apt-get update
                                                 apt-get install -y sudo wget gnupg2 lsb-release curl systemd
                                                ;;
                                            "oraclelinux:8"|"oraclelinux:9")
                                                 yum install -y sudo wget gnupg2 curl systemd
                                                ;;
                                        esac

                                        # Download and run your script
                                        wget https://raw.githubusercontent.com/EvgeniyPatlan/TA_tests/main/test_ta.sh -O your-script.sh
                                        chmod +x your-script.sh
                                        ./your-script.sh

                                        # Verify service behavior
                                        pgrep -f percona-telemetry-agent || echo "Telemetry agent is not running, as expected"
                                        """
                                    }
                                }
                            }
                        }
                    }

                    // Execute the parallel stages
                    parallel parallelStages
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
