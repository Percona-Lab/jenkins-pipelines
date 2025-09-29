/**
 * OpenShift cluster operations library for automated provisioning and management.
 *
 * This library provides comprehensive OpenShift cluster lifecycle management on AWS,
 * including cluster creation, destruction, and state persistence in S3.
 *
 * @since 1.0.0
 */

// ============================================================================
// Library Imports
// ============================================================================
import groovy.json.JsonBuilder

// ============================================================================
// Public API Methods
// ============================================================================

/**
 * Creates a new OpenShift cluster on AWS with the specified configuration.
 *
 * This method orchestrates the complete cluster creation process including:
 * - Parameter validation and AWS credential verification
 * - S3 bucket creation for state persistence
 * - OpenShift installer and CLI tools installation
 * - Cluster provisioning via openshift-install
 * - Optional PMM (Percona Monitoring and Management) deployment
 * - State backup to S3 for disaster recovery
 *
 * @param config Map containing cluster configuration:
 *   - clusterName: Name for the cluster (required, lowercase alphanumeric with hyphens, max 20 chars)
 *   - openshiftVersion: OpenShift version to install (required, e.g., '4.16.20', 'latest', 'stable-4.16')
 *   - awsRegion: AWS region (required, currently only 'us-east-2' supported due to DNS and AMI constraints)
 *   - pullSecret: Red Hat pull secret for OpenShift (required, obtain from cloud.redhat.com)
 *   - sshPublicKey: SSH public key for cluster access (required)
 *   - s3Bucket: S3 bucket for storing cluster state (required)
 *   - workDir: Working directory for cluster files (required)
 *   - accessKey: AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *   - baseDomain: Base domain for cluster URLs (optional, default: 'cd.percona.com')
 *   - masterType: EC2 instance type for masters (optional, default: 'm5.xlarge')
 *   - workerType: EC2 instance type for workers (optional, default: 'm5.large')
 *   - workerCount: Number of worker nodes (optional, default: 3)
 *   - deleteAfterHours: Auto-delete tag value for cleanup automation (optional, default: '8')
 *   - teamName: Team name for AWS tagging (optional, default: 'cloud')
 *   - productTag: Product tag for AWS tagging (optional, default: 'openshift')
 *   - buildUser: User who initiated the build (optional, default: env.BUILD_USER_ID or 'jenkins')
 *   - deployPMM: Whether to deploy PMM after cluster creation (optional, default: true)
 *   - pmmImageTag: Docker image tag for PMM server (optional, default: '3.3.1')
 *   - pmmHelmChartVersion: Helm chart version for PMM (optional, default: '1.4.7')
 *   - pmmHelmChartBranch: Branch from percona-helm-charts repo (optional, overrides chart version)
 *   - pmmImageRepository: Docker image repository (optional, default: 'percona/pmm-server')
 *   - pmmNamespace: Kubernetes namespace for PMM deployment (optional, default: 'pmm-monitoring')
 *   - pmmAdminPassword: PMM admin password (optional, default: '<GENERATED>' for random password)
 *
 * @return Map containing cluster information:
 *   - apiUrl: Kubernetes API server URL
 *   - consoleUrl: OpenShift web console URL (may be absent if route not ready)
 *   - kubeconfig: Path to kubeconfig file
 *   - kubeadminPassword: Initial admin password for kubeadmin user
 *   - clusterDir: Local directory containing cluster files
 *   - pmm: PMM access details (if deployed):
 *     - url: HTTPS URL for PMM web interface
 *     - username: Admin username (always 'admin')
 *     - password: Admin password (generated or specified)
 *     - namespace: Kubernetes namespace where PMM is deployed
 *     - passwordGenerated: Boolean indicating if password was auto-generated
 *
 * @throws IllegalArgumentException When required parameters are missing or invalid
 * @throws RuntimeException When cluster creation fails or AWS resources cannot be provisioned
 *
 * @example
 * def cluster = openshiftCluster.create([
 *     clusterName: 'test-cluster-001',
 *     openshiftVersion: '4.16.20',
 *     awsRegion: 'us-east-2',
 *     pullSecret: credentials('openshift-pull-secret'),
 *     sshPublicKey: credentials('ssh-public-key'),
 *     s3Bucket: 'openshift-clusters-bucket',
 *     workDir: env.WORKSPACE
 * ])
 */
