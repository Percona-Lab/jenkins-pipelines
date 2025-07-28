@Grab('com.amazonaws:aws-java-sdk-s3:1.12.261')
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.*
import com.amazonaws.AmazonServiceException
import com.amazonaws.SdkClientException

def call(String action, Map config) {
    switch(action) {
        case 'upload':
            return uploadClusterState(config)
        case 'download':
            return downloadClusterState(config)
        case 'list':
            return listClusterStates(config)
        case 'delete':
            return deleteClusterState(config)
        case 'exists':
            return checkClusterStateExists(config)
        default:
            error "Unknown action: ${action}. Valid actions: upload, download, list, delete, exists"
    }
}

def uploadClusterState(Map config) {
    validateConfig(config, ['bucket', 'clusterName', 'region', 'localPath'])

    def s3Client = createS3Client(config.region)
    def key = "clusters/${config.clusterName}/cluster-state.tar.gz"

    echo "Uploading cluster state to S3: s3://${config.bucket}/${key}"

    try {
        // Upload the file
        def file = new File(config.localPath)
        if (!file.exists()) {
            error "Local file not found: ${config.localPath}"
        }

        def putRequest = new PutObjectRequest(config.bucket, key, file)

        // Add metadata
        def metadata = new ObjectMetadata()
        metadata.addUserMetadata('cluster-name', config.clusterName)
        metadata.addUserMetadata('upload-time', new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"))
        metadata.addUserMetadata('jenkins-build', env.BUILD_NUMBER ?: 'unknown')

        if (config.metadata) {
            config.metadata.each { k, v ->
                metadata.addUserMetadata(k, v.toString())
            }
        }

        putRequest.setMetadata(metadata)

        // Set storage class if specified
        if (config.storageClass) {
            putRequest.setStorageClass(config.storageClass)
        }

        s3Client.putObject(putRequest)

        echo "Successfully uploaded cluster state to S3"
        return "s3://${config.bucket}/${key}"

    } catch (AmazonServiceException e) {
        error "AWS service error uploading to S3: ${e.message}"
    } catch (SdkClientException e) {
        error "AWS SDK client error: ${e.message}"
    }
}

def downloadClusterState(Map config) {
    validateConfig(config, ['bucket', 'clusterName', 'region', 'localPath'])

    def s3Client = createS3Client(config.region)
    def key = "clusters/${config.clusterName}/cluster-state.tar.gz"

    echo "Downloading cluster state from S3: s3://${config.bucket}/${key}"

    try {
        // Check if object exists
        if (!s3Client.doesObjectExist(config.bucket, key)) {
            error "Cluster state not found in S3: s3://${config.bucket}/${key}"
        }

        // Download the file
        def s3Object = s3Client.getObject(config.bucket, key)
        def inputStream = s3Object.getObjectContent()

        def file = new File(config.localPath)
        file.parentFile.mkdirs()

        file.withOutputStream { outputStream ->
            outputStream << inputStream
        }

        inputStream.close()

        echo "Successfully downloaded cluster state to: ${config.localPath}"

        // Return metadata
        def metadata = s3Object.getObjectMetadata()
        return [
            file: config.localPath,
            size: metadata.getContentLength(),
            lastModified: metadata.getLastModified(),
            userMetadata: metadata.getUserMetadata()
        ]

    } catch (AmazonServiceException e) {
        error "AWS service error downloading from S3: ${e.message}"
    } catch (SdkClientException e) {
        error "AWS SDK client error: ${e.message}"
    }
}

def listClusterStates(Map config) {
    validateConfig(config, ['bucket', 'region'])

    def s3Client = createS3Client(config.region)
    def prefix = config.prefix ?: 'clusters/'

    echo "Listing cluster states in S3 bucket: ${config.bucket}"

    try {
        def clusters = []
        def listRequest = new ListObjectsV2Request()
            .withBucketName(config.bucket)
            .withPrefix(prefix)
            .withDelimiter('/')

        def result
        do {
            result = s3Client.listObjectsV2(listRequest)

            // Get cluster names from common prefixes
            result.getCommonPrefixes().each { prefix ->
                def clusterName = prefix.split('/')[-2]
                if (clusterName && clusterName != 'clusters') {
                    clusters << clusterName
                }
            }

            listRequest.setContinuationToken(result.getNextContinuationToken())
        } while (result.isTruncated())

        echo "Found ${clusters.size()} clusters in S3"
        return clusters.sort()

    } catch (AmazonServiceException e) {
        error "AWS service error listing S3 objects: ${e.message}"
    } catch (SdkClientException e) {
        error "AWS SDK client error: ${e.message}"
    }
}

def deleteClusterState(Map config) {
    validateConfig(config, ['bucket', 'clusterName', 'region'])

    def s3Client = createS3Client(config.region)
    def prefix = "clusters/${config.clusterName}/"

    echo "Deleting cluster state from S3: s3://${config.bucket}/${prefix}"

    try {
        // List all objects with the prefix
        def objectsToDelete = []
        def listRequest = new ListObjectsV2Request()
            .withBucketName(config.bucket)
            .withPrefix(prefix)

        def result
        do {
            result = s3Client.listObjectsV2(listRequest)

            result.getObjectSummaries().each { summary ->
                objectsToDelete << new DeleteObjectsRequest.KeyVersion(summary.getKey())
            }

            listRequest.setContinuationToken(result.getNextContinuationToken())
        } while (result.isTruncated())

        if (objectsToDelete.isEmpty()) {
            echo "No objects found to delete for cluster: ${config.clusterName}"
            return true
        }

        // Delete objects in batches (S3 limit is 1000 per request)
        objectsToDelete.collate(1000).each { batch ->
            def deleteRequest = new DeleteObjectsRequest(config.bucket)
                .withKeys(batch)
                .withQuiet(false)

            def deleteResult = s3Client.deleteObjects(deleteRequest)
            echo "Deleted ${deleteResult.getDeletedObjects().size()} objects"
        }

        echo "Successfully deleted cluster state from S3"
        return true

    } catch (AmazonServiceException e) {
        error "AWS service error deleting from S3: ${e.message}"
    } catch (SdkClientException e) {
        error "AWS SDK client error: ${e.message}"
    }
}

def checkClusterStateExists(Map config) {
    validateConfig(config, ['bucket', 'clusterName', 'region'])

    def s3Client = createS3Client(config.region)
    def key = "clusters/${config.clusterName}/cluster-state.tar.gz"

    try {
        return s3Client.doesObjectExist(config.bucket, key)
    } catch (Exception e) {
        echo "Error checking S3 object existence: ${e.message}"
        return false
    }
}

def createS3Client(String region) {
    return AmazonS3ClientBuilder.standard()
        .withRegion(region)
        .build()
}

def validateConfig(Map config, List requiredFields) {
    requiredFields.each { field ->
        if (!config.containsKey(field) || !config[field]) {
            error "Missing required field: ${field}"
        }
    }
}

// Method to get cluster metadata from S3
def getClusterMetadata(Map config) {
    validateConfig(config, ['bucket', 'clusterName', 'region'])

    def s3Client = createS3Client(config.region)
    def key = "clusters/${config.clusterName}/metadata.json"

    try {
        if (!s3Client.doesObjectExist(config.bucket, key)) {
            return null
        }

        def s3Object = s3Client.getObject(config.bucket, key)
        def content = s3Object.getObjectContent().text

        def slurper = new groovy.json.JsonSlurper()
        return slurper.parseText(content)

    } catch (Exception e) {
        echo "Error reading cluster metadata: ${e.message}"
        return null
    }
}

// Method to save cluster metadata to S3
def saveClusterMetadata(Map config, String metadataJson) {
    validateConfig(config, ['bucket', 'clusterName', 'region'])

    def s3Client = createS3Client(config.region)
    def key = "clusters/${config.clusterName}/metadata.json"

    try {
        def metadata = new ObjectMetadata()
        metadata.setContentType('application/json')
        metadata.setContentLength(metadataJson.length())

        def inputStream = new ByteArrayInputStream(metadataJson.bytes)
        def putRequest = new PutObjectRequest(config.bucket, key, inputStream, metadata)

        s3Client.putObject(putRequest)
        echo "Saved cluster metadata to S3: s3://${config.bucket}/${key}"

    } catch (Exception e) {
        error "Failed to save cluster metadata: ${e.message}"
    }
}
