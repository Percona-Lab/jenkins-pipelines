pipeline {
    agent {
        label 'docker'
    }
    
    parameters {
        string(name: 'RETRY_COUNT', defaultValue: '2', description: 'Number of retries on failure')
    }
    
    stages {
        stage('Run Tests') {
            parallel {
                stage('cluster1') {
                    steps {
                        script {
                            def retryCountInt = params.RETRY_COUNT.toInteger()
                            retry(retryCountInt) {
                                echo "Running the build/test steps... cluster1"
                                error "This is a first forced failure to demonstrate cluster1 retry logic." // Simulate a failure for testing
                            }
                        }
                    }
                }

                stage('cluster2') {
                    steps {
                        script {
                            def retryCountInt = params.RETRY_COUNT.toInteger()
                            retry(retryCountInt) {
                                echo "Running the build/test steps... cluster2"
                            }
                        }
                    }
                }
            }
        }
    }
}
