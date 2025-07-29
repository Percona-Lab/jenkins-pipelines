// OpenShift S3 state management library
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

def uploadState(Map config) {
    def required = ['bucket', 'clusterName', 'region', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    echo 'Backing up cluster state to S3...'

    // Create tarball of cluster state
    sh """
        cd ${config.workDir}
        tar -czf ${config.clusterName}-state.tar.gz ${config.clusterName}/
    """

    def s3Key = "openshift-state/${config.clusterName}/${config.clusterName}-state.tar.gz"
    def localPath = "${config.workDir}/${config.clusterName}-state.tar.gz"

    // Upload to S3 with metadata
    def metadataPairs = []
    if (config.metadata) {
        if (config.metadata instanceof Map) {
            config.metadata.each { k, v ->
                metadataPairs << "${k}=${v}"
            }
        }
    }

    def metadataString = metadataPairs ? "--metadata '${metadataPairs.join(',')}'" : ''

    sh """
        aws s3 cp ${localPath} s3://${config.bucket}/${s3Key} \
            --region ${config.region} \
            ${metadataString}
    """

    // Also save metadata JSON
    if (config.metadata) {
        saveMetadata([
            bucket: config.bucket,
            clusterName: config.clusterName,
            region: config.region
        ], config.metadata)
    }

    return "s3://${config.bucket}/${s3Key}"
}

def downloadState(Map config) {
    def required = ['bucket', 'clusterName', 'region', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def s3Key = "openshift-state/${config.clusterName}/${config.clusterName}-state.tar.gz"
    def localPath = "${config.workDir}/${config.clusterName}-state.tar.gz"

    echo 'Downloading cluster state from S3...'

    // Check if state exists
    def exists = sh(
        script: """
            aws s3api head-object \
                --bucket ${config.bucket} \
                --key ${s3Key} \
                --region ${config.region} >/dev/null 2>&1 && echo "true" || echo "false"
        """,
        returnStdout: true
    ).trim()

    if (exists != 'true') {
        echo "No state found in S3 for cluster: ${config.clusterName}"
        return false
    }

    // Download state
    sh """
        aws s3 cp s3://${config.bucket}/${s3Key} ${localPath} \
            --region ${config.region}
    """

    // Extract state
    sh """
        cd ${config.workDir}
        tar -xzf ${config.clusterName}-state.tar.gz
        rm -f ${config.clusterName}-state.tar.gz
    """

    return true
}

def saveMetadata(Map params, Map metadata) {
    def s3Key = "openshift-state/${params.clusterName}/metadata.json"
    def tempFile = "/tmp/${params.clusterName}-metadata.json"

    def json = new JsonBuilder(metadata).toPrettyString()
    writeFile file: tempFile, text: json

    sh """
        aws s3 cp ${tempFile} s3://${params.bucket}/${s3Key} \
            --region ${params.region} \
            --content-type "application/json"
        rm -f ${tempFile}
    """
}

def getMetadata(Map params) {
    def s3Key = "openshift-state/${params.clusterName}/metadata.json"

    try {
        def json = sh(
            script: """
                aws s3 cp s3://${params.bucket}/${s3Key} - \
                    --region ${params.region} 2>/dev/null || echo '{}'
            """,
            returnStdout: true
        ).trim()

        if (json && json != '{}') {
            return new JsonSlurper().parseText(json)
        }
    } catch (Exception e) {
        echo "Failed to get metadata: ${e.message}"
    }

    return null
}

def cleanup(Map params) {
    echo "Cleaning up S3 state for cluster: ${params.clusterName}"

    sh """
        aws s3 rm s3://${params.bucket}/openshift-state/${params.clusterName}/ \
            --recursive \
            --region ${params.region}
    """
}

def listClusters(Map params = [:]) {
    def bucket = params.bucket ?: 'percona-jenkins-artifactory'
    def region = params.region ?: 'us-east-2'

    def output = sh(
        script: """
            aws s3api list-objects-v2 \
                --bucket ${bucket} \
                --prefix openshift-state/ \
                --region ${region} \
                --query 'Contents[?ends_with(Key, `metadata.json`)].Key' \
                --output json
        """,
        returnStdout: true
    ).trim()

    if (!output || output == 'null') {
        return []
    }

    def s3Files = new JsonSlurper().parseText(output)
    def clusters = []

    s3Files.each { key ->
        def clusterName = key.split('/')[1]
        clusters << clusterName
    }

    return clusters
}

def getClusterAge(String bucket, String clusterName, String region) {
    try {
        def metadata = getMetadata([
            bucket: bucket,
            clusterName: clusterName,
            region: region
        ])

        if (metadata?.created_date) {
            def created = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", metadata.created_date)
            def now = new Date()
            def ageInHours = (now.time - created.time) / (1000 * 60 * 60)
            return ageInHours
        }
    } catch (Exception e) {
        echo "Failed to get cluster age: ${e.message}"
    }

    return -1
}

def archiveCluster(Map params) {
    def sourcePrefix = "openshift-state/${params.clusterName}/"
    def archivePrefix = "openshift-archive/${params.clusterName}/"

    echo "Archiving cluster ${params.clusterName}..."

    // Copy all objects to archive
    sh """
        aws s3 sync s3://${params.bucket}/${sourcePrefix} \
                    s3://${params.bucket}/${archivePrefix} \
                    --region ${params.region}
    """

    // Remove from active state
    cleanup(params)

    echo "Cluster ${params.clusterName} archived successfully"
}

// Backward compatibility
def call(String action, Map params) {
    switch (action) {
        case 'upload':
            return uploadState(params)
        case 'download':
            return downloadState(params)
        case 'cleanup':
            return cleanup(params)
        case 'getMetadata':
            return getMetadata(params)
        case 'saveMetadata':
            return saveMetadata(params)
        default:
            error "Unknown action: ${action}"
    }
}
