void auth(String cloudSdkConfig = env.CLOUDSDK_CONFIG) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        if (!cloudSdkConfig) {
            error("CLOUDSDK_CONFIG must be set before running gcloud auth")
        }

        withEnv(["CLOUDSDK_CONFIG=${cloudSdkConfig}"]) {
            sh '''
                mkdir -p "$CLOUDSDK_CONFIG"
                gcloud auth activate-service-account --key-file "$CLIENT_SECRET_FILE"
                gcloud config set project "$GCP_PROJECT"
            '''
        }
    }
}

return this
