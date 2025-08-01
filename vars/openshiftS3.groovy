/**
 * OpenShift S3 state management library.
 *
 * Provides functionality for storing and retrieving OpenShift cluster state
 * in Amazon S3. Handles cluster metadata, state backups, and lifecycle management.
 * Uses AWS SDK for Java with proper credential handling and error recovery.
 *
 * @since 1.0.0
 */
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.*
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import java.io.File

/**
 * Helper method to build S3 client with credentials from environment if available.
 *
 * Creates an AWS S3 client with proper credential precedence:
 * 1. Explicitly passed credentials (highest priority)
 * 2. Environment variables (from withCredentials block)
 * 3. EC2 instance profile (fallback)
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * @param region AWS region for the S3 client (required)
 * @param accessKey AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 * @param secretKey AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *
 * @return AmazonS3 Configured S3 client instance
 *
 * @since 1.0.0
 */
@NonCPS
def buildS3Client(String region, String accessKey = null, String secretKey = null) {
    // Credential precedence: parameter > environment > instance profile
    def awsAccessKey = accessKey ?: System.getenv('AWS_ACCESS_KEY_ID')
    def awsSecretKey = secretKey ?: System.getenv('AWS_SECRET_ACCESS_KEY')

    if (awsAccessKey && awsSecretKey) {
        def awsCreds = new BasicAWSCredentials(awsAccessKey, awsSecretKey)
        return AmazonS3ClientBuilder.standard()
            .withRegion(region)
            .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
            .build()
    } else {
        return AmazonS3ClientBuilder.standard()
            .withRegion(region)
            .build()
    }
}

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
 *   - accessKey: AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *
 * @return String S3 URI of uploaded state file (s3://bucket/cluster/file)
 *
 * @throws IllegalArgumentException When required parameters are missing
 * @throws RuntimeException When S3 upload fails or cluster files are not found
 *
 * @since 1.0.0
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

    // Create compressed tarball of cluster state directory
    // SECURITY: Use quoted paths to prevent shell injection
    sh(script: "cd '${config.workDir}' && tar -czf 'cluster-state.tar.gz' '${config.clusterName}/'")

    def s3Key = "${config.clusterName}/cluster-state.tar.gz"
    def localPath = "${config.workDir}/cluster-state.tar.gz"

    // Delegate to NonCPS method to avoid Jenkins serialization of AWS SDK objects
    def result = performS3Upload(
        config.bucket,
        s3Key,
        localPath,
        config.region,
        config.metadata,
        config.accessKey,
        config.secretKey
    )

    if (result.error) {
        error "Failed to upload state to S3: ${result.error}"
    }

    // Save metadata separately for quick access without downloading full state
    if (config.metadata) {
        saveMetadata([
            bucket: config.bucket,
            clusterName: config.clusterName,
            region: config.region,
            accessKey: config.accessKey,
            secretKey: config.secretKey
        ], config.metadata)
    }

    // Clean up temporary tarball to save disk space
    sh(script: "rm -f '${localPath}' || true")

    return result.s3Uri
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
 *   - accessKey: AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *
 * @return boolean true if state was found and downloaded, false if not found
 *
 * @throws IllegalArgumentException When required parameters are missing
 * @throws RuntimeException When S3 download fails (other than 404)
 *
 * @since 1.0.0
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

    def s3Key = "${config.clusterName}/cluster-state.tar.gz"
    def localPath = "${config.workDir}/cluster-state.tar.gz"

    openshiftTools.log('INFO', 'Downloading cluster state from S3...', config)

    // Delegate to NonCPS method to avoid Jenkins serialization of AWS SDK objects
    def downloaded = performS3Download(config.bucket, s3Key, localPath, config.region, config.accessKey, config.secretKey)

    if (!downloaded) {
        openshiftTools.log('WARN', "No state found in S3 for cluster: ${config.clusterName}", config)
        return false
    }

    // Extract state tarball and remove temporary file
    // SECURITY: Use quoted paths to prevent shell injection
    sh(script: "cd '${config.workDir}' && tar -xzf 'cluster-state.tar.gz' && rm -f 'cluster-state.tar.gz'")

    return true
}

