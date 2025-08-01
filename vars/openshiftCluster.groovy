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
 *   - pmmVersion: PMM version to deploy (optional, default: '3.3.0')
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
        pmmVersion: '3.3.0',
        pmmNamespace: 'pmm-monitoring',
        pmmAdminPassword: '<GENERATED>'  // Default to auto-generation
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
        openshiftS3.ensureS3BucketExists(params.s3Bucket, params.awsRegion, awsAccessKey, awsSecretKey)

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
        def installConfigData = generateInstallConfig(params)

        // Use writeYaml from Pipeline Utility Steps plugin to write YAML
        writeYaml file: "${clusterDir}/install-config.yaml", data: installConfigData
        // Also create a backup copy
        writeYaml file: "${clusterDir}/install-config.yaml.backup", data: installConfigData

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
        retry(3) {
            openshiftS3.uploadState([
                bucket: params.s3Bucket,
                clusterName: params.clusterName,
                region: params.awsRegion,
                workDir: params.workDir,
                metadata: metadata,
                accessKey: awsAccessKey,
                secretKey: awsSecretKey
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
            aws s3 cp auth-backup.tar.gz s3://${params.s3Bucket}/${params.clusterName}/auth-backup.tar.gz
            rm -f auth-backup.tar.gz
        """

        // Deploy Percona Monitoring and Management if enabled
        if (params.deployPMM) {
            env.KUBECONFIG = "${clusterDir}/auth/kubeconfig"
            def pmmInfo = deployPMM(params)

            metadata.pmmDeployed = true
            metadata.pmmVersion = params.pmmVersion
            metadata.pmmUrl = pmmInfo.url
            metadata.pmmNamespace = pmmInfo.namespace

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
        openshiftTools.log('ERROR', "Failed to create OpenShift cluster: ${e.toString()}", params)
        error "Failed to create OpenShift cluster: ${e.toString()}"
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
        def metadata = openshiftS3.getMetadata([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            accessKey: awsAccessKey,
            secretKey: awsSecretKey
        ])

        if (!metadata) {
            error "No metadata found for cluster ${params.clusterName}."
        }

        def clusterDir = "${params.workDir}/${params.clusterName}"
        sh "mkdir -p ${clusterDir}"

        // Download cluster state from S3
        def stateExists = openshiftS3.downloadState([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            workDir: params.workDir,
            accessKey: awsAccessKey,
            secretKey: awsSecretKey
        ])

        if (!stateExists) {
            error "No state found for cluster ${params.clusterName}."
        }

        // Install OpenShift tools
        if (metadata?.openshiftVersion) {
            openshiftTools.install([
                openshiftVersion: metadata.openshiftVersion
            ])
        }

        // Destroy the cluster
        if (stateExists) {
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
        }

        // Clean up S3
        openshiftS3.cleanup([
            bucket: params.s3Bucket,
            clusterName: params.clusterName,
            region: params.awsRegion,
            accessKey: awsAccessKey,
            secretKey: awsSecretKey
        ])

        return [
            clusterName: params.clusterName,
            destroyed: true,
            s3Cleaned: true
        ]
    } catch (Exception e) {
        error "Failed to destroy OpenShift cluster: ${e.toString()}"
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
        bucket: env.OPENSHIFT_S3_BUCKET ?: 'openshift-clusters-119175775298-us-east-2'
    ] + config

    // Use provided credentials or fall back to environment variables
    def awsAccessKey = params.accessKey ?: env.AWS_ACCESS_KEY_ID
    def awsSecretKey = params.secretKey ?: env.AWS_SECRET_ACCESS_KEY

    if (!awsAccessKey || !awsSecretKey) {
        error 'AWS credentials not provided and not found in environment'
    }

    try {
        def clusters = []

        // Get cluster names using the S3 library
        // This replaces the previous AWS CLI approach for better error handling
        // Pass AWS credentials
        def clusterNames = openshiftS3.listClusters([
            bucket: params.bucket,
            region: params.region,
            accessKey: awsAccessKey,
            secretKey: awsSecretKey
        ])

        clusterNames.each { clusterName ->
            def metadata = openshiftS3.getMetadata([
                bucket: params.bucket,
                clusterName: clusterName,
                region: params.region,
                accessKey: awsAccessKey,
                secretKey: awsSecretKey
            ])

            if (metadata) {
                clusters << [
                    name: clusterName,
                    version: metadata.openshift_version ?: 'Unknown',
                    region: metadata.aws_region ?: params.region,
                    created_by: metadata.created_by ?: 'Unknown',
                    created_at: metadata.created_date ?: 'Unknown',
                    pmm_deployed: metadata.pmm_deployed ? 'Yes' : 'No',
                    pmm_version: metadata.pmm_version ?: 'N/A'
                ]
            }
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
    def config = [
        apiVersion: 'v1',
        baseDomain: params.baseDomain,
        compute: [[
            architecture: 'amd64',
            hyperthreading: 'Enabled',
            name: 'worker',
            platform: [
                aws: [
                    type: params.workerType
                ]
            ],
            replicas: params.workerCount
        ]],
        controlPlane: [
            architecture: 'amd64',
            hyperthreading: 'Enabled',
            name: 'master',
            platform: [
                aws: [
                    type: params.masterType
                ]
            ],
            replicas: 3
        ],
        metadata: [
            name: params.clusterName
        ],
        networking: [
            clusterNetwork: [[
                cidr: '10.128.0.0/14',
                hostPrefix: 23
            ]],
            machineNetwork: [[
                cidr: '10.0.0.0/16'
            ]],
            networkType: 'OVNKubernetes',
            serviceNetwork: ['172.30.0.0/16']
        ],
        platform: [
            aws: [
                region: params.awsRegion,
                // AWS tags are crucial for cost tracking and automated cleanup
                userTags: [
                    'iit-billing-tag': 'openshift',  // Required for internal billing
                    'delete-cluster-after-hours': params.deleteAfterHours,  // Used by cleanup automation
                    'team': params.teamName,
                    'product': params.productTag,
                    'owner': params.buildUser ?: env.BUILD_USER_ID ?: 'jenkins',
                    'creation-time': (System.currentTimeMillis() / 1000).longValue().toString()  // Convert milliseconds to seconds for Unix timestamp
                ]
            ]
        ],
        pullSecret: params.pullSecret,
        sshKey: params.sshPublicKey
    ]

    // Return as Map structure - will be converted to YAML by Jenkins writeYaml step
    // This approach avoids manual YAML serialization issues
    return config
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
 *   - pmmVersion: Version to deploy (required)
 *   - pmmNamespace: Namespace for PMM deployment (optional, default: 'pmm-monitoring')
 *   - pmmAdminPassword: Admin password for PMM (optional, '<GENERATED>' for random password)
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
 *     pmmVersion: '3.3.0',
 *     pmmNamespace: 'monitoring'
 * ])
 * println "PMM UI: ${pmm.url}"
 */
def deployPMM(Map params) {
    openshiftTools.log('INFO', "Deploying PMM ${params.pmmVersion} to namespace ${params.pmmNamespace}...", params)

    // Install Helm if not already installed
    openshiftTools.installHelm()

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        # Create namespace
        oc create namespace ${params.pmmNamespace} || true

        # Grant anyuid SCC permissions - required for PMM containers
        # WARNING: PMM requires elevated privileges to run monitoring components
        oc adm policy add-scc-to-user anyuid -z default -n ${params.pmmNamespace}
        oc adm policy add-scc-to-user anyuid -z pmm -n ${params.pmmNamespace}

        # Add Percona Helm repo
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo update

        # Deploy PMM using Helm (will be retried if it fails)
        echo "Deploying PMM with Helm..."
    """

    // Prepare helm command with optional password
    def helmCommand = """
        export PATH="\$HOME/.local/bin:\$PATH"
        helm upgrade --install pmm percona/pmm \
            --namespace ${params.pmmNamespace} \
            --version ${params.pmmVersion.startsWith('3.') ? '1.4.6' : '1.3.12'} \
            --set platform=openshift \
            --set service.type=ClusterIP"""

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

    sh helmCommand

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        # Create OpenShift route for HTTPS access
        # Fixed: Use correct service name and HTTP port for edge termination
        oc create route edge pmm-https \
            --service=monitoring-service \
            --port=http \
            --insecure-policy=Redirect \
            -n ${params.pmmNamespace} || true
    """

    def pmmUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            oc get route pmm-https -n ${params.pmmNamespace} -o jsonpath='{.spec.host}'
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

    return [
        url: "https://${pmmUrl}",
        username: 'admin',
        password: actualPassword,
        namespace: params.pmmNamespace,
        passwordGenerated: !params.pmmAdminPassword || params.pmmAdminPassword == '<GENERATED>'  // Indicate if password was auto-generated
    ]
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

/**
 * Backward compatibility method - delegates to create().
 *
 * Allows calling the library directly without method name for backward
 * compatibility with existing pipelines.
 *
 * @deprecated Use create() instead
 * @param config Map containing cluster configuration
 * @return Map containing cluster information
 * @since 1.0.0
 *
 * @example
 * // Old style (deprecated)
 * openshiftCluster([clusterName: 'test'])
 *
 * // New style (preferred)
 * openshiftCluster.create([clusterName: 'test'])
 */
def call(Map config) {
    return create(config)
}
