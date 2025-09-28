/**
 * OpenShift S3 state management library.
 *
 * Provides functionality for storing and retrieving OpenShift cluster state
 * in Amazon S3. Handles cluster metadata, state backups, and lifecycle management.
 * Uses AWS CLI for all S3 operations to avoid Jenkins sandbox and serialization issues.
 *
 * @since 2.0.0 - Refactored to use AWS CLI instead of SDK
 */
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

/**
 * Uploads OpenShift cluster state to S3 for backup and recovery.
 *
 * Creates a compressed tarball of the cluster directory including all
 * configuration files, certificates, and state. Uploads to S3 with
 * metadata for lifecycle management and disaster recovery.
 *
 * @param config Map containing upload configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *   - workDir: Working directory containing cluster files (required)
 *   - metadata: Additional metadata to store (optional)
 *
 * @return String S3 URI of uploaded state file (s3://bucket/cluster/file)
 *
 * @throws IllegalArgumentException When required parameters are missing
 * @throws RuntimeException When S3 upload fails or cluster files are not found
 *
 * @since 2.0.0
 *
 * @example
 * def uri = openshiftS3.uploadState([
 *     bucket: 'my-clusters',
 *     clusterName: 'test-cluster',
 *     region: 'us-east-2',
 *     workDir: env.WORKSPACE
 * ])
 */