def create(Map config) {
    def required = ['clusterName', 'openshiftVersion', 'awsRegion', 'pullSecret',
                    'sshPublicKey', 's3Bucket', 'workDir']

    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def params = [
        baseDomain: 'cd.percona.com',
        masterType: 'm5.xlarge',
        workerType: 'm5.large',
        workerCount: 3,
        deleteAfterHours: '8',
        teamName: 'cloud',
        productTag: 'openshift',
        deployPMM: true,
        pmmImageTag: '3.3.1',
        pmmHelmChartVersion: '1.4.7',
        pmmImageRepository: 'percona/pmm-server',
        pmmNamespace: 'pmm-monitoring',
        pmmAdminPassword: '<GENERATED>',  // Default to auto-generation
        // SSL Configuration defaults
        enableSSL: false,
        sslMethod: 'acm',
        sslEmail: 'admin@percona.com',
        useStaging: false,
        consoleCustomDomain: '',
        pmmCustomDomain: ''
    ] + config

    // Use provided credentials or fall back to environment variables
    def awsAccessKey = params.accessKey ?: env.AWS_ACCESS_KEY_ID
    def awsSecretKey = params.secretKey ?: env.AWS_SECRET_ACCESS_KEY

    if (!awsAccessKey || !awsSecretKey) {
        error 'AWS credentials not provided and not found in environment'
    }

    openshiftTools.log('INFO', "Creating OpenShift cluster: ${params.clusterName}", params)

    try {
        // Validate cluster name format and region constraints
        openshiftTools.log('DEBUG', "Validating parameters for cluster ${params.clusterName}", params)
        validateParams(params)

        // Create or verify S3 bucket for cluster state persistence
        openshiftTools.log('DEBUG', "Ensuring S3 bucket exists: ${params.s3Bucket} in ${params.awsRegion}", params)
        openshiftS3.ensureS3BucketExists(params.s3Bucket, params.awsRegion)

        // Install OpenShift CLI and installer binaries for specified version
        openshiftTools.log('INFO', "Installing OpenShift tools for version: ${params.openshiftVersion}", params)
        def resolvedVersion = openshiftTools.install([
            openshiftVersion: params.openshiftVersion
        ])
        params.openshiftVersion = resolvedVersion
        openshiftTools.log('DEBUG', "Resolved OpenShift version: ${resolvedVersion}", params)

        // Create local working directory for cluster configuration and state files
        def clusterDir = "${params.workDir}/${params.clusterName}"
        openshiftTools.log('DEBUG', "Creating cluster directory: ${clusterDir}", params)
        sh "mkdir -p ${clusterDir}"

        // Generate OpenShift installer configuration with AWS infrastructure settings
        openshiftTools.log('DEBUG', 'Generating install-config.yaml', params)
        def installConfigYaml = generateInstallConfig(params)

        // Write the YAML directly to file
        writeFile file: "${clusterDir}/install-config.yaml", text: installConfigYaml

        // Create backup copy of install-config.yaml before running the installer
        // IMPORTANT: openshift-install consumes (deletes) the install-config.yaml file
        // during cluster creation. We need this backup for:
        // - Debugging if cluster creation fails
        // - Including in S3 state backup for disaster recovery
        // - Reference for the exact configuration used
        // - Potential cluster recreation with identical settings
        sh "cp ${clusterDir}/install-config.yaml ${clusterDir}/install-config.yaml.backup"

        // Generate metadata for cluster tracking and lifecycle management
        openshiftTools.log('DEBUG', 'Creating cluster metadata', params)
        def metadata = createMetadata(params, clusterDir)

        // Execute OpenShift installer to provision AWS infrastructure and bootstrap cluster
        openshiftTools.log('INFO', 'Creating OpenShift cluster (this will take 30-45 minutes)...', params)

        // Print install-config.yaml content when debug mode is enabled
        if (env.OPENSHIFT_INSTALL_LOG_LEVEL == 'debug') {
            openshiftTools.log('DEBUG', 'install-config.yaml contents:', params)
            def yamlContent = readFile("${clusterDir}/install-config.yaml.backup")
            echo '====== install-config.yaml ======'
            echo yamlContent
            echo '================================='
        }

        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            cd ${clusterDir}
            openshift-install create cluster --log-level=info
        """

        // Persist cluster state to S3 for disaster recovery with retry logic
        // This is needed for the destroy operation to work
        retry(3) {
            openshiftS3.uploadState([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion,
                workDir: params.workDir,
                metadata: metadata
            ])
        }

        // Extract cluster access information from generated files
        def clusterInfo = getClusterInfo(clusterDir)

        // Validate critical files exist
        def criticalFiles = [
            "${clusterDir}/auth/kubeconfig",
            "${clusterDir}/auth/kubeadmin-password"
        ]
        criticalFiles.each { file ->
            if (!fileExists(file)) {
                error "Critical file missing after cluster creation: ${file}"
            }
        }

        // Create additional backup of auth directory in S3
        sh """
            cd ${clusterDir}
            tar -czf auth-backup.tar.gz auth/
            aws s3 cp auth-backup.tar.gz s3://${params.s3Bucket}/${params.clusterName}/auth-backup.tar.gz --region ${params.awsRegion}
            rm -f auth-backup.tar.gz
        """

        // Deploy Percona Monitoring and Management if enabled
        if (params.deployPMM) {
            env.KUBECONFIG = "${clusterDir}/auth/kubeconfig"

            // Pass SSL configuration to PMM deployment
            def pmmParams = params + [
                clusterName: params.clusterName,
                baseDomain: params.baseDomain,
                awsRegion: params.awsRegion
            ]

            def pmmInfo = deployPMM(pmmParams)

            metadata.pmmDeployed = true
            metadata.pmmImageTag = params.pmmImageTag
            metadata.pmmUrl = pmmInfo.url
            metadata.pmmIp = pmmInfo.ip
            metadata.pmmNamespace = pmmInfo.namespace

            // Update metadata in S3 with PMM information
            openshiftS3.saveMetadata([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion,
                accessKey: awsAccessKey,
                secretKey: awsSecretKey
            ], metadata)

            clusterInfo.pmm = pmmInfo
        }

        return clusterInfo
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to create OpenShift cluster: ${e.message}", params)
        error "Failed to create OpenShift cluster: ${e.message}"
    } finally {
        sh "rm -f ${params.workDir}/${params.clusterName}-state.tar.gz || true"
    }
}

/**
 * Destroys an existing OpenShift cluster and cleans up associated resources.
 *
 * This method handles the complete cluster teardown process including:
 * - Downloading cluster state from S3
 * - Running openshift-install destroy command
 * - Cleaning up S3 state files
 * - Handling partial deletion scenarios
 *
 * @param config Map containing cluster destruction configuration:
 *   - clusterName: Name of the cluster to destroy (required)
 *   - s3Bucket: S3 bucket containing cluster state (required)
 *   - awsRegion: AWS region where cluster exists (required)
 *   - workDir: Working directory (required)
 *   - accessKey: AWS access key (optional, default: env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, default: env.AWS_SECRET_ACCESS_KEY)
 *   - reason: Reason for destruction (optional, for logging)
 *   - destroyedBy: User initiating destruction (optional, for logging)
 *
 * @return Map containing destruction status:
 *   - clusterName: Name of the destroyed cluster
 *   - destroyed: Boolean indicating if destruction was successful
 *   - s3Cleaned: Boolean indicating if S3 cleanup was successful
 *
 * @throws IllegalArgumentException When required parameters are missing
 * @throws RuntimeException When cluster state cannot be found or destruction fails
 *
 * @since 1.0.0
 *
 * @example
 * openshiftCluster.destroy([
 *     clusterName: 'test-cluster-001',
 *     s3Bucket: 'openshift-clusters-bucket',
 *     awsRegion: 'us-east-2',
 *     workDir: env.WORKSPACE
 * ])
 */
def destroy(Map config) {
    def required = ['clusterName', 's3Bucket', 'awsRegion', 'workDir']
    required.each { param ->
        if (!config.containsKey(param) || !config[param]) {
            error "Missing required parameter: ${param}"
        }
    }

    def params = config

    // Use provided credentials or fall back to environment variables
    def awsAccessKey = params.accessKey ?: env.AWS_ACCESS_KEY_ID
    def awsSecretKey = params.secretKey ?: env.AWS_SECRET_ACCESS_KEY

    if (!awsAccessKey || !awsSecretKey) {
        error 'AWS credentials not provided and not found in environment'
    }

    openshiftTools.log('INFO', "Destroying OpenShift cluster: ${params.clusterName}")

    try {
        // Get metadata and cluster state from S3
        def metadataResult = openshiftS3.getMetadata([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion
        ])

        if (!metadataResult) {
            error "No metadata found for cluster ${params.clusterName}."
        }

        // Convert LazyMap to regular Map for serialization
        def metadata = metadataResult.collectEntries { k, v -> [k, v] }

        def clusterDir = "${params.workDir}/${params.clusterName}"
        sh "mkdir -p ${clusterDir}"

        // Download cluster state from S3
        def stateExists = openshiftS3.downloadState([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            workDir: params.workDir
        ])

        if (!stateExists) {
            openshiftTools.log('WARN', "No valid state found for cluster ${params.clusterName}. May be test data or corrupt state file.", params)
            openshiftTools.log('INFO', "Proceeding with S3 cleanup only (no OpenShift resources to destroy).", params)

            // Even without valid state, we should clean up S3
            openshiftS3.cleanup([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion
            ])

            return [
                clusterName: params.clusterName,
                destroyed: false,
                s3Cleaned: true,
                reason: 'Invalid or corrupt state file - S3 cleanup only'
            ]
        }

        // Install OpenShift tools
        def version = metadata?.openshift_version ?: metadata?.openshiftVersion
        if (version) {
            openshiftTools.log('INFO', "Installing OpenShift tools version: ${version}")
            openshiftTools.install([
                openshiftVersion: version
            ])
        } else {
            openshiftTools.log('WARN', "No OpenShift version found in metadata, using default")
        }

        // Destroy the cluster
        openshiftTools.log('INFO', 'Destroying OpenShift cluster...')
        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            cd ${clusterDir}
            if [ -f auth/kubeconfig ]; then
                export KUBECONFIG=\$(pwd)/auth/kubeconfig
                echo "Using kubeconfig: \$KUBECONFIG"
            fi
            openshift-install destroy cluster --log-level=info
        """

        // Clean up S3
        openshiftS3.cleanup([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion
        ])

        return [
            clusterName: params.clusterName,
            destroyed: true,
            s3Cleaned: true
        ]
    } catch (Exception e) {
        error "Failed to destroy OpenShift cluster: ${e.message}"
    } finally {
        sh "rm -rf ${params.workDir}/${params.clusterName} || true"
    }
}

