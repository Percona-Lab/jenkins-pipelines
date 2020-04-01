def call(String FOLDER_NAME, String AWS_STASH_PATH) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/${AWS_STASH_PATH}
            aws s3 ls \$S3_PATH/${FOLDER_NAME} || :
            aws s3 cp --quiet --recursive ${FOLDER_NAME} \$S3_PATH/${FOLDER_NAME} || :
        """
    }
}

