pipeline {
    agent {
        label 'docker'
    }
    option{
        retry(2)
    }

    stages {
        stage('Run Tests') {
            parallel {
                stage('cluster1') {
                    steps {
                        script {
                                echo "Running the build/test steps... cluster1"
                                error "This is a first forced failure to demonstrate cluster1 retry logic." // Simulate a failure for testing
                
                        }
                    }
                }

                stage('cluster2') {
                    steps {
                        script {
                                echo "Running the build/test steps... cluster2"
                                // OUR PDPXC test logic goes here
                        }
                    }
                }
            }
        }
    }
}
