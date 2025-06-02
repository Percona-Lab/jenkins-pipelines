def call(Map config = [:]) {
    // ccacheUpload: Uploads ccache to S3 with retention tags
    // Objects are tagged with RetentionDays, Project, CacheType, and CreatedDate
    // for custom S3 lifecycle policies
    // 
    // ALLOWED RETENTION DAYS: 7, 14, 21, 30
    // S3 lifecycle rules are configured for these specific retention periods only.
    // Using any other value will cause the cache to fall back to the default 30-day cleanup.
    //
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
        echo 'ccache disabled'
        return
    }

    // Validate retention days
    def allowedRetentionDays = ['7', '14', '21', '30']
    if (!allowedRetentionDays.contains(cacheRetentionDays)) {
        echo "WARNING: Invalid retention days '${cacheRetentionDays}'. Allowed values: ${allowedRetentionDays.join(', ')}"
        echo "WARNING: Cache will use default 30-day cleanup instead of ${cacheRetentionDays} days"
        echo "INFO: To use custom retention, update S3 lifecycle rules in ps-build-cache bucket"
    }

    echo '=== Uploading ccache ==='

    // Use CCACHE_PROJECT environment parameter or config override
    def projectName = env.CCACHE_PROJECT ?: config.get('projectName', 'percona-server')

    // Build cache key with readable format
    def cleanDockerOs = dockerOs.replace(':', '-')

    // Collect all cache key components
    def keyComponents = [cleanDockerOs, cmakeBuildType]

    if (compiler && compiler != 'default') {
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
                aws s3 cp --no-progress "${cacheArchiveName}" "${s3Path}" \
                    --metadata "project=${projectName},branch=${branch},build-type=${cmakeBuildType}"
                UPLOAD_TIME=\$(((\$(date +%s) - UPLOAD_START)))

                # Add retention tags for lifecycle policy
                aws s3api put-object-tagging \
                    --bucket \$(echo "${s3Path}" | cut -d'/' -f3) \
                    --key \$(echo "${s3Path}" | sed 's|s3://[^/]*/||') \
                    --tagging "TagSet=[{Key=RetentionDays,Value=${cacheRetentionDays}},{Key=Project,Value=${projectName}},{Key=CacheType,Value=ccache},{Key=CreatedDate,Value=\$(date -u +%Y-%m-%d)}]"

                rm -f "${cacheArchiveName}"

                echo "ccache uploaded (\${FILE_SIZE}) in \${UPLOAD_TIME}s"

                # Show cache statistics
                CACHE_SIZE="${cacheSize}"
                echo "ccache statistics after build:"
                docker run --rm -v "${workspace}/${cacheDir}:/tmp/ccache" \\
                    -e CCACHE_DIR=/tmp/ccache \\
                    "${dockerRegistry}/${dockerImage}:${cleanDockerOs}" \\
                    bash -c "
                        ccache --max-size=\${CACHE_SIZE}
                        ccache -s
                    " || true
            else
                echo "No ccache directory found"
            fi
        """
    }
}
