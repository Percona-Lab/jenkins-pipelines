def call(Map config = [:]) {
    // ccacheUpload: Uploads ccache to S3 with retention tags
    // Objects are tagged with RetentionDays, Project, CacheType, and CreatedDate
    // for custom S3 lifecycle policies
    //
    // RETENTION PERIODS:
    // - Normal builds: 60 days
    // - Sanitizer builds (ASAN/Valgrind): 120 days
    //
    // S3 LIFECYCLE RULES (configured in ps-build-cache bucket):
    // - RetentionDays=7: Objects expire after 7 days
    // - RetentionDays=14: Objects expire after 14 days
    // - RetentionDays=21: Objects expire after 21 days
    // - RetentionDays=30: Objects expire after 30 days
    // - RetentionDays=60: Objects expire after 60 days (for normal builds)
    // - RetentionDays=120: Objects expire after 120 days (for sanitizer builds)
    // - Default: Objects without RetentionDays tag expire after 30 days
    //
    // Set default values
    def cloud = config.get('cloud', env.CLOUD ?: 'AWS')
    def awsCredentialsId = config.get('awsCredentialsId', 'AWS_CREDENTIALS_ID')
    def s3Bucket = config.get('s3Bucket', 's3://ps-build-cache/')
    def workspace = config.get('workspace', env.WORKSPACE)
    def branch = config.get('branch', env.BRANCH ?: '8.0')
    def dockerOs = config.get('dockerOs', env.DOCKER_OS)
    def cmakeBuildType = config.get('cmakeBuildType', env.CMAKE_BUILD_TYPE)
    def toolset = config.get('toolset', env.TOOLSET ?: '')
    def buildParamsType = config.get('buildParamsType', env.BUILD_PARAMS_TYPE ?: 'standard')
    def forceCacheMiss = config.get('forceCacheMiss', env.FORCE_CACHE_MISS == 'true')
    def serverVersion = config.get('serverVersion', env.SERVER_VERSION ?: '')
    // Default retention is 14 days, can be overridden by caller
    def cacheRetentionDays = config.get('cacheRetentionDays', '14')
    def awsRetryMode = config.get('awsRetryMode', env.AWS_RETRY_MODE ?: 'standard')
    def awsRetries = config.get('awsRetries', env.AWS_RETRIES ?: '5')
    def cacheDir = config.get('cacheDir', '.ccache')
    def cacheArchiveName = config.get('cacheArchiveName', 'ccache.tar.gz')
    def dockerRegistry = config.get('dockerRegistry', 'public.ecr.aws/e7j3v3n0')
    def dockerImage = config.get('dockerImage', 'ps-build')
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

    echo "Using retention period: ${cacheRetentionDays} days (${buildParamsType} build)"

    echo '=== Uploading ccache ==='

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
        echo 'Skipping cache upload for forced cache miss'
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

            # Check if directory exists
            if [ ! -d "${workspace}/${cacheDir}" ]; then
                echo "ccache directory missing"
                exit 0
            fi

            # Validate with ccache tool
            if command -v ccache >/dev/null 2>&1; then
                if CCACHE_DIR="${workspace}/${cacheDir}" ccache -s >/dev/null 2>&1; then
                    echo "ccache validation passed"
                else
                    echo "ccache integrity check failed"
                    exit 0
                fi
            fi

            if [ -d "${workspace}/${cacheDir}" ]; then
                cd "${workspace}"

                echo "Compressing cache..."
                COMPRESS_START=\$(date +%s)
                tar -czf "${cacheArchiveName}" "${cacheDir}/"
                COMPRESS_TIME=\$(((\$(date +%s) - COMPRESS_START)))
                FILE_SIZE=\$(ls -lh "${cacheArchiveName}" | awk '{print \$5}')

                echo "Cache compressed to \${FILE_SIZE} in \${COMPRESS_TIME}s"
                echo "Uploading to: ${s3Path}"

                # Upload cache archive
                UPLOAD_START=\$(date +%s)
                aws s3 cp ${s3Endpoint} --no-progress "${cacheArchiveName}" "${s3Path}" \
                    --metadata "project=${projectName},branch=${branch},build-type=${cmakeBuildType}"
                UPLOAD_TIME=\$(((\$(date +%s) - UPLOAD_START)))

                # Add retention tags for lifecycle policy
                aws s3api put-object-tagging ${s3Endpoint} \
                    --bucket \$(echo "${s3Path}" | cut -d'/' -f3) \
                    --key \$(echo "${s3Path}" | sed 's|s3://[^/]*/||') \
                    --tagging "TagSet=[{Key=RetentionDays,Value=${cacheRetentionDays}},{Key=Project,Value=${projectName}},{Key=CacheType,Value=ccache},{Key=CreatedDate,Value=\$(date -u +%Y-%m-%d)}]"

                rm -f "${cacheArchiveName}"

                echo "ccache uploaded (\${FILE_SIZE}) in \${UPLOAD_TIME}s"

                # Show cache statistics
                echo "ccache statistics after build:"
                docker run --rm -v "${workspace}/${cacheDir}:/tmp/ccache" \\
                    -e CCACHE_DIR=/tmp/ccache \\
                    "${dockerRegistry}/${dockerImage}:${cleanDockerOs}" \\
                    bash -c "ccache -s" || true

                # List all ccache buckets for this project
                echo "\nListing all ccache buckets for ${projectName}:"
                aws s3 ls ${s3Endpoint} "${s3Bucket}ccache/${projectName}/" --recursive | grep -E "ccache\\.tar\\.gz\$" | \\
                    awk '{print \$4}' | sed "s|ccache/${projectName}/||" | sed 's|/ccache.tar.gz||' | \\
                    sort -u | tail -20 || echo "Failed to list ccache buckets"
            else
                echo "No ccache directory found"
            fi
        """
    }
}