/**
 * Lists all OpenShift clusters stored in S3 with their metadata.
 *
 * @param config Map containing:
 *   - region: AWS region to search (optional, default: 'us-east-2' or env.OPENSHIFT_AWS_REGION)
 *   - bucket: S3 bucket to search (optional, default: 'openshift-clusters-119175775298-us-east-2' or env.OPENSHIFT_S3_BUCKET)
 *   - accessKey: AWS access key (optional, falls back to env.AWS_ACCESS_KEY_ID)
 *   - secretKey: AWS secret key (optional, falls back to env.AWS_SECRET_ACCESS_KEY)
 *
 * @return List of cluster information maps, each containing:
 *   - name: Cluster name
 *   - version: OpenShift version
 *   - region: AWS region
 *   - created_by: User who created the cluster
 *   - created_at: Creation timestamp
 *   - pmm_deployed: 'Yes' or 'No' indicating PMM deployment status
 *   - pmm_version: PMM version if deployed, 'N/A' otherwise
 */
def list(Map config = [:]) {
    def params = [
        region: env.OPENSHIFT_AWS_REGION ?: 'us-east-2',
        bucket: env.OPENSHIFT_S3_BUCKET ?: 'openshift-clusters-119175775298-us-east-2',
        includeResourceCounts: true
    ] + config

    try {
        // Use the new discovery library for comprehensive cluster discovery
        // AWS CLI will use environment variables set by withCredentials blocks
        def discoveredClusters = openshiftDiscovery.discoverClusters([
            bucket: params.bucket,
            region: params.region,
            includeResourceCounts: params.includeResourceCounts
        ])

        // Transform discovered clusters to match expected format
        def clusters = []
        discoveredClusters.each { cluster ->
            def clusterInfo = [
                name: cluster.name,
                version: cluster.metadata?.openshift_version ?: 'Unknown',
                region: cluster.metadata?.aws_region ?: params.region,
                created_by: cluster.metadata?.created_by ?: cluster.metadata?.owner ?: 'Unknown',
                created_at: cluster.metadata?.created_date ?: cluster.metadata?.creationTime ?: 'Unknown',
                pmm_deployed: cluster.metadata?.pmm_deployed ? 'Yes' : 'No',
                pmm_version: cluster.metadata?.pmm_version ?: 'N/A',
                source: cluster.source,
                has_backup: cluster.s3State?.hasBackup ? 'Yes' : 'No',
                resource_count: cluster.resources?.size() ?: 0
            ]
            // Include base name if present (for clusters with random suffixes)
            if (cluster.baseName) {
                clusterInfo.baseName = cluster.baseName
            }
            clusters << clusterInfo
        }

        // Log discovery summary
        def s3OnlyCount = clusters.count { it.source == 's3' }
        def awsOnlyCount = clusters.count { it.source == 'aws-tags' }
        def combinedCount = clusters.count { it.source == 'combined' }

        openshiftTools.log('INFO', "Cluster discovery complete: ${clusters.size()} total " +
            "(${combinedCount} with both S3+AWS, ${awsOnlyCount} AWS-only, ${s3OnlyCount} S3-only)", params)

        // Check for potential issues
        def orphaned = discoveredClusters.findAll { !it.s3State?.hasBackup && it.source == 'aws-tags' }
        if (orphaned) {
            openshiftTools.log('WARN', "Found ${orphaned.size()} cluster(s) without S3 backup: " +
                orphaned.collect { it.name }.join(', '), params)
        }

        def stale = discoveredClusters.findAll { it.source == 's3' && (it.resources?.isEmpty() ?: true) }
        if (stale) {
            openshiftTools.log('INFO', "Found ${stale.size()} cluster(s) with S3 state but no AWS resources " +
                "(possibly deleted): " + stale.collect { it.name }.join(', '), params)
        }

        return clusters
    } catch (Exception e) {
        error "Failed to list OpenShift clusters: ${e.message}"
    }
}

/**
 * Validates cluster creation parameters to ensure they meet requirements.
 * Throws error if validation fails.
 *
 * @param params Map of parameters to validate
 */
def validateParams(Map params) {
    // Validate cluster name format:
    // - Must start with a lowercase letter
    // - Can contain lowercase letters and numbers
    // - Can contain hyphens, but not consecutive or at start/end
    // - Maximum 20 characters
    if (!params.clusterName.matches(/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/)) {
        error "Invalid cluster name: '${params.clusterName}'. Must start with lowercase letter, contain only lowercase letters, numbers, and non-consecutive hyphens."
    }

    if (params.clusterName.length() > 20) {
        error 'Cluster name too long. Maximum 20 characters.'
    }

    // Validate OpenShift version format - supports channels and specific versions
    if (!params.openshiftVersion.matches(/^(latest|stable|fast|candidate|eus-[0-9]+\.[0-9]+|latest-[0-9]+\.[0-9]+|stable-[0-9]+\.[0-9]+|fast-[0-9]+\.[0-9]+|candidate-[0-9]+\.[0-9]+|[0-9]+\.[0-9]+(\.[0-9]+)?)$/)) {
        error "Invalid OpenShift version: '${params.openshiftVersion}'. Use specific version (4.16.20), channel (latest), or channel-version (stable-4.16)."
    }

    // WARNING: Region restriction due to OpenShift installer AMI availability and DNS zones
    // TODO: Add support for additional regions once AMIs and DNS zones are configured
    if (params.awsRegion != 'us-east-2') {
        error "Unsupported AWS region: '${params.awsRegion}'. Currently only 'us-east-2' is supported."
    }
}

