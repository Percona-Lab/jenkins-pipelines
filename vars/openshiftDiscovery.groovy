/**
 * OpenShift Cluster Discovery Library
 *
 * Provides comprehensive cluster discovery by combining multiple data sources:
 * - S3 bucket metadata (managed clusters)
 * - AWS Resource Groups Tagging API (all clusters with resources)
 *
 * Consolidates and deduplicates information to provide a unified view of
 * OpenShift clusters across the infrastructure.
 *
 * @since 1.0.0
 */
import com.amazonaws.services.resourcegroupstaggingapi.*
import com.amazonaws.services.resourcegroupstaggingapi.model.*
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain

/**
 * Build AWS Resource Groups Tagging API client with proper credentials.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * @param region AWS region (required)
 * @param accessKey AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 * @param secretKey AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 * @return AWSResourceGroupsTaggingAPI client instance
 *
 * @since 1.0.0
 */
@NonCPS
def buildTaggingClient(String region, String accessKey = null, String secretKey = null) {
    def awsAccessKey = accessKey ?: System.getenv('AWS_ACCESS_KEY_ID')
    def awsSecretKey = secretKey ?: System.getenv('AWS_SECRET_ACCESS_KEY')

    if (awsAccessKey && awsSecretKey) {
        def awsCreds = new BasicAWSCredentials(awsAccessKey, awsSecretKey)
        return AWSResourceGroupsTaggingAPIClientBuilder.standard()
            .withRegion(region)
            .withCredentials(new AWSStaticCredentialsProvider(awsCreds))
            .build()
    } else {
        // Use default credential chain (instance profile, env vars, etc.)
        return AWSResourceGroupsTaggingAPIClientBuilder.standard()
            .withRegion(region)
            .withCredentials(new DefaultAWSCredentialsProviderChain())
            .build()
    }
}

/**
 * Discovers all OpenShift clusters by combining S3 metadata and AWS resources.
 *
 * Performs parallel discovery from:
 * 1. S3 bucket metadata (for managed clusters with saved state)
 * 2. AWS Resource Groups Tagging API (for all clusters with active resources)
 *
 * Consolidates data by cluster name, preferring S3 metadata when available
 * and enriching with live AWS resource counts.
 *
 * @param params Map containing discovery configuration:
 *   - bucket: S3 bucket name (optional, default: 'openshift-clusters-119175775298-us-east-2')
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - includeResourceCounts: Include live resource counts (optional, default: true)
 *   - accessKey: AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *
 * @return List of cluster information maps containing:
 *   - name: Cluster name
 *   - source: Data source ('s3', 'aws-tags', or 'combined')
 *   - status: Cluster status
 *   - metadata: Combined metadata from S3 and AWS tags
 *   - resources: Live resource counts by type
 *   - s3State: Information about S3 backup state
 *
 * @since 1.0.0
 *
 * @example
 * def clusters = openshiftDiscovery.discoverClusters()
 * clusters.each { cluster ->
 *     println "${cluster.name}: ${cluster.resources.size()} resource types, source: ${cluster.source}"
 * }
 */