/**
 * Performs the actual S3 download operation.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * @param bucket S3 bucket name
 * @param s3Key S3 object key
 * @param localPath Local file path to save to
 * @param region AWS region
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return boolean true if downloaded, false if not found
 *
 * @since 1.0.0
 */
@NonCPS
private performS3Download(String bucket, String s3Key, String localPath, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        // Check if object exists before attempting download
        try {
            s3Client.getObjectMetadata(bucket, s3Key)
        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404) {
                return false  // Object not found is expected for new clusters
            }
            throw e  // Re-throw other errors (403, 500, etc.)
        }

        // Download state
        def getRequest = new GetObjectRequest(bucket, s3Key)
        def file = new File(localPath)
        s3Client.getObject(getRequest, file)

        return true
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Saves cluster metadata as JSON to S3.
 *
 * Stores metadata separately from state files for quick access without
 * downloading full cluster state. Used for cluster listing and lifecycle
 * management operations.
 *
 * @param params Map containing S3 configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *   - accessKey: AWS access key (optional)
 *   - secretKey: AWS secret key (optional)
 * @param metadata Map of metadata to save (will be converted to JSON)
 *
 * @throws RuntimeException When S3 upload fails
 *
 * @since 1.0.0
 */
def saveMetadata(Map params, Map metadata) {
    def s3Key = "${params.clusterName}/metadata.json"
    def json = new JsonBuilder(metadata).toPrettyString()

    // Delegate to NonCPS method to avoid Jenkins serialization of AWS SDK objects
    def result = performSaveMetadata(
        params.bucket,
        s3Key,
        json,
        params.region,
        params.accessKey,
        params.secretKey
    )

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to save metadata: ${result.error}", params)
        error "Failed to save metadata to S3: ${result.error}"
    }
}

/**
 * Retrieves cluster metadata JSON from S3.
 *
 * Fetches and parses the metadata.json file for a specific cluster.
 * Returns null if not found rather than throwing an error.
 *
 * @param params Map containing retrieval configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *   - accessKey: AWS access key (optional)
 *   - secretKey: AWS secret key (optional)
 *
 * @return Map parsed metadata or null if not found
 *
 * @since 1.0.0
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
    // Delegate to NonCPS method to avoid Jenkins serialization of AWS SDK objects
    def result = performGetMetadata(params.bucket, params.clusterName, params.region, params.accessKey, params.secretKey)

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to get metadata: ${result.error}", params)
        return null
    }

    if (result.notFound) {
        openshiftTools.log('WARN', "No metadata found for cluster: ${params.clusterName}", params)
        return null
    }

    if (result.fromObjectMetadata) {
        openshiftTools.log('INFO', "Found metadata in S3 object metadata for cluster: ${params.clusterName}", params)
    }

    return result.metadata
}

/**
 * Performs the actual S3 metadata retrieval.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * Attempts to retrieve metadata from:
 * 1. Dedicated metadata.json file (preferred)
 * 2. User metadata on cluster-state.tar.gz object (fallback)
 *
 * @param bucket S3 bucket name
 * @param clusterName Name of the cluster
 * @param region AWS region
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return Map with metadata, error, notFound, and fromObjectMetadata flags
 *
 * @since 1.0.0
 */
