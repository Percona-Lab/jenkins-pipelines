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
 * @since 2.0.0 - Migrated from AWS SDK to AWS CLI for better Jenkins compatibility
 */
import groovy.json.JsonSlurper

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
 * @since 2.0.0
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

    // Track which S3 clusters have been matched to AWS clusters
    def matchedS3Clusters = [] as Set
    def matchedAWSClusters = [] as Set

    // First pass: Try to match AWS clusters with S3 clusters
    // AWS cluster names often have random suffixes (e.g., test-cluster-7-qc8lc)
    // while S3 stores them with base names (e.g., test-cluster-7)
    awsClusters.each { awsClusterName, awsCluster ->
        def matchedS3Name = null

        // Check if this AWS cluster name starts with any S3 cluster name
        s3Clusters.each { s3ClusterName, s3Cluster ->
            // Match if AWS cluster name starts with S3 cluster name followed by a hyphen
            // This handles the pattern: base-name-randomsuffix
            if (awsClusterName == s3ClusterName ||
                awsClusterName.startsWith("${s3ClusterName}-")) {
                matchedS3Name = s3ClusterName
                return true // Break out of the inner loop
            }
        }

        if (matchedS3Name) {
            // Found matching S3 backup
            def s3Cluster = s3Clusters[matchedS3Name]
            def merged = [
                name: awsClusterName,  // Use the AWS cluster name (with suffix) as the primary name
                baseName: matchedS3Name, // Store the base name for reference
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
            matchedS3Clusters << matchedS3Name
            matchedAWSClusters << awsClusterName
        }
    }

    // Second pass: Add unmatched AWS clusters (no S3 backup)
    awsClusters.each { awsClusterName, awsCluster ->
        if (!matchedAWSClusters.contains(awsClusterName)) {
            awsCluster.s3State = [hasBackup: false]
            consolidatedClusters << awsCluster
        }
    }

    // Third pass: Add unmatched S3 clusters (deleted or no AWS resources)
    s3Clusters.each { s3ClusterName, s3Cluster ->
        if (!matchedS3Clusters.contains(s3ClusterName)) {
            consolidatedClusters << s3Cluster
        }
    }

    // Sort by cluster name for consistent output
    consolidatedClusters.sort { it.name }

    openshiftTools.log('INFO', "Discovery complete: ${consolidatedClusters.size()} total clusters found", params)

    return consolidatedClusters
}

/**
 * Performs AWS resource discovery using Resource Groups Tagging API via AWS CLI.
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
 * @since 2.0.0 - Migrated to AWS CLI
 */
private performAWSResourceDiscovery(String region, String accessKey = null, String secretKey = null) {
    try {
        def clusters = [:]

        // Set up AWS credentials if provided
        def awsEnvVars = []
        if (accessKey && secretKey) {
            awsEnvVars = ["AWS_ACCESS_KEY_ID=${accessKey}", "AWS_SECRET_ACCESS_KEY=${secretKey}"]
        }

        // First, get all VPCs to identify cluster names
        def vpcOutput = ''
        withEnv(awsEnvVars) {
            vpcOutput = sh(
                script: """
                    aws resourcegroupstaggingapi get-resources \
                        --resource-type-filters 'ec2:vpc' \
                        --region ${region} \
                        --query 'ResourceTagMappingList[].Tags[?starts_with(Key, `kubernetes.io/cluster/`)].Key' \
                        --output text 2>/dev/null || echo ''
                """,
                returnStdout: true
            ).trim()
        }

        if (!vpcOutput) {
            return [clusters: [], error: null]
        }

        // Extract unique cluster names (handle both newline and tab separators)
        def clusterNames = vpcOutput.split('[\\n\\t]').collect { tagKey ->
            tagKey.trim().replace('kubernetes.io/cluster/', '')
        }.findAll { it }.unique()

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

        // For each cluster, get detailed resource information
        clusterNames.each { clusterName ->
            def cluster = [
                name: clusterName,
                source: 'aws-tags',
                status: 'active',
                metadata: [:],
                resources: [:]
            ]

            // Get metadata from first EC2 instance
            def metadataJson = ''
            withEnv(awsEnvVars) {
                metadataJson = sh(
                    script: """
                        aws resourcegroupstaggingapi get-resources \
                            --resource-type-filters 'ec2:instance' \
                            --tag-filters 'Key=kubernetes.io/cluster/${clusterName}' \
                            --region ${region} \
                            --query 'ResourceTagMappingList[0].Tags' \
                            --output json 2>/dev/null || echo '[]'
                    """,
                    returnStdout: true
                ).trim()
            }

            if (metadataJson && metadataJson != '[]' && metadataJson != 'null') {
                def tags = new JsonSlurper().parseText(metadataJson)
                tags.each { tag ->
                    def key = tag.Key
                    def value = tag.Value

                    // Capture useful metadata tags
                    switch(key) {
                        case 'owner':
                            cluster.metadata.owner = value
                            break
                        case 'team':
                            cluster.metadata.team = value
                            break
                        case 'product':
                            cluster.metadata.product = value
                            break
                        case 'creation-time':
                            cluster.metadata.creationTime = value
                            break
                        case 'iit-billing-tag':
                            cluster.metadata.billingTag = value
                            break
                        case 'build-url':
                            cluster.metadata.buildUrl = value
                            break
                    }
                }
            }

            // Count resources for this cluster
            resourceTypes.each { resourceType, displayName ->
                def count = ''
                withEnv(awsEnvVars) {
                    count = sh(
                        script: """
                            aws resourcegroupstaggingapi get-resources \
                                --resource-type-filters '${resourceType}' \
                                --tag-filters 'Key=kubernetes.io/cluster/${clusterName}' \
                                --region ${region} \
                                --query 'ResourceTagMappingList | length(@)' \
                                --output text 2>/dev/null || echo '0'
                        """,
                        returnStdout: true
                    ).trim()
                }

                if (count != '0' && count.isInteger()) {
                    cluster.resources[displayName] = count.toInteger()
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