/**
 * Generates OpenShift install-config.yaml based on provided parameters.
 *
 * @param params Map containing cluster configuration
 * @return String YAML content for install-config.yaml
 */
def generateInstallConfig(Map params) {
    // Helper function to escape YAML strings if needed
    def escapeYaml = { String value ->
        if (!value) return value
        // Check if string contains special characters that need quoting
        if (value.contains(':') || value.contains('#') || value.contains('@') ||
            value.contains('|') || value.contains('>') || value.contains('*') ||
            value.contains('&') || value.contains('!') || value.contains('%') ||
            value.contains('{') || value.contains('}') || value.contains('[') ||
            value.contains(']') || value.contains(',') || value.contains('"') ||
            value.contains("'") || value.startsWith('-') || value.startsWith('?')) {
            // Escape quotes and wrap in double quotes
            return '"' + value.replace('"', '\\"') + '"'
        }
        return value
    }

    // Generate YAML directly using a string template
    // This is more elegant and avoids JSON conversion complexity
    def yaml = """apiVersion: v1
baseDomain: ${params.baseDomain}
compute:
- architecture: amd64
  hyperthreading: Enabled
  name: worker
  platform:
    aws:
      type: ${params.workerType}
  replicas: ${params.workerCount}
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform:
    aws:
      type: ${params.masterType}
  replicas: 3
metadata:
  name: ${params.clusterName}
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: OVNKubernetes
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: ${params.awsRegion}
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: "${params.deleteAfterHours}"
      team: ${escapeYaml(params.teamName)}
      product: ${escapeYaml(params.productTag)}
      owner: ${escapeYaml(params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins')}
      creation-time: "${(System.currentTimeMillis() / 1000).longValue()}"
pullSecret: '${params.pullSecret}'
sshKey: '${params.sshPublicKey}'
"""

    return yaml
}

/**
 * Creates metadata JSON file with cluster information for tracking.
 *
 * Generates comprehensive metadata including creation timestamp, user info,
 * and cluster specifications for audit and lifecycle management.
 *
 * @param params Map containing cluster configuration
 * @param clusterDir Directory where metadata will be saved
 *
 * @return Map of metadata that was saved
 *
 * @since 1.0.0
 */
def createMetadata(Map params, String clusterDir) {
    def metadata = [
        cluster_name: params.clusterName,
        openshift_version: params.openshiftVersion,
        aws_region: params.awsRegion,
        created_date: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        created_by: params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
        jenkins_build: env.BUILD_NUMBER ?: '1',
        master_type: params.masterType,
        worker_type: params.workerType,
        worker_count: params.workerCount
    ]

    def json = new JsonBuilder(metadata).toPrettyString()
    writeFile file: "${clusterDir}/metadata.json", text: json

    return metadata
}

/**
 * Deploys Percona Monitoring and Management (PMM) to the OpenShift cluster.
 *
 * Creates namespace, sets permissions, and deploys via Helm. Configures
 * OpenShift-specific settings including anyuid SCC and route creation.
 *
 * @param params Map containing PMM deployment configuration:
 *   - pmmImageTag: Docker image tag to deploy (required)
 *   - pmmHelmChartVersion: Helm chart version (required)
 *   - pmmImageRepository: Docker image repository (required)
 *   - pmmNamespace: Namespace for PMM deployment (optional, default: 'pmm-monitoring')
 *   - pmmAdminPassword: Admin password for PMM (optional, '<GENERATED>' for random password)
 *   - pmmHelmChartBranch: Branch from percona-helm-charts repo (optional, overrides chart version)
 *   - clusterName: Name of the cluster (for logging)
 *
 * @return Map with PMM access details:
 *   - url: HTTPS URL for PMM web interface
 *   - username: Admin username (always 'admin')
 *   - password: Admin password (generated or as specified)
 *   - namespace: Kubernetes namespace where PMM is deployed
 *   - passwordGenerated: Boolean indicating if password was auto-generated
 *
 * @throws RuntimeException When Helm deployment fails or route creation errors
 *
 * @since 1.0.0
 *
 * @example
 * def pmm = deployPMM([
 *     pmmImageTag: '3.3.1',
 *     pmmHelmChartVersion: '1.4.7',
 *     pmmImageRepository: 'percona/pmm-server',
 *     pmmNamespace: 'monitoring'
 * ])
 * println "PMM UI: ${pmm.url}"
 */
