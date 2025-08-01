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

                        error "This is a forced failure to demonstrate retry logic." // Simulate a failure for testing
                    }
                    
                    echo "Build stage succeeded."
                }
            }
        }
    }
}