@NonCPS
private performGetMetadata(String bucket, String clusterName, String region, String accessKey = null, String secretKey = null) {
    def s3Key = "${clusterName}/metadata.json"
    def s3Client = buildS3Client(region, accessKey, secretKey)
    def s3Object = null

    try {
        // Primary method: Retrieve dedicated metadata.json file
        try {
            s3Object = s3Client.getObject(bucket, s3Key)
            def json = s3Object.getObjectContent().text

            if (json) {
                def lazyMap = new JsonSlurper().parseText(json)
                // CRITICAL: Convert LazyMap to HashMap to prevent Jenkins serialization errors
                def metadata = new HashMap(lazyMap)
                return [metadata: metadata, error: null, notFound: false, fromObjectMetadata: false]
            }
        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404) {
                // Fallback method: Extract metadata from S3 object user metadata
                try {
                    def stateKey = "${clusterName}/cluster-state.tar.gz"
                    def headResult = s3Client.getObjectMetadata(bucket, stateKey)
                    def userMetadata = headResult.getUserMetadata()

                    if (userMetadata && !userMetadata.isEmpty()) {
                        // Convert S3 user metadata to regular metadata map
                        // NOTE: S3 user metadata keys use hyphens, normalize to underscores
                        def metadata = [:]
                        userMetadata.each { key, value ->
                            // Normalize key format: cluster-name → cluster_name
                            def normalizedKey = key.replaceAll('-', '_')
                            metadata[normalizedKey] = value
                        }

                        return [metadata: metadata, error: null, notFound: false, fromObjectMetadata: true]
                    }
                } catch (AmazonServiceException e2) {
                    if (e2.getStatusCode() != 404) {
                        return [metadata: null, error: e2.message, notFound: false, fromObjectMetadata: false]
                    }
                }

                return [metadata: null, error: null, notFound: true, fromObjectMetadata: false]
            }
            throw e
        }
    } catch (Exception e) {
        return [metadata: null, error: e.message, notFound: false, fromObjectMetadata: false]
    } finally {
        if (s3Object != null) {
            s3Object.close()
        }
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 upload operation.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * Uploads file to S3 with optional user metadata attached.
 *
 * @param bucket S3 bucket name
 * @param s3Key S3 object key
 * @param localPath Local file path to upload
 * @param region AWS region
 * @param metadata Optional metadata to attach as S3 user metadata
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return Map with s3Uri and error (if any)
 *
 * @since 1.0.0
 */
@NonCPS
private performS3Upload(String bucket, String s3Key, String localPath, String region, Map metadata, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        // Attach user metadata to S3 object for backup metadata storage
        def objectMetadata = new ObjectMetadata()
        if (metadata && metadata instanceof Map) {
            metadata.each { k, v ->
                objectMetadata.addUserMetadata(k.toString(), v.toString())
            }
        }

        // Upload file to S3
        def file = new File(localPath)
        def putRequest = new PutObjectRequest(bucket, s3Key, file)
            .withMetadata(objectMetadata)

        s3Client.putObject(putRequest)

        return [s3Uri: "s3://${bucket}/${s3Key}", error: null]
    } catch (Exception e) {
        return [s3Uri: null, error: e.message]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 metadata save operation.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * Uploads JSON metadata file to S3 with proper content type.
 *
 * @param bucket S3 bucket name
 * @param s3Key S3 object key
 * @param json JSON string to upload
 * @param region AWS region
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return Map with error (if any)
 *
 * @since 1.0.0
 */
@NonCPS
private performSaveMetadata(String bucket, String s3Key, String json, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)
    def inputStream = null

    try {
        // Set content type and length for proper S3 handling
        def objectMetadata = new ObjectMetadata()
        objectMetadata.setContentType('application/json')
        objectMetadata.setContentLength(json.bytes.length)

        // Upload JSON as stream
        inputStream = new ByteArrayInputStream(json.bytes)
        def putRequest = new PutObjectRequest(bucket, s3Key, inputStream, objectMetadata)

        s3Client.putObject(putRequest)

        return [error: null]
    } catch (Exception e) {
        return [error: e.message]
    } finally {
        if (inputStream != null) {
            inputStream.close()
        }
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 cleanup operation.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * Deletes all objects with a given prefix. Handles pagination for
 * buckets with many objects and batch deletion limits.
 *
 * @param bucket S3 bucket name
 * @param prefix S3 key prefix to delete
 * @param region AWS region
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return Map with deletedCount and error (if any)
 *
 * @since 1.0.0
 */
@NonCPS
private performS3Cleanup(String bucket, String prefix, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)
    def deletedCount = 0

    try {
        // List and delete all objects with the prefix
        def listRequest = new ListObjectsV2Request()
            .withBucketName(bucket)
            .withPrefix(prefix)

        def result
        def isTruncated = true

        while (isTruncated) {
            result = s3Client.listObjectsV2(listRequest)

            if (result.getObjectSummaries().size() > 0) {
                // Prepare batch delete request (S3 limit: 1000 objects per request)
                // AWS SDK handles batching automatically
                def deleteRequest = new DeleteObjectsRequest(bucket)
                def keysToDelete = result.getObjectSummaries().collect {
                    new DeleteObjectsRequest.KeyVersion(it.getKey())
                }
                deleteRequest.setKeys(keysToDelete)
                def deleteResult = s3Client.deleteObjects(deleteRequest)
                deletedCount += deleteResult.getDeletedObjects().size()
            }

            // Continue pagination if more objects exist
            isTruncated = result.isTruncated()
            if (isTruncated) {
                listRequest.setContinuationToken(result.getNextContinuationToken())
            }
        }

        return [deletedCount: deletedCount, error: null]
    } catch (Exception e) {
        return [deletedCount: deletedCount, error: e.message]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 list clusters operation.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * Lists all cluster directories by looking for metadata files.
 * Handles pagination and extracts unique cluster names.
 *
 * @param bucket S3 bucket name
 * @param region AWS region
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return Map with clusters list and error (if any)
 *
 * @since 1.0.0
 */
@NonCPS
private performListClusters(String bucket, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        def clusters = []
        def prefix = ''  // List from bucket root

        // List objects
        def listRequest = new ListObjectsV2Request()
            .withBucketName(bucket)
            .withPrefix(prefix)

        def result
        def isTruncated = true

        while (isTruncated) {
            result = s3Client.listObjectsV2(listRequest)

            result.getObjectSummaries().each { summary ->
                // Identify cluster directories by presence of metadata or state files
                if (summary.getKey().endsWith('metadata.json') || summary.getKey().endsWith('cluster-state.tar.gz')) {
                    // Extract cluster name from S3 key path (format: "cluster-name/file")
                    def parts = summary.getKey().split('/')
                    if (parts.length >= 2) {
                        def clusterName = parts[0]
                        if (!clusters.contains(clusterName)) {
                            clusters << clusterName
                        }
                    }
                }
            }

            isTruncated = result.isTruncated()
            if (isTruncated) {
                listRequest.setContinuationToken(result.getNextContinuationToken())
            }
        }

        return [clusters: clusters, error: null]
    } catch (Exception e) {
        return [clusters: [], error: e.message]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Performs the actual S3 bucket existence check and creation.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * Creates bucket if it doesn't exist, with versioning enabled for safety.
 * Handles region-specific bucket creation requirements.
 *
 * @param bucketName S3 bucket name
 * @param region AWS region
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return Map with created flag, error, and errorCode (if any)
 *
 * @since 1.0.0
 */
@NonCPS
private performEnsureS3BucketExists(String bucketName, String region, String accessKey = null, String secretKey = null) {
    def s3Client = buildS3Client(region, accessKey, secretKey)

    try {
        // Check if bucket exists before attempting creation
        if (!s3Client.doesBucketExistV2(bucketName)) {
            // Create bucket with region-specific handling
            if (region == 'us-east-1') {
                // NOTE: us-east-1 doesn't require LocationConstraint
                s3Client.createBucket(bucketName)
            } else {
                // Other regions require explicit LocationConstraint
                s3Client.createBucket(bucketName, region)
            }

            // Enable versioning for disaster recovery and accidental deletion protection
            def versioningConfig = new BucketVersioningConfiguration()
                .withStatus(BucketVersioningConfiguration.ENABLED)
            s3Client.setBucketVersioningConfiguration(
                new SetBucketVersioningConfigurationRequest(
                    bucketName, versioningConfig
                )
            )

            return [created: true, error: null, errorCode: null]
        } else {
            return [created: false, error: null, errorCode: null]
        }
    } catch (AmazonS3Exception e) {
        return [created: false, error: e.message, errorCode: e.getStatusCode()]
    } catch (Exception e) {
        return [created: false, error: e.message, errorCode: null]
    } finally {
        s3Client.shutdown()
    }
}

/**
 * Removes all S3 objects for a cluster from active state.
 *
 * Deletes all objects with the cluster prefix using batch deletion
 * for efficiency. Handles pagination for clusters with many files.
 *
 * @param params Map containing cleanup configuration:
 *   - bucket: S3 bucket name (required)
 *   - clusterName: Name of the cluster (required)
 *   - region: AWS region (required)
 *   - accessKey: AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *
 * @throws RuntimeException When S3 deletion fails
 *
 * @since 1.0.0
 *
 * @example
 * openshiftS3.cleanup([
 *     bucket: 'my-clusters',
 *     clusterName: 'test-cluster',
 *     region: 'us-east-2'
 * ])
 */
def cleanup(Map params) {
    openshiftTools.log('INFO', "Cleaning up S3 state for cluster: ${params.clusterName}", params)

    def prefix = "${params.clusterName}/"

    // Delegate to NonCPS method to avoid Jenkins serialization of AWS SDK objects
    def result = performS3Cleanup(
        params.bucket,
        prefix,
        params.region,
        params.accessKey,
        params.secretKey
    )

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to cleanup S3 state: ${result.error}", params)
        error "Failed to cleanup S3 state: ${result.error}"
    }

    openshiftTools.log('INFO', "Successfully deleted ${result.deletedCount} objects for cluster: ${params.clusterName}", params)
}

/**
 * Lists all OpenShift clusters by scanning S3 for metadata files.
 *
 * Scans the S3 bucket for cluster directories identified by presence
 * of metadata.json or cluster-state.tar.gz files.
 *
 * @param params Map containing listing configuration:
 *   - bucket: S3 bucket name (optional, default: 'openshift-clusters-119175775298-us-east-2')
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - accessKey: AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *
 * @return List of cluster names found in the bucket
 *
 * @throws RuntimeException When S3 access fails
 *
 * @since 1.0.0
 *
 * @example
 * def clusters = openshiftS3.listClusters()
 * clusters.each { name ->
 *     println "Found cluster: ${name}"
 * }
 */
def listClusters(Map params = [:]) {
    def bucket = params.bucket ?: 'openshift-clusters-119175775298-us-east-2'
    def region = params.region ?: 'us-east-2'
    def accessKey = params.accessKey
    def secretKey = params.secretKey

    // Delegate to NonCPS method to avoid Jenkins serialization of AWS SDK objects
    def result = performListClusters(bucket, region, accessKey, secretKey)

    if (result.error) {
        openshiftTools.log('ERROR', "Failed to list clusters: ${result.error}", params)
        error "Failed to list clusters from S3: ${result.error}"
    }

    return result.clusters
}

/**
 * Ensures S3 bucket exists, creating it if necessary.
 *
 * Creates bucket with appropriate region settings and enables
 * versioning for data protection. Handles region-specific creation
 * requirements (us-east-1 vs other regions).
 *
 * @param bucketName Name of the S3 bucket (required, must follow S3 naming rules)
 * @param region AWS region for bucket (required)
 * @param accessKey AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 * @param secretKey AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *
 * @throws RuntimeException When bucket exists but is owned by different AWS account (403)
 * @throws RuntimeException When bucket creation fails due to permissions or naming
 *
 * @since 1.0.0
 *
 * @example
 * openshiftS3.ensureS3BucketExists(
 *     'my-openshift-clusters',
 *     'us-east-2',
 *     credentials.accessKey,
 *     credentials.secretKey
 * )
 */
def ensureS3BucketExists(String bucketName, String region, String accessKey = null, String secretKey = null) {
    openshiftTools.log('DEBUG', "Checking if S3 bucket ${bucketName} exists in region ${region}...", [bucket: bucketName, region: region])

    // Perform the S3 bucket operations in a separate method to avoid serialization issues
    def result = performEnsureS3BucketExists(bucketName, region, accessKey, secretKey)

    if (result.error) {
        if (result.errorCode == 409) {
            // 409 Conflict means bucket name is taken globally
            // S3 bucket names must be globally unique across all AWS accounts
            error "S3 bucket ${bucketName} already exists but is owned by another AWS account"
        } else {
            error "Failed to create S3 bucket ${bucketName}: ${result.error}"
        }
    }

    if (result.created) {
        openshiftTools.log('INFO', "S3 bucket ${bucketName} created successfully with versioning enabled", [bucket: bucketName, region: region])
    } else {
        openshiftTools.log('DEBUG', "S3 bucket ${bucketName} already exists", [bucket: bucketName, region: region])
    }
}