def discoverClusters(Map params = [:]) {
    def bucket = params.bucket ?: 'openshift-clusters-119175775298-us-east-2'
    def region = params.region ?: 'us-east-2'
    def includeResourceCounts = params.includeResourceCounts != false

    openshiftTools.log('INFO', 'Starting comprehensive cluster discovery...', params)

    // Step 1: Get clusters from S3
    def s3Clusters = [:]
    try {
        def s3ClusterNames = openshiftS3.listClusters([
            bucket: bucket,
            region: region
        ])

        s3ClusterNames.each { clusterName ->
            def metadata = openshiftS3.getMetadata([
                bucket: bucket,
                clusterName: clusterName,
                region: region
            ])

            s3Clusters[clusterName] = [
                name: clusterName,
                source: 's3',
                status: metadata?.status ?: 'unknown',
                metadata: metadata ?: [:],
                resources: [:],
                s3State: [
                    hasBackup: true,
                    bucket: bucket
                ]
            ]
        }

        openshiftTools.log('INFO', "Found ${s3Clusters.size()} clusters in S3", params)
    } catch (Exception e) {
        openshiftTools.log('WARN', "Failed to retrieve S3 clusters: ${e.message}", params)
    }

    // Step 2: Discover clusters from AWS resources
    def awsClusters = [:]
    if (includeResourceCounts) {
        def discoveryResult = performAWSResourceDiscovery(
            region,
            params.accessKey,
            params.secretKey
        )

        if (discoveryResult.error) {
            openshiftTools.log('WARN', "AWS resource discovery failed: ${discoveryResult.error}", params)
        } else {
            discoveryResult.clusters.each { awsCluster ->
                awsClusters[awsCluster.name] = awsCluster
            }
            openshiftTools.log('INFO', "Found ${awsClusters.size()} clusters via AWS resource discovery", params)
        }
    }

    // Step 3: Consolidate data
    def consolidatedClusters = []

    // Process all unique cluster names
    def allClusterNames = (s3Clusters.keySet() + awsClusters.keySet()) as Set

    allClusterNames.each { clusterName ->
        def s3Cluster = s3Clusters[clusterName]
        def awsCluster = awsClusters[clusterName]

        if (s3Cluster && awsCluster) {
            // Merge data from both sources
            def merged = [
                name: clusterName,
                source: 'combined',
                status: s3Cluster.status ?: awsCluster.status,
                metadata: [:],
                resources: awsCluster.resources,
                s3State: s3Cluster.s3State
            ]

            // Merge metadata, preferring S3 but including AWS-only fields
            merged.metadata.putAll(awsCluster.metadata ?: [:])
            merged.metadata.putAll(s3Cluster.metadata ?: [:])

            consolidatedClusters << merged
        } else if (s3Cluster) {
            // S3 only - cluster might be deleted but state preserved
            consolidatedClusters << s3Cluster
        } else if (awsCluster) {
            // AWS only - active cluster without S3 backup
            awsCluster.s3State = [hasBackup: false]
            consolidatedClusters << awsCluster
        }
    }

    // Sort by cluster name for consistent output
    consolidatedClusters.sort { it.name }

    openshiftTools.log('INFO', "Discovery complete: ${consolidatedClusters.size()} total clusters found", params)

    return consolidatedClusters
}

/**
 * Performs AWS resource discovery using Resource Groups Tagging API.
 *
 * @NonCPS Required to prevent Jenkins serialization of AWS SDK objects
 *
 * Discovers OpenShift clusters by finding resources tagged with
 * kubernetes.io/cluster/* tags. Counts resources by type and extracts
 * metadata from resource tags.
 *
 * @param region AWS region
 * @param accessKey AWS access key (optional)
 * @param secretKey AWS secret key (optional)
 * @return Map with clusters list and error (if any)
 *
 * @since 1.0.0
 */
@NonCPS
private performAWSResourceDiscovery(String region, String accessKey = null, String secretKey = null) {
    def taggingClient = buildTaggingClient(region, accessKey, secretKey)

    try {
        def clusters = [:]

        // First, get all VPCs to identify cluster names
        def vpcRequest = new GetResourcesRequest()
            .withResourceTypeFilters('ec2:vpc')

        def vpcResult = taggingClient.getResources(vpcRequest)
        def clusterNames = new HashSet<String>()

        // Extract cluster names from VPC tags
        vpcResult.getResourceTagMappingList().each { mapping ->
            mapping.getTags().each { tag ->
                if (tag.getKey().startsWith('kubernetes.io/cluster/')) {
                    def clusterName = tag.getKey().substring('kubernetes.io/cluster/'.length())
                    clusterNames.add(clusterName)
                }
            }
        }

        // For each cluster, get detailed resource information
        clusterNames.each { clusterName ->
            def cluster = [
                name: clusterName,
                source: 'aws-tags',
                status: 'active',
                metadata: [:],
                resources: [:]
            ]

            // Define resource types to count
            def resourceTypes = [
                'ec2:instance': 'EC2 Instances',
                'ec2:volume': 'EBS Volumes',
                'ec2:security-group': 'Security Groups',
                'ec2:network-interface': 'Network Interfaces',
                'elasticloadbalancing:loadbalancer': 'Load Balancers',
                'ec2:elastic-ip': 'Elastic IPs',
                'ec2:nat-gateway': 'NAT Gateways',
                'ec2:internet-gateway': 'Internet Gateways',
                'ec2:subnet': 'Subnets',
                'ec2:vpc': 'VPCs',
                'ec2:route-table': 'Route Tables'
            ]

            // Count resources for this cluster
            resourceTypes.each { resourceType, displayName ->
                def resourceRequest = new GetResourcesRequest()
                    .withResourceTypeFilters(resourceType)
                    .withTagFilters(
                        new TagFilter()
                            .withKey("kubernetes.io/cluster/${clusterName}")
                    )

                def resourceResult = taggingClient.getResources(resourceRequest)
                def count = resourceResult.getResourceTagMappingList().size()

                if (count > 0) {
                    cluster.resources[displayName] = count

                    // Extract metadata from first resource if not already set
                    if (cluster.metadata.isEmpty() && !resourceResult.getResourceTagMappingList().isEmpty()) {
                        def firstResource = resourceResult.getResourceTagMappingList().get(0)
                        firstResource.getTags().each { tag ->
                            def key = tag.getKey()
                            def value = tag.getValue()

                            // Capture useful metadata tags
                            if (key == 'owner') {
                                cluster.metadata.owner = value
                            } else if (key == 'team') {
                                cluster.metadata.team = value
                            } else if (key == 'product') {
                                cluster.metadata.product = value
                            } else if (key == 'creation-time') {
                                cluster.metadata.creationTime = value
                            } else if (key == 'iit-billing-tag') {
                                cluster.metadata.billingTag = value
                            } else if (key == 'build-url') {
                                cluster.metadata.buildUrl = value
                            }
                        }
                    }
                }
            }

            // Only add cluster if it has resources
            if (!cluster.resources.isEmpty()) {
                clusters[clusterName] = cluster
            }
        }

        return [clusters: clusters.values() as List, error: null]
    } catch (Exception e) {
        return [clusters: [], error: e.message]
    } finally {
        taggingClient.shutdown()
    }
}

