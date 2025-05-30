def call(Map config = [:]) {
    // ccacheDownload: Downloads ccache from S3 if available
    // Objects are tagged with RetentionDays for custom lifecycle policies
    // Set default values
    def awsCredentialsId = config.get('awsCredentialsId', 'AWS_CREDENTIALS_ID')
    def s3Bucket = config.get('s3Bucket', 's3://ps-build-cache/')
    def workspace = config.get('workspace', env.WORKSPACE)
    def jobName = config.get('jobName', env.JOB_NAME)
    def branch = config.get('branch', env.BRANCH ?: '8.0')
    def dockerOs = config.get('dockerOs', env.DOCKER_OS)
    def cmakeBuildType = config.get('cmakeBuildType', env.CMAKE_BUILD_TYPE)
    def compiler = config.get('compiler', env.COMPILER ?: '')
    def buildParamsType = config.get('buildParamsType', env.BUILD_PARAMS_TYPE ?: 'standard')
    def forceCacheMiss = config.get('forceCacheMiss', env.FORCE_CACHE_MISS == 'true')
    def mysqlVersion = config.get('mysqlVersion', env.MYSQL_VERSION ?: '')
    def compilerVersion = config.get('compilerVersion', env.COMPILER_VERSION ?: '')
    def awsRetryMode = config.get('awsRetryMode', env.AWS_RETRY_MODE ?: 'standard')
    def awsRetries = config.get('awsRetries', env.AWS_RETRIES ?: '5')
    def cacheDir = config.get('cacheDir', '.ccache')
    def cacheArchiveName = config.get('cacheArchiveName', 'ccache.tar.gz')
    def cachePermissions = config.get('cachePermissions', '777')
    // AWS_MAX_ATTEMPTS includes the initial attempt, so add 1
    // See: https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-retries.html
    def awsMaxAttempts = (awsRetries.toInteger() + 1).toString()

    if (env.USE_CCACHE != 'true') {
        echo 'ccache is disabled (USE_CCACHE != true)'
        return
    }

    echo '=== Downloading ccache ==='

    // Use CCACHE_PROJECT environment parameter or config override
    def projectName = env.CCACHE_PROJECT ?: config.get('projectName', 'percona-server')

    // Build cache key with readable format
    def cleanDockerOs = dockerOs.replace(':', '-')

    // Collect all cache key components
    def keyComponents = [cleanDockerOs, cmakeBuildType]

    if (compiler) {
        keyComponents.add(compiler)
    }
    if (compilerVersion) {
        keyComponents.add("v${compilerVersion}")
    }
    if (mysqlVersion) {
        keyComponents.add("mysql${mysqlVersion}")
    }

    keyComponents.add(buildParamsType)
    
    // Add branch to cache key (replace / with - for branch names like release/8.0.35)
    keyComponents.add(branch.replace('/', '-'))

    // Join components with underscores for better readability
    def cacheKey = keyComponents.join('_')

    if (forceCacheMiss) {
        // Add timestamp to force cache miss for testing
        cacheKey = "${cacheKey}-${currentBuild.startTimeInMillis}"
        echo "Forcing cache miss with unique key: ${cacheKey}"
    }

    def s3Path = "${s3Bucket}ccache/${projectName}/${cacheKey}/${cacheArchiveName}"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                      credentialsId: awsCredentialsId,
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            # Configure AWS CLI retry behavior
            export AWS_RETRY_MODE=${awsRetryMode}
            export AWS_MAX_ATTEMPTS=${awsMaxAttempts}

            echo "Attempting to download ccache from: ${s3Path}"
            echo "AWS CLI configured with retry mode: ${awsRetryMode}, retries: ${awsRetries} (max attempts: ${awsMaxAttempts})"
            mkdir -p "${workspace}/${cacheDir}"
            # Set permissions to allow Docker container to write
            chmod -R "${cachePermissions}" "${workspace}/${cacheDir}"

            # Try to download cache from S3
            if aws s3 cp "${s3Path}" "${workspace}/${cacheArchiveName}" 2>/dev/null; then
                echo "Successfully downloaded ccache archive from ${s3Path}"
                cd "${workspace}"
                tar -xzf "${cacheArchiveName}"
                rm -f "${cacheArchiveName}"
                echo "ccache extracted successfully"
                # Ensure permissions are set after extraction
                chmod -R "${cachePermissions}" "${cacheDir}/"

                # Verify cache integrity using ccache built-in checks
                echo "Verifying cache integrity..."
                if command -v ccache >/dev/null 2>&1; then
                    CCACHE_DIR="${cacheDir}" ccache -sv | grep -E "Files:|Cache size:" || true
                fi
            else
                echo "No existing ccache found at ${s3Path} (object may have expired or does not exist)"
                echo "Starting with empty ccache"
            fi
        """
    }
}
