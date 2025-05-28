def call(Map config = [:]) {
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
    def cacheSize = config.get('cacheSize', env.CACHE_SIZE ?: '2G')

    if (env.USE_CCACHE != 'true') {
        echo 'ccache is disabled (USE_CCACHE != true)'
        return
    }

    echo '=== Uploading ccache ==='

    // Use CCACHE_PROJECT environment parameter or config override
    def projectName = env.CCACHE_PROJECT ?: config.get('projectName', 'percona-server')

    // Build cache key
    def cleanDockerOs = dockerOs.replace(':', '-')
    def compilerSuffix = compiler ? "-${compiler}" : ''
    def cacheKey = "${cleanDockerOs}-${cmakeBuildType}${compilerSuffix}-${buildParamsType}"

    if (forceCacheMiss) {
        echo 'Skipping cache upload for forced cache miss test'
        return
    }

    def s3Path = "${s3Bucket}ccache/${projectName}/${branch}/${cacheKey}/ccache.tar.gz"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                      credentialsId: awsCredentialsId,
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            if [ -d "${workspace}/.ccache" ]; then
                echo "Compressing ccache directory..."
                cd ${workspace}
                tar -czf ccache.tar.gz .ccache/

                echo "Uploading ccache to: ${s3Path}"
                aws s3 cp ccache.tar.gz ${s3Path}
                rm -f ccache.tar.gz

                echo "ccache uploaded successfully"

                # Show cache statistics
                echo "=== ccache Statistics ==="
                CACHE_SIZE="${cacheSize}"
                docker run --rm -v ${workspace}/.ccache:/tmp/ccache \\
                    -e CCACHE_DIR=/tmp/ccache \\
                    public.ecr.aws/e7j3v3n0/ps-build:${cleanDockerOs} \\
                    bash -c "ccache --max-size=\${CACHE_SIZE} && ccache -s" || true
            else
                echo "No ccache directory found, skipping upload"
            fi
        """
    }
}