/**
 * Formats cluster information for display.
 *
 * Creates a formatted string representation of cluster data suitable
 * for console output or reports.
 *
 * @param cluster Map containing cluster information
 * @param verbose Include detailed resource counts (default: true)
 * @return String Formatted cluster information
 *
 * @since 1.0.0
 */
def formatClusterInfo(Map cluster, boolean verbose = true) {
    def output = []

    output << "Cluster: ${cluster.name}"
    output << "  Source: ${cluster.source}"
    output << "  Status: ${cluster.status}"

    if (cluster.s3State?.hasBackup) {
        output << "  S3 Backup: Yes (${cluster.s3State.bucket})"
    } else {
        output << "  S3 Backup: No"
    }

    if (cluster.metadata && !cluster.metadata.isEmpty()) {
        output << "  Metadata:"
        cluster.metadata.each { key, value ->
            if (value) {
                def displayKey = key.replaceAll('_', ' ').capitalize()
                output << "    ${displayKey}: ${value}"
            }
        }
    }

    if (verbose && cluster.resources && !cluster.resources.isEmpty()) {
        output << "  Resources:"
        cluster.resources.each { type, count ->
            output << "    ${type}: ${count}"
        }
        output << "  Total Resource Types: ${cluster.resources.size()}"
    }

    return output.join('\n')
}

/**
 * Gets detailed information about a specific cluster.
 *
 * Retrieves comprehensive information about a single cluster by combining
 * S3 metadata and live AWS resource data.
 *
 * @param clusterName Name of the cluster (required)
 * @param params Map containing configuration (same as discoverClusters)
 * @return Map Cluster information or null if not found
 *
 * @since 1.0.0
 */
def getClusterDetails(String clusterName, Map params = [:]) {
    def allClusters = discoverClusters(params)
    return allClusters.find { it.name == clusterName }
}

/**
 * Checks for orphaned resources (resources without corresponding S3 state).
 *
 * Identifies clusters that have AWS resources but no S3 backup,
 * which might indicate failed cluster creation or deletion.
 *
 * @param params Map containing configuration (same as discoverClusters)
 * @return List of orphaned cluster names
 *
 * @since 1.0.0
 */
def findOrphanedClusters(Map params = [:]) {
    def clusters = discoverClusters(params)
    return clusters.findAll {
        !it.s3State?.hasBackup && it.source == 'aws-tags'
    }.collect { it.name }
}

/**
 * Checks for stale S3 states (S3 backups without corresponding AWS resources).
 *
 * Identifies clusters that have S3 backups but no active AWS resources,
 * which might indicate successful deletion but failed S3 cleanup.
 *
 * @param params Map containing configuration (same as discoverClusters)
 * @return List of stale cluster names
 *
 * @since 1.0.0
 */
def findStaleClusters(Map params = [:]) {
    def clusters = discoverClusters(params)
    return clusters.findAll {
        it.source == 's3' && (it.resources?.isEmpty() ?: true)
    }.collect { it.name }
}
