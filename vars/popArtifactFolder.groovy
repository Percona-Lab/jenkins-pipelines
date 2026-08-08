def call(String CLOUD_NAME, String FOLDER_NAME, String AWS_STASH_PATH, String S3_FILTER = '') {
    def useAWS = false
    try {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'HTZ_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            retry(15) {
                sh """
                    pwd
                    S3_PATH=s3://percona-jenkins-artifactory/${AWS_STASH_PATH}
                    AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp --recursive \$S3_PATH/${FOLDER_NAME} ${FOLDER_NAME} ${S3_FILTER} --endpoint-url https://fsn1.your-objectstorage.com --cli-connect-timeout 60 --cli-read-timeout 120
                """
            }
            if (sh(script: "ls -A ${FOLDER_NAME} 2>/dev/null | head -1", returnStdout: true).trim().isEmpty()) {
                echo "WARNING: No files downloaded from Hetzner endpoint, falling back to AWS."
                useAWS = true
            }
        }
    } catch (Exception e) {
        echo "Hetzner endpoint failed: ${e.message}. Falling back to AWS endpoint."
        useAWS = true
    }

    if (useAWS) {
        unstable("⚠️ Hetzner S3 endpoint unavailable or returned empty folder, fell back to AWS")
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            retry(15) {
                sh """
                    pwd
                    S3_PATH=s3://percona-jenkins-artifactory/${AWS_STASH_PATH}
                    AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp --recursive \$S3_PATH/${FOLDER_NAME} ${FOLDER_NAME} ${S3_FILTER} --endpoint-url https://s3.amazonaws.com --cli-connect-timeout 60 --cli-read-timeout 120
                """
            }
        }
    }
}
