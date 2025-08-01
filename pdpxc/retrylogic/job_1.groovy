pipeline {
    agent any
    
    parameters {
        string(name: 'RETRY_COUNT', defaultValue: '2', description: 'Number of retries for Job_2')
    }
    
    stages {
        stage('Trigger Job_2') {
            steps {
                script {
                        withCredentials([string(credentialsId: 'JNKPERCONA_CLOUD_TOKEN', variable: 'TOKEN')]) {
                                sh """
                                        curl -X POST \
                                        -u ${TOKEN} \
                                        --data-urlencode RETRY_COUNT=${params.RETRY_COUNT} https://cloud.cd.percona.com/job/Job_2/buildWithParameters
                                """
                        }
                }
            }
        }
    }
}
