pipeline {
    agent {
        label 'docker'
    }
    
    parameters {
        string(name: 'RETRY_COUNT', defaultValue: '2', description: 'Number of retries on failure')
    }
    
    stages {
        stage('Build with Retry') {
            steps {
                script {
                    def retryCountInt = params.RETRY_COUNT.toInteger()
                    echo "Job_2 will retry up to ${retryCountInt} times on failure."
                    
                    retry(retryCountInt) {

                        echo "Running the build/test steps..."
                        
                        if (new Random().nextBoolean()) {
                            error "Simulated random failure."
                        }
                    }
                    
                    echo "Build stage succeeded."
                }
            }
        }
    }
}