def deployPMM(Map params) {
    openshiftTools.log('INFO',
        "Deploying PMM from ${params.pmmImageRepository}:${params.pmmImageTag} to namespace ${params.pmmNamespace}...",
        params)

    // Install Helm if not already installed
    openshiftTools.installHelm()

    // Determine service type based on SSL configuration
    // When SSL is enabled, PMM needs LoadBalancer for ACM certificate
    def serviceType = params.enableSSL ? 'LoadBalancer' : 'ClusterIP'

    openshiftTools.log('INFO', "PMM service type: ${serviceType} (SSL enabled: ${params.enableSSL})")

    // Check if we're using a custom branch
    def chartPath = "percona/pmm"
    def usingCustomBranch = params.pmmHelmChartBranch && params.pmmHelmChartBranch.trim() != ''
    def tempDir = null

    if (usingCustomBranch) {
        openshiftTools.log('INFO', "Using custom branch '${params.pmmHelmChartBranch}' from percona-helm-charts repository")

        try {
            // Create secure temporary directory
            tempDir = sh(
                script: "mktemp -d /tmp/percona-helm-charts.XXXXXX",
                returnStdout: true
            ).trim()
            
            openshiftTools.log('INFO', "Created temporary directory: ${tempDir}")

            // First validate that the branch exists
            def branchExists = sh(
                script: """
                    export PATH="\$HOME/.local/bin:\$PATH"
                    # Check if branch exists in remote repository
                    git ls-remote --heads https://github.com/percona/percona-helm-charts.git refs/heads/${params.pmmHelmChartBranch} | grep -q ${params.pmmHelmChartBranch}
                """,
                returnStatus: true
            ) == 0

            if (!branchExists) {
                error("Branch '${params.pmmHelmChartBranch}' does not exist in percona-helm-charts repository")
            }

            // Clone the repository and checkout the specified branch
            def gitStatus = sh(
                script: """
                    export PATH="\$HOME/.local/bin:\$PATH"
                    set -e  # Exit on any error
                    
                    # Clone the repository
                    echo "Cloning percona-helm-charts repository..."
                    git clone --depth 1 --branch ${params.pmmHelmChartBranch} \
                        https://github.com/percona/percona-helm-charts.git ${tempDir}
                    
                    # Verify we're on the correct branch
                    cd ${tempDir}
                    CURRENT_BRANCH=\$(git branch --show-current)
                    if [[ "\$CURRENT_BRANCH" != "${params.pmmHelmChartBranch}" ]]; then
                        echo "ERROR: Expected branch '${params.pmmHelmChartBranch}' but got '\$CURRENT_BRANCH'"
                        exit 1
                    fi
                    
                    # Show current branch for confirmation
                    echo "Successfully checked out branch: \$CURRENT_BRANCH"
                    echo "Commit: \$(git rev-parse --short HEAD)"
                    
                    # Verify PMM chart exists
                    if [[ ! -d "${tempDir}/charts/pmm" ]]; then
                        echo "ERROR: PMM chart not found at ${tempDir}/charts/pmm"
                        exit 1
                    fi
                """,
                returnStatus: true
            )

            if (gitStatus != 0) {
                error("Failed to clone repository or checkout branch '${params.pmmHelmChartBranch}'")
            }

            // Use the local chart path
            chartPath = "${tempDir}/charts/pmm"
            
        } catch (Exception e) {
            // Clean up on error
            if (tempDir) {
                sh "rm -rf ${tempDir} || true"
            }
            throw new Exception("Failed to setup custom Helm chart branch: ${e.message}", e)
        }
    }

    try {
        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            # Create namespace
            oc create namespace ${params.pmmNamespace} || true

            # Grant anyuid SCC permissions - required for PMM containers
            # WARNING: PMM requires elevated privileges to run monitoring components
            oc adm policy add-scc-to-user anyuid -z default -n ${params.pmmNamespace}
            oc adm policy add-scc-to-user anyuid -z pmm -n ${params.pmmNamespace}
        """

        // Only add Helm repo if not using custom branch
        if (!usingCustomBranch) {
            sh """
                export PATH="\$HOME/.local/bin:\$PATH"
                # Add Percona Helm repo
                helm repo add percona https://percona.github.io/percona-helm-charts/ || true
                helm repo update
            """
        }

        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            # Deploy PMM using Helm (will be retried if it fails)
            echo "Deploying PMM with Helm..."
        """

        // Prepare helm command with optional password
        def helmCommand = """
            export PATH="\$HOME/.local/bin:\$PATH"
            helm upgrade --install pmm ${chartPath} \\
                --namespace ${params.pmmNamespace}"""

        // Add version flag only when not using custom branch
        if (!usingCustomBranch) {
            helmCommand += " \\\n            --version ${params.pmmHelmChartVersion}"
        }

        helmCommand += """ \\
                --set platform=openshift \\
                --set service.type=${serviceType} \\
                --set image.repository=${params.pmmImageRepository} \\
                --set image.tag=${params.pmmImageTag}"""

        // Set password based on user input
        // Only generate random password if value is '<GENERATED>' or empty
        if (params.pmmAdminPassword && params.pmmAdminPassword != '<GENERATED>') {
            // Escape single quotes in password for shell safety
            // This prevents command injection and syntax errors
            def escapedPassword = params.pmmAdminPassword.replaceAll("'", "'\"'\"'")
            helmCommand += " \\\n            --set secret.pmm_password='${escapedPassword}'"
        }
        // If password is '<GENERATED>' or empty, Helm will auto-generate one

        helmCommand += ' \\\n            --wait --timeout 10m'

        // Log the full Helm command for debugging
        openshiftTools.log('DEBUG', "Executing Helm command:")
        echo helmCommand

        sh helmCommand

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        # Create OpenShift route for HTTPS access with passthrough termination
        # This allows end-to-end TLS for both HTTPS and GRPC traffic
        oc create route passthrough pmm-https \
            --service=monitoring-service \
            --port=https \
            -n ${params.pmmNamespace} || true
    """

    def pmmUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            oc get route pmm-https -n ${params.pmmNamespace} -o jsonpath='{.spec.host}'
        """,
        returnStdout: true
    ).trim()

    // Get the public IP address from the OpenShift ingress controller
    // The monitoring-service is a ClusterIP (internal only), so we need the ingress IP
    def pmmIp = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"

            # AWS provides hostname in LoadBalancer status, not direct IP
            INGRESS_HOSTNAME=\$(oc get service -n openshift-ingress router-default \
                -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)

            if [[ -n "\$INGRESS_HOSTNAME" ]]; then
                # Resolve AWS ELB hostname to IP address
                # Use getent (available by default on Oracle Linux) with nslookup fallback
                getent hosts "\$INGRESS_HOSTNAME" 2>/dev/null | awk '{print \$1; exit}' || \
                    nslookup "\$INGRESS_HOSTNAME" 2>/dev/null | grep -A 1 "^Name:" | grep "Address" | head -1 | awk '{print \$2}'
            else
                # No hostname found - ingress might not be ready yet
                echo ''
            fi
        """,
        returnStdout: true
    ).trim()

    // Get the actual password from the secret (either set or generated)
    def actualPassword = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            oc get secret pmm-secret -n ${params.pmmNamespace} -o jsonpath='{.data.PMM_ADMIN_PASSWORD}' | base64 -d
        """,
        returnStdout: true
    ).trim()

    def result = [
        url: "https://${pmmUrl}",
        ip: pmmIp ?: 'N/A',  // Return 'N/A' if we couldn't determine the IP
        username: 'admin',
        password: actualPassword,
        namespace: params.pmmNamespace,
        // Indicate if password was auto-generated
        passwordGenerated: !params.pmmAdminPassword || params.pmmAdminPassword == '<GENERATED>'
    ]

    // Configure SSL with ACM if enabled
    if (params.enableSSL && serviceType == 'LoadBalancer') {
        openshiftTools.log('INFO', 'Configuring ACM SSL for PMM LoadBalancer...')

        // Wait for LoadBalancer to be ready
        sleep(time: 30, unit: 'SECONDS')

        // Get LoadBalancer hostname
        def lbHostname = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                oc get svc monitoring-service -n ${params.pmmNamespace} -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
            """,
            returnStdout: true
        ).trim()

        if (lbHostname) {
            openshiftTools.log('INFO', "PMM LoadBalancer hostname: ${lbHostname}")

            // Find ACM certificate for the domain
            def pmmDomain = params.pmmCustomDomain ?: "pmm-${params.clusterName}.${params.baseDomain}"
            def wildcardDomain = "*.${params.baseDomain}"

            // Note: This requires AWS credentials to be available
            def acmArn = awsCertificates.findACMCertificate([
                domain: wildcardDomain,
                region: params.awsRegion ?: 'us-east-2'
            ])

            if (acmArn) {
                openshiftTools.log('INFO', "Found ACM certificate: ${acmArn}")

                // Apply ACM certificate to LoadBalancer
                def acmApplied = awsCertificates.applyACMToLoadBalancer([
                    namespace: params.pmmNamespace,
                    serviceName: 'monitoring-service',
                    certificateArn: acmArn,
                    kubeconfig: env.KUBECONFIG
                ])

                if (acmApplied) {
                    // Create Route53 DNS record
                    def dnsCreated = awsCertificates.createRoute53Record([
                        domain: pmmDomain,
                        value: lbHostname,
                        region: params.awsRegion ?: 'us-east-2'
                    ])

                    if (dnsCreated) {
                        result.sslDomain = pmmDomain
                        result.sslUrl = "https://${pmmDomain}"
                        openshiftTools.log('INFO', "PMM SSL configured successfully at: https://${pmmDomain}")
                    } else {
                        openshiftTools.log('WARN', 'Failed to create Route53 DNS record for PMM')
                    }
                } else {
                    openshiftTools.log('WARN', 'Failed to apply ACM certificate to PMM LoadBalancer')
                }
            } else {
                openshiftTools.log('WARN', "No ACM certificate found for ${wildcardDomain}")
            }
        } else {
            openshiftTools.log('WARN', 'LoadBalancer hostname not available yet')
        }
    }

        return result
        
    } finally {
        // Clean up temporary directory if it was created
        if (tempDir) {
            openshiftTools.log('INFO', "Cleaning up temporary directory: ${tempDir}")
            sh "rm -rf ${tempDir} || true"
        }
    }
}

/**
 * Validates that a PMM Docker image exists in the registry before deployment.
 *
 * This method uses the OpenShift CLI to check if a Docker image is available
 * without requiring a cluster connection or Docker daemon. It prevents wasted
 * time and resources by catching invalid image tags early in the pipeline.
 *
 * @param params Map containing:
 *   - pmmImageRepository: Docker image repository (e.g., 'percona/pmm-server')
 *   - pmmImageTag: Docker image tag to validate (e.g., '3.3.1', 'dev-latest')
 *
 * @return boolean true if image exists, false otherwise
 *
 * @example
 * if (!validatePMMImage([
 *     pmmImageRepository: 'percona/pmm-server',
 *     pmmImageTag: '3.3.1'
 * ])) {
 *     error "PMM image not found"
 * }
 */
def validatePMMImage(Map params) {
    def imageFullName = "${params.pmmImageRepository}:${params.pmmImageTag}"

    openshiftTools.log('INFO', "Validating PMM image: ${imageFullName}", params)

    try {
        // Use KUBECONFIG=/dev/null to ensure no cluster connection is attempted
        // The --filter-by-os flag ensures we get a specific manifest for AMD64
        def result = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                KUBECONFIG=/dev/null oc image info ${imageFullName} --filter-by-os=linux/amd64 >/dev/null 2>&1
            """,
            returnStatus: true
        )

        if (result == 0) {
            openshiftTools.log('INFO', "Successfully validated PMM image: ${imageFullName}", params)
            return true
        } else {
            openshiftTools.log('ERROR', "PMM image not found in registry: ${imageFullName}", params)
            openshiftTools.log('ERROR',
                "Please verify the image tag exists at https://hub.docker.com/r/${params.pmmImageRepository}/tags",
                params)
            return false
        }
    } catch (Exception e) {
        openshiftTools.log('ERROR', "Failed to validate PMM image: ${e.message}", params)
        return false
    }
}

