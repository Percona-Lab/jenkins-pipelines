def call(Map config = [:]) {
    // ccacheDownload: Downloads ccache from S3 if available
    // Objects are tagged with RetentionDays for custom lifecycle policies
    //
    // CACHE RETENTION: Objects in S3 are automatically cleaned up based on tags
    // Configured retention periods: 7, 14, 21, 30 days
    // Default: 14 days (if not specified)
    //
    // Set default values
    def cloud = config.get('cloud', env.CLOUD ?: 'AWS')
    def awsCredentialsId = config.get('awsCredentialsId', 'AWS_CREDENTIALS_ID')
    def s3Bucket = config.get('s3Bucket', 's3://ps-build-cache/')
    def workspace = config.get('workspace', env.WORKSPACE)
    def dockerOs = config.get('dockerOs', env.DOCKER_OS)
    def cmakeBuildType = config.get('cmakeBuildType', env.CMAKE_BUILD_TYPE)
    def toolset = config.get('toolset', env.TOOLSET ?: '')
    def buildParamsType = config.get('buildParamsType', env.BUILD_PARAMS_TYPE ?: 'standard')
    def forceCacheMiss = config.get('forceCacheMiss', env.FORCE_CACHE_MISS == 'true')
    def serverVersion = config.get('serverVersion', env.SERVER_VERSION ?: '')
    def awsRetryMode = config.get('awsRetryMode', env.AWS_RETRY_MODE ?: 'standard')
    def awsRetries = config.get('awsRetries', env.AWS_RETRIES ?: '5')
    def cacheDir = config.get('cacheDir', '.ccache')
    def cacheArchiveName = config.get('cacheArchiveName', 'ccache.tar.gz')
    def cachePermissions = config.get('cachePermissions', '777')
    // AWS_MAX_ATTEMPTS includes the initial attempt, so add 1
    // See: https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-retries.html
    def awsMaxAttempts = (awsRetries.toInteger() + 1).toString()

    // Override credentials and endpoint for Hetzner
    if (cloud == 'Hetzner') {
        awsCredentialsId = config.get('awsCredentialsId', 'HTZ_STASH')
        // Use existing Hetzner bucket
        s3Bucket = config.get('s3Bucket', 's3://percona-jenkins-artifactory/')
    }
    def s3Endpoint = (cloud == 'Hetzner') ? '--endpoint-url https://fsn1.your-objectstorage.com' : ''

    if (env.USE_CCACHE != 'yes' && env.USE_CCACHE != 'true') {
        echo 'ccache disabled'
        return
    }

    // Use CCACHE_PROJECT environment parameter or config override
    def projectName = env.CCACHE_PROJECT ?: config.get('projectName', 'percona-server')

    // Build cache key with readable format
    def cleanDockerOs = dockerOs.replace(':', '-')

    // Collect all cache key components
    def keyComponents = [cleanDockerOs, cmakeBuildType]

    if (toolset) {
        keyComponents.add(toolset)
    }
    if (serverVersion) {
        keyComponents.add("mysql${serverVersion}")
    }

    keyComponents.add(buildParamsType)

    // Join components with underscores for better readability
    def cacheKey = keyComponents.join('_')

    echo "ccache key: ${cacheKey}"

    if (forceCacheMiss) {
        // Add timestamp to force cache miss for testing
        cacheKey = "${cacheKey}-${currentBuild.startTimeInMillis}"
        echo 'Forcing cache miss'
    }

    def s3Path = "${s3Bucket}ccache/${projectName}/${cacheKey}/${cacheArchiveName}"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                      credentialsId: awsCredentialsId,
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            export AWS_RETRY_MODE=${awsRetryMode}
            export AWS_MAX_ATTEMPTS=${awsMaxAttempts}

            echo "Downloading from: ${s3Path}"
            mkdir -p "${workspace}/${cacheDir}"
            chmod -R "${cachePermissions}" "${workspace}/${cacheDir}"

            START_TIME=\$(date +%s)
            if aws s3 cp ${s3Endpoint} --no-progress "${s3Path}" "${workspace}/${cacheArchiveName}" 2>/dev/null; then
                DOWNLOAD_TIME=\$(((\$(date +%s) - START_TIME)))
                FILE_SIZE=\$(ls -lh "${workspace}/${cacheArchiveName}" | awk '{print \$5}')

                cd "${workspace}"
                tar -xzf "${cacheArchiveName}"
                rm -f "${cacheArchiveName}"
                chmod -R "${cachePermissions}" "${cacheDir}/"

                echo "ccache downloaded (\${FILE_SIZE}) in \${DOWNLOAD_TIME}s"

                if command -v ccache >/dev/null 2>&1; then
                    echo "ccache statistics after download:"
                    CCACHE_DIR="${cacheDir}" ccache -s || true
                fi
            else
                echo "No ccache found"
            fi
        """
    }
}