def uploadState(Map config) {
    def required = ['bucket', 'clusterName', 'region', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    openshiftTools.log('INFO', 'Backing up cluster state to S3...', config)

    def s3Uri = "s3://${config.bucket}/${config.clusterName}/cluster-state.tar.gz"

    try {
        // Create compressed tarball and upload to S3 using AWS CLI
        sh """
            cd '${config.workDir}'
            tar -czf cluster-state.tar.gz '${config.clusterName}/'
            aws s3 cp cluster-state.tar.gz '${s3Uri}' --region ${config.region}
            rm -f cluster-state.tar.gz
        """

        // Save metadata if provided
        if (config.metadata) {
            saveMetadata([
                bucket: config.bucket,
                clusterName: config.clusterName,
                region: config.region
            ], config.metadata)
        }

        openshiftTools.log('INFO', "Cluster state uploaded successfully: ${s3Uri}", config)
        return s3Uri

    } catch (Exception e) {
        error "Failed to upload state to S3: ${e.message}"
    }
}

/**
 * Downloads OpenShift cluster state from S3 and extracts it.
 *
 * Retrieves the cluster state tarball from S3 and extracts all files
 * to the working directory. Used for cluster recovery or destruction
 * operations that require access to cluster configuration and credentials.
 *
 * @param config Map containing download configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *   - workDir: Directory where state will be extracted (required)
 *
 * @return boolean true if state was found and downloaded, false if not found
 *
 * @throws IllegalArgumentException When required parameters are missing
 * @throws RuntimeException When S3 download fails (other than 404)
 *
 * @since 2.0.0
 *
 * @example
 * if (openshiftS3.downloadState([...]))
 *     println 'State restored successfully'
 * }
 */
def downloadState(Map config) {
    def required = ['bucket', 'clusterName', 'region', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def s3Uri = "s3://${config.bucket}/${config.clusterName}/cluster-state.tar.gz"
    openshiftTools.log('INFO', 'Downloading cluster state from S3...', config)

    // Check if the state file exists in S3
    def existsResult = sh(
        script: "aws s3 ls '${s3Uri}' --region ${config.region} 2>/dev/null | wc -l",
        returnStdout: true
    ).trim()

    if (existsResult == '0') {
        openshiftTools.log('WARN', "No state found in S3 for cluster: ${config.clusterName}", config)
        return false
    }

    try {
        // Download and extract the state
        def tempFile = "state-${System.currentTimeMillis()}.tar.gz"
        sh """
            aws s3 cp '${s3Uri}' '${tempFile}' --region ${config.region}

            # Validate the downloaded file
            if ! file '${tempFile}' | grep -q 'gzip compressed data'; then
                echo "ERROR: Downloaded file is not a valid gzip archive"
                rm -f '${tempFile}'
                exit 1
            fi

            # Extract the state
            cd '${config.workDir}'
            tar -xzf '../${tempFile}'
            rm -f '../${tempFile}'
        """

        openshiftTools.log('INFO', "Cluster state downloaded and extracted successfully", config)
        return true

    } catch (Exception e) {
        // Check if it's a genuine error or just corrupt/test data
        if (e.message.contains('not a valid gzip archive')) {
            openshiftTools.log('WARN', "Cluster state file is corrupt or invalid for ${config.clusterName}. This may be test data.", config)
            return false
        }
        error "Failed to download state from S3: ${e.message}"
    }
}

/**
 * Saves cluster metadata as JSON to S3.
 *
 * Stores metadata separately from the main state file for quick access
 * without downloading the full cluster state tarball.
 *
 * NOTE: Changed from metadata.json to cluster-metadata.json to avoid
 * overwriting the OpenShift installer's metadata.json file which is
 * required for openshift-install destroy operations.
 *
 * @param params Map containing S3 configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 * @param metadata Map containing metadata to save (required)
 *
 * @throws RuntimeException When S3 upload fails
 *
 * @since 2.0.0
 * @since 2.1.0 - Changed filename to cluster-metadata.json
 */
def saveMetadata(Map params, Map metadata) {
    def json = new JsonBuilder(metadata).toPrettyString()
    def s3Uri = "s3://${params.bucket}/${params.clusterName}/cluster-metadata.json"

    try {
        sh """
            cat > cluster-metadata.json <<'EOF'
${json}
EOF
            aws s3 cp cluster-metadata.json '${s3Uri}' --region ${params.region}
            rm -f cluster-metadata.json
        """
        openshiftTools.log('INFO', "Metadata saved successfully to ${s3Uri}", params)
    } catch (Exception e) {
        error "Failed to save metadata to S3: ${e.message}"
    }
}

/**
 * Retrieves cluster metadata JSON from S3.
 *
 * Fetches and parses the cluster-metadata.json file for a specific cluster.
 * Returns null if not found rather than throwing an error.
 *
 * NOTE: Changed from metadata.json to cluster-metadata.json to avoid
 * conflicts with the OpenShift installer's metadata.json file which is
 * required for openshift-install destroy operations.
 *
 * @param params Map containing retrieval configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *
 * @return Map parsed metadata or null if not found
 *
 * @since 2.0.0
 * @since 2.1.0 - Changed filename to cluster-metadata.json (no fallback)
 *
 * @example
 * def metadata = openshiftS3.getMetadata([
 *     bucket: 'my-clusters',
 *     clusterName: 'test-cluster',
 *     region: 'us-east-2'
 * ])
 * if (metadata) {
 *     println "Cluster created by: ${metadata.created_by}"
 * }
 */
def getMetadata(Map params) {
    // Use cluster-metadata.json - metadata.json is reserved for OpenShift installer
    def s3Uri = "s3://${params.bucket}/${params.clusterName}/cluster-metadata.json"

    // Check if metadata exists
    def existsResult = sh(
        script: "aws s3 ls '${s3Uri}' --region ${params.region} 2>/dev/null | wc -l",
        returnStdout: true
    ).trim()

    if (existsResult == '0') {
        return null
    }

    try {
        // Download and parse metadata
        def jsonContent = sh(
            script: "aws s3 cp '${s3Uri}' - --region ${params.region}",
            returnStdout: true
        ).trim()

        // Convert LazyMap to regular Map for serialization
        def lazyMap = new JsonSlurper().parseText(jsonContent)
        return lazyMap.collectEntries { k, v -> [k, v] }
    } catch (Exception e) {
        openshiftTools.log('WARN', "Failed to retrieve metadata: ${e.message}", params)
        return null
    }
}

/**
 * Cleans up all S3 objects for a specific cluster.
 *
 * Removes all files stored under the cluster prefix including state,
 * metadata, and backups. Used during cluster destruction or cleanup operations.
 *
 * @param params Map containing cleanup configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *
 * @return Map with cleanup results
 *
 * @since 2.0.0
 *
 * @example
 * openshiftS3.cleanup([
 *     bucket: 'my-clusters',
 *     clusterName: 'test-cluster',
 *     region: 'us-east-2'
 * ])
 */
def cleanup(Map params) {
    def required = ['bucket', 'clusterName', 'region']
    required.each { param ->
        if (!params.containsKey(param) || !params[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    openshiftTools.log('INFO', "Cleaning up S3 objects for cluster: ${params.clusterName}", params)

    try {
        // Delete all objects with the cluster prefix
        def s3Prefix = "s3://${params.bucket}/${params.clusterName}/"

        // First list objects to log what will be deleted
        def objects = sh(
            script: "aws s3 ls '${s3Prefix}' --recursive --region ${params.region} 2>/dev/null || true",
            returnStdout: true
        ).trim()

        if (objects) {
            openshiftTools.log('INFO', "Deleting S3 objects:\n${objects}", params)

            // Delete all objects
            sh "aws s3 rm '${s3Prefix}' --recursive --region ${params.region}"

            return [success: true, deleted: objects.split('\n').size()]
        } else {
            openshiftTools.log('INFO', "No S3 objects found for cluster: ${params.clusterName}", params)
            return [success: true, deleted: 0]
        }
    } catch (Exception e) {
        error "Failed to cleanup S3 objects: ${e.message}"
    }
}

/**
 * Lists all clusters stored in the S3 bucket.
 *
 * Scans the bucket for cluster prefixes and retrieves metadata for each cluster.
 * Returns a list of cluster information including creation time and status.
 *
 * @param params Map containing optional configuration:
 *   - bucket: S3 bucket name (default from environment)
 *   - region: AWS region (default from environment)
 *
 * @return List of Map objects containing cluster information
 *
 * @since 2.0.0
 *
 * @example
 * def clusters = openshiftS3.listClusters()
 * clusters.each { cluster ->
 *     println "${cluster.name} - ${cluster.created_at}"
 * }
 */
def listClusters(Map params = [:]) {
    def bucket = params.bucket ?: env.S3_BUCKET ?: 'openshift-cluster-state-test'
    def region = params.region ?: env.AWS_REGION ?: 'us-east-2'

    openshiftTools.log('INFO', "Listing clusters in bucket: ${bucket}", params)

    try {
        // List all top-level prefixes (cluster names)
        def listOutput = sh(
            script: """
                aws s3api list-objects-v2 \
                    --bucket '${bucket}' \
                    --delimiter '/' \
                    --region ${region} \
                    --query 'CommonPrefixes[].Prefix' \
                    --output text 2>/dev/null || echo ''
            """,
            returnStdout: true
        ).trim()

        // AWS CLI returns "None" when no results with --output text
        if (!listOutput || listOutput == 'None') {
            return []
        }

        def clusters = []
        listOutput.split('\t').each { prefix ->
            def clusterName = prefix.replaceAll('/$', '')

            // Get metadata for each cluster
            def metadata = getMetadata([
                bucket: bucket,
                clusterName: clusterName,
                region: region
            ])

            if (metadata) {
                clusters.add([
                    name: clusterName,
                    created_at: metadata.created_at,
                    created_by: metadata.created_by,
                    region: metadata.region ?: region,
                    openshift_version: metadata.openshift_version
                ])
            } else {
                // If no metadata, just add basic info
                clusters.add([
                    name: clusterName,
                    region: region
                ])
            }
        }

        return clusters
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to list clusters: ${e.message}", params)
        return []
    }
}

/**
 * Uploads auth backup to S3.
 *
 * Creates a backup of the auth directory containing kubeconfig and credentials.
 * This is kept as a separate backup for easier access without full state download.
 *
 * @param config Map containing upload configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - authDir: Path to auth directory (required)
 *   - workDir: Working directory (required)
 *   - region: AWS region (required)
 *
 * @return String S3 URI of uploaded auth backup
 *
 * @since 2.0.0
 */
def uploadAuthBackup(Map config) {
    def required = ['bucket', 'clusterName', 'authDir', 'workDir', 'region']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    openshiftTools.log('INFO', 'Backing up auth directory to S3...', config)

    def s3Uri = "s3://${config.bucket}/${config.clusterName}/auth-backup.tar.gz"

    try {
        sh """
            cd '${config.authDir}/..'
            tar -czf auth-backup.tar.gz auth/
            aws s3 cp auth-backup.tar.gz '${s3Uri}' --region ${config.region}
            rm -f auth-backup.tar.gz
        """

        openshiftTools.log('INFO', "Auth backup uploaded successfully: ${s3Uri}", config)
        return s3Uri

    } catch (Exception e) {
        error "Failed to upload auth backup to S3: ${e.message}"
    }
}

/**
 * Ensures an S3 bucket exists, creating it if necessary.
 *
 * Checks if the specified bucket exists and creates it with proper
 * configuration if it doesn't. Handles region-specific bucket creation rules.
 *
 * @param bucketName Name of the S3 bucket (required)
 * @param region AWS region for the bucket (required)
 *
 * @return boolean true if bucket exists or was created, false on error
 *
 * @since 2.0.0
 */
def ensureS3BucketExists(String bucketName, String region) {
    try {
        // Check if bucket exists
        def existsResult = sh(
            script: "aws s3api head-bucket --bucket '${bucketName}' --region ${region} 2>&1",
            returnStatus: true
        )

        if (existsResult == 0) {
            openshiftTools.log('INFO', "S3 bucket already exists: ${bucketName}")
            return true
        }

        // Create bucket if it doesn't exist
        openshiftTools.log('INFO', "Creating S3 bucket: ${bucketName} in region ${region}")

        if (region == 'us-east-1') {
            // us-east-1 doesn't accept LocationConstraint
            sh "aws s3api create-bucket --bucket '${bucketName}' --region ${region}"
        } else {
            sh """
                aws s3api create-bucket \
                    --bucket '${bucketName}' \
                    --region ${region} \
                    --create-bucket-configuration LocationConstraint=${region}
            """
        }

        // Enable versioning for safety
        sh "aws s3api put-bucket-versioning --bucket '${bucketName}' --versioning-configuration Status=Enabled --region ${region}"

        openshiftTools.log('INFO', "S3 bucket created successfully: ${bucketName}")
        return true

    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to ensure S3 bucket exists: ${e.message}")
        return false
    }
}