/**
 * Extracts cluster access information from OpenShift installation files.
 *
 * Parses kubeconfig and retrieves console route to provide complete
 * cluster access information for users and automation.
 *
 * @param clusterDir Directory containing OpenShift installation files
 *
 * @return Map with cluster access details:
 *   - apiUrl: Kubernetes API server URL
 *   - consoleUrl: OpenShift web console URL (if available)
 *   - kubeadminPassword: Initial admin password
 *   - kubeconfig: Path to kubeconfig file
 *   - clusterDir: Local directory containing cluster files
 *
 * @since 1.0.0
 */
def getClusterInfo(String clusterDir) {
    def info = [:]

    info.apiUrl = sh(
        script: "grep 'server:' ${clusterDir}/auth/kubeconfig | head -1 | awk '{print \$2}'",
        returnStdout: true
    ).trim()

    def consoleRoute = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export KUBECONFIG=${clusterDir}/auth/kubeconfig
            oc get route -n openshift-console console -o jsonpath='{.spec.host}' 2>/dev/null || echo ''
        """,
        returnStdout: true
    ).trim()

    if (consoleRoute) {
        info.consoleUrl = "https://${consoleRoute}"
    }

    def kubeadminFile = "${clusterDir}/auth/kubeadmin-password"
    if (fileExists(kubeadminFile)) {
        info.kubeadminPassword = readFile(kubeadminFile).trim()
    }

    info.kubeconfig = "${clusterDir}/auth/kubeconfig"
    info.clusterDir = clusterDir

    return info
}

