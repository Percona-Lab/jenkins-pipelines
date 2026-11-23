// valkeyPopArtifactFolder — download a stashed folder in the Valkey package
// jobs from the S3 stash that pushArtifactFolder uploaded it to.
//
// pushArtifactFolder writes to Hetzner S3 when CLOUD is 'Hetzner' and to AWS
// S3 otherwise. popArtifactFolder always reads Hetzner first, so on a CLOUD=AWS
// run it found an empty folder every time, fell back to AWS and marked the
// build UNSTABLE although nothing was wrong.
//
// CLOUD=Hetzner: unchanged, popArtifactFolder (Hetzner, AWS fallback, UNSTABLE
// on fallback). Any other cloud: read AWS directly.
def call(String cloud, String folder, String stashPath, String s3Filter = '') {
    if (cloud == 'Hetzner') {
        popArtifactFolder(cloud, folder, stashPath, s3Filter)
        return
    }
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AWS_STASH', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        retry(15) {
            sh """
                pwd
                S3_PATH=s3://percona-jenkins-artifactory/${stashPath}
                AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=10 aws s3 cp --recursive \$S3_PATH/${folder} ${folder} ${s3Filter} --endpoint-url https://s3.amazonaws.com --cli-connect-timeout 60 --cli-read-timeout 120
            """
        }
    }
}
