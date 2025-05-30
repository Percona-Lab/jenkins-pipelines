def call(Map config = [:]) {
    // ccacheUpload: Uploads ccache to S3 with retention tags
    // Objects are tagged with RetentionDays, Project, CacheType, and CreatedDate
    // for custom S3 lifecycle policies
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
    def cacheSize = config.get('cacheSize', env.CACHE_SIZE ?: '8G')
    def mysqlVersion = config.get('mysqlVersion', env.MYSQL_VERSION ?: '')
    def compilerVersion = config.get('compilerVersion', env.COMPILER_VERSION ?: '')
    def cacheRetentionDays = config.get('cacheRetentionDays', env.CACHE_RETENTION_DAYS ?: '14')
    def awsRetryMode = config.get('awsRetryMode', env.AWS_RETRY_MODE ?: 'standard')
    def awsRetries = config.get('awsRetries', env.AWS_RETRIES ?: '5')
    def cacheDir = config.get('cacheDir', '.ccache')
    def cacheArchiveName = config.get('cacheArchiveName', 'ccache.tar.gz')
    def cachePermissions = config.get('cachePermissions', '777')
    def dockerRegistry = config.get('dockerRegistry', 'public.ecr.aws/e7j3v3n0')
    def dockerImage = config.get('dockerImage', 'ps-build')
    // AWS_MAX_ATTEMPTS includes the initial attempt, so add 1
    // See: https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-retries.html
    def awsMaxAttempts = (awsRetries.toInteger() + 1).toString()

    if (env.USE_CCACHE != 'true') {
        echo 'ccache is disabled (USE_CCACHE != true)'
        return
    }

    echo '=== Uploading ccache ==='

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
        echo 'Skipping cache upload for forced cache miss test'
        return
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

            # Comprehensive ccache validation before upload
            echo "=== Validating ccache before upload ==="

            # Check if directory exists
            if [ ! -d "${workspace}/${cacheDir}" ]; then
                echo "✗ ccache directory missing - skipping upload"
                exit 0
            fi


            # Validate with ccache tool
            if command -v ccache >/dev/null 2>&1; then
                if CCACHE_DIR="${workspace}/${cacheDir}" ccache -s >/dev/null 2>&1; then
                    echo "✓ ccache integrity check passed"
                else
                    echo "✗ ccache integrity check failed - skipping upload"
                    exit 0
                fi
            else
                echo "⚠ ccache tool not available for validation, proceeding with upload"
            fi

            echo "=== Proceeding with cache upload ==="

            if [ -d "${workspace}/${cacheDir}" ]; then
                echo "Compressing ccache directory..."
                cd "${workspace}"
                tar -czf "${cacheArchiveName}" "${cacheDir}/"

                echo "Uploading ccache to: ${s3Path}"
                echo "Cache retention period: ${cacheRetentionDays} days"
                echo "AWS CLI configured with retry mode: ${awsRetryMode}, retries: ${awsRetries} (max attempts: ${awsMaxAttempts})"

                # Upload cache archive
                aws s3 cp --no-progress "${cacheArchiveName}" "${s3Path}" \
                    --metadata "project=${projectName},branch=${branch},build-type=${cmakeBuildType}"

                # Add retention tags for lifecycle policy
                aws s3api put-object-tagging \
                    --bucket \$(echo "${s3Path}" | cut -d'/' -f3) \
                    --key \$(echo "${s3Path}" | sed 's|s3://[^/]*/||') \
                    --tagging "TagSet=[{Key=RetentionDays,Value=${cacheRetentionDays}},{Key=Project,Value=${projectName}},{Key=CacheType,Value=ccache},{Key=CreatedDate,Value=\$(date -u +%Y-%m-%d)}]"

                rm -f "${cacheArchiveName}"

                echo "ccache uploaded successfully"

                # Show cache statistics and verify integrity
                echo "=== ccache Statistics ==="
                CACHE_SIZE="${cacheSize}"
                docker run --rm -v "${workspace}/${cacheDir}:/tmp/ccache" \\
                    -e CCACHE_DIR=/tmp/ccache \\
                    "${dockerRegistry}/${dockerImage}:${cleanDockerOs}" \\
                    bash -c "
                        ccache --max-size=\${CACHE_SIZE}
                        # Show detailed stats including integrity info
                        ccache -sv
                        # Extract key metrics for monitoring
                        echo '=== Key Metrics ==='
                        ccache -s | grep -E 'Hits:|Misses:|Cache size'
                    " || true
            else
                echo "No ccache directory found, skipping upload"
            fi
        """
    }
}
