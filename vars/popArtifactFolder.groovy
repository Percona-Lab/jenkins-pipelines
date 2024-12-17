def call(String FOLDER_NAME, String AWS_STASH_PATH) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
	    pwd
            S3_PATH=s3://percona-jenkins-artifactory/${AWS_STASH_PATH}
            aws s3 cp --recursive \$S3_PATH/${FOLDER_NAME} ${FOLDER_NAME} || :
        """
    }
}

