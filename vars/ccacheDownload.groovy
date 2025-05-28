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

    if (env.USE_CCACHE != 'true') {
        echo 'ccache is disabled (USE_CCACHE != true)'
        return
    }

    echo '=== Downloading ccache ==='

    // Use CCACHE_PROJECT environment parameter or config override
    def projectName = env.CCACHE_PROJECT ?: config.get('projectName', 'percona-server')

    // Build cache key
    def cleanDockerOs = dockerOs.replace(':', '-')
    def compilerSuffix = compiler ? "-${compiler}" : ''
    def cacheKey = "${cleanDockerOs}-${cmakeBuildType}${compilerSuffix}-${buildParamsType}"

    if (forceCacheMiss) {
        // Add timestamp to force cache miss for testing
        cacheKey = "${cacheKey}-${currentBuild.startTimeInMillis}"
        echo "Forcing cache miss with unique key: ${cacheKey}"
    }

    def s3Path = "${s3Bucket}ccache/${projectName}/${branch}/${cacheKey}/ccache.tar.gz"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                      accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                      credentialsId: awsCredentialsId,
                      secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            echo "Attempting to download ccache from: ${s3Path}"
            mkdir -p ${workspace}/.ccache

            if aws s3 cp ${s3Path} ${workspace}/ccache.tar.gz; then
                echo "Successfully downloaded ccache archive"
                cd ${workspace}
                tar -xzf ccache.tar.gz
                rm -f ccache.tar.gz
                echo "ccache extracted successfully"
                ls -la .ccache/
            else
                echo "No existing ccache found at ${s3Path} or download failed"
                echo "Starting with empty ccache"
            fi
        """
    }
}
