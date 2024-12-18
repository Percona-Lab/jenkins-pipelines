def call(String CLOUD_NAME, String FOLDER_NAME, String AWS_STASH_PATH) {
    String S3_STASH = (CLOUD_NAME == 'Hetzner') ? 'HTZ_STASH' : 'AWS_STASH'
    String S3_ENDPOINT = (CLOUD_NAME == 'Hetzner') ? '--endpoint-url https://fsn1.your-objectstorage.com' : '--endpoint-url https://s3.amazonaws.com'
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: S3_STASH, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            pwd
            S3_PATH=s3://percona-jenkins-artifactory/${AWS_STASH_PATH}
            aws s3 ls \$S3_PATH/${FOLDER_NAME} ${S3_ENDPOINT} || :
            aws s3 cp --recursive ${FOLDER_NAME} \$S3_PATH/${FOLDER_NAME} ${S3_ENDPOINT} || :
        """
    }
}

