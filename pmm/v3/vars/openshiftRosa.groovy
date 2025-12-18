/**
 * OpenShift ROSA HCP operations library.
 *
 * This library provides generic ROSA (Red Hat OpenShift Service on AWS) cluster
 * lifecycle management. It supports Hosted Control Planes (HCP) for fast (~10-15 min)
 * cluster provisioning.
 *
 * For PMM HA-specific operations (Helm deployment, ECR pull-through cache),
 * see pmmHaRosa.groovy which extends this library.
 *
 * Prerequisites:
 * - rosa CLI installed on Jenkins agents (or use installRosaCli())
 * - Red Hat offline token stored as Jenkins credential
 * - AWS credentials with ROSA permissions
 *
 * @since 1.0.0
 *
 * @example Basic usage:
 * // Import the library
 * library changelog: false, identifier: 'lib@master', retriever: modernSCM([
 *     $class: 'GitSCMSource',
 *     remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
 * ])
 *
 * // Create ROSA HCP cluster
 * def cluster = openshiftRosa.createCluster([
 *     clusterName: 'my-cluster',
 *     region: 'us-east-2',
 *     replicas: 3
 * ])
 *
 * // Configure access (admin user + kubeconfig)
 * def access = openshiftRosa.configureAccess([
 *     clusterName: cluster.clusterName
 * ])
 *
 * // Delete cluster when done
 * openshiftRosa.deleteCluster([
 *     clusterName: cluster.clusterName
 * ])
 */

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.Field

// ============================================================================
// Configuration Constants
// ============================================================================

@Field static final String DEFAULT_REGION = 'us-east-2'
@Field static final String DEFAULT_OPENSHIFT_VERSION = '4.18'
@Field static final String DEFAULT_INSTANCE_TYPE = 'm5.xlarge'
@Field static final int DEFAULT_REPLICAS = 3
@Field static final int DEFAULT_MAX_CLUSTERS = 10

// AWS Account ID and ECR configuration
@Field static final String AWS_ACCOUNT_ID = '119175775298'
@Field static final String ECR_REGION = 'us-east-2'
@Field static final String ECR_PREFIX = '119175775298.dkr.ecr.us-east-2.amazonaws.com/docker-hub'

// PMM-specific OIDC/operator roles prefix
@Field static final String PMM_OPERATOR_ROLE_PREFIX = 'pmm-rosa-ha'

// ============================================================================
// Tool Installation
// ============================================================================

/**
 * Installs ROSA CLI if not already available.
 *
 * Downloads and installs the latest ROSA CLI from Red Hat's official mirror.
 * Supports both x86_64 and arm64 architectures.
 *
 * @param config Map containing:
 *   - version: ROSA CLI version to install (optional, default: 'latest')
 *
 * @return String The installed ROSA version
 */
def installRosaCli(Map config = [:]) {
    def version = config.version ?: 'latest'

    echo "Installing ROSA CLI (version: ${version})..."

    sh '''
        export PATH="$HOME/.local/bin:$PATH"

        # Check if rosa is already installed and working
        if command -v rosa &>/dev/null && rosa version &>/dev/null; then
            INSTALLED_VERSION=$(rosa version 2>/dev/null | head -1 || echo "unknown")
            echo "ROSA CLI already installed: $INSTALLED_VERSION"
            exit 0
        fi

        # Create local bin directory
        mkdir -p $HOME/.local/bin

        # Detect architecture
        ARCH=$(uname -m)
        case $ARCH in
            x86_64|amd64)
                ROSA_ARCH="linux"
                ;;
            aarch64|arm64)
                ROSA_ARCH="linux-arm64"
                ;;
            *)
                echo "ERROR: Unsupported architecture: $ARCH"
                exit 1
                ;;
        esac

        # Download ROSA CLI for detected architecture
        echo "Downloading ROSA CLI for ${ROSA_ARCH}..."
        ROSA_URL="https://mirror.openshift.com/pub/openshift-v4/clients/rosa/latest/rosa-${ROSA_ARCH}.tar.gz"
        curl -sSL "$ROSA_URL" -o /tmp/rosa.tar.gz

        # Extract and install
        tar -xzf /tmp/rosa.tar.gz -C $HOME/.local/bin rosa
        chmod +x $HOME/.local/bin/rosa

        # Verify installation
        rosa version
        rm -f /tmp/rosa.tar.gz
    '''

    def installedVersion = sh(
        script: '''
            export PATH="$HOME/.local/bin:$PATH"
            rosa version | head -1
        ''',
        returnStdout: true
    ).trim()

    echo "ROSA CLI installed: ${installedVersion}"
    return installedVersion
}

/**
 * Installs OpenShift CLI (oc) if not already available.
 *
 * @param config Map containing:
 *   - version: OpenShift version (optional, default: '4.18')
 *
 * @return String The installed oc version
 */
def installOcCli(Map config = [:]) {
    def version = config.version ?: DEFAULT_OPENSHIFT_VERSION

    echo "Installing OpenShift CLI (oc) for version ${version}..."

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        # Check if oc is already installed and working
        if command -v oc &>/dev/null && oc version --client &>/dev/null; then
            INSTALLED_VERSION=\$(oc version --client 2>/dev/null | head -1 || echo "unknown")
            echo "OpenShift CLI already installed: \$INSTALLED_VERSION"
            exit 0
        fi

        # Create local bin directory
        mkdir -p \$HOME/.local/bin

        # Detect architecture
        ARCH=\$(uname -m)
        case \$ARCH in
            x86_64|amd64)
                OC_ARCH="linux"
                ;;
            aarch64|arm64)
                OC_ARCH="linux-arm64"
                ;;
            *)
                echo "ERROR: Unsupported architecture: \$ARCH"
                exit 1
                ;;
        esac

        # Download OC CLI for detected architecture
        echo "Downloading OpenShift CLI for \${OC_ARCH}..."
        OC_URL="https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable-${version}/openshift-client-\${OC_ARCH}.tar.gz"
        curl -sSL "\$OC_URL" -o /tmp/oc.tar.gz

        # Extract and install
        tar -xzf /tmp/oc.tar.gz -C \$HOME/.local/bin oc kubectl
        chmod +x \$HOME/.local/bin/oc \$HOME/.local/bin/kubectl

        # Verify installation
        oc version --client
        rm -f /tmp/oc.tar.gz
    """

    def installedVersion = sh(
        script: '''
            export PATH="$HOME/.local/bin:$PATH"
            oc version --client | head -1
        ''',
        returnStdout: true
    ).trim()

    echo "OpenShift CLI installed: ${installedVersion}"
    return installedVersion
}

/**
 * Logs into ROSA using Red Hat offline token.
 *
 * @param config Map containing:
 *   - token: Red Hat offline token (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *
 * @return boolean true if login successful
 */
def login(Map config) {
    if (!config.token) {
        error 'Red Hat offline token is required for ROSA login'
    }

    def region = config.region ?: DEFAULT_REGION

    echo 'Logging into ROSA...'

    def result = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${region}
            rosa login --token='${config.token}'
            rosa whoami
        """,
        returnStatus: true
    )

    if (result != 0) {
        error 'Failed to login to ROSA'
    }

    echo 'Successfully logged into ROSA'
    return true
}

// ============================================================================
// Cluster Lifecycle
// ============================================================================

/**
 * Creates a new ROSA HCP cluster.
 *
 * Creates a ROSA cluster with Hosted Control Planes (HCP) for fast provisioning.
 * HCP clusters provision in ~10-15 minutes vs ~40 minutes for classic ROSA.
 *
 * @param config Map containing:
 *   - clusterName: Name for the cluster (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - openshiftVersion: OpenShift version (optional, default: '4.18')
 *   - replicas: Number of worker nodes (optional, default: 3)
 *   - instanceType: EC2 instance type (optional, default: 'm5.xlarge')
 *   - subnetIds: Comma-separated subnet IDs for existing VPC (optional)
 *   - machineCidr: VPC CIDR block (optional, auto-generated if not provided)
 *   - oidcConfigId: Pre-existing OIDC config ID (optional)
 *   - tags: Map of AWS tags to apply (optional)
 *
 * @return Map containing cluster information:
 *   - clusterName: Full cluster name
 *   - clusterId: ROSA cluster ID
 *   - apiUrl: API server URL
 *   - consoleUrl: OpenShift console URL
 *   - region: AWS region
 */
def createCluster(Map config) {
    def params = [
        region: DEFAULT_REGION,
        openshiftVersion: DEFAULT_OPENSHIFT_VERSION,
        replicas: DEFAULT_REPLICAS,
        instanceType: DEFAULT_INSTANCE_TYPE
    ] + config

    if (!params.clusterName) {
        error 'clusterName is required'
    }

    def clusterName = params.clusterName

    // Resolve version if only major.minor is provided (e.g., 4.18 -> 4.18.x)
    def resolvedVersion = params.openshiftVersion
    if (params.openshiftVersion ==~ /^\d+\.\d+$/) {
        echo "Resolving latest patch version for ${params.openshiftVersion}..."
        resolvedVersion = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                rosa list versions --hosted-cp -o json 2>/dev/null | \
                    jq -r '.[] | select(.raw_id | startswith("${params.openshiftVersion}.")) | .raw_id' | \
                    head -1
            """,
            returnStdout: true
        ).trim()

        if (!resolvedVersion) {
            error "Could not find a valid version for ${params.openshiftVersion}"
        }
        echo "Resolved version: ${resolvedVersion}"
    }

    // Get OIDC config ID (required for HCP)
    // Use PMM-specific roles to avoid conflicts with other teams
    def oidcConfigId = params.oidcConfigId
    def operatorRolePrefix = params.operatorRolePrefix ?: PMM_OPERATOR_ROLE_PREFIX

    if (!oidcConfigId) {
        echo "Setting up ROSA HCP resources with prefix: ${operatorRolePrefix}"

        // Step 1: Ensure account roles exist (Installer, Support, Worker)
        // These are shared across all clusters using the same prefix
        def installerRoleExists = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                aws iam get-role --role-name ${operatorRolePrefix}-HCP-ROSA-Installer-Role 2>/dev/null && echo "exists" || echo ""
            """,
            returnStdout: true
        ).trim()

        if (!installerRoleExists.contains('exists')) {
            echo "Account roles not found. Creating account roles with prefix: ${operatorRolePrefix}"
            sh """
                export PATH="\$HOME/.local/bin:\$PATH"
                export AWS_DEFAULT_REGION=${params.region}
                rosa create account-roles --prefix ${operatorRolePrefix} --hosted-cp --mode auto --yes
            """
            echo "Account roles created successfully"
        } else {
            echo "Found existing account roles with prefix: ${operatorRolePrefix}"
        }

        // Step 2: Find or create OIDC config
        // Check if operator roles already exist and extract OIDC config from trust policy
        echo 'Checking for existing operator roles...'
        def existingOidcId = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                # Get trust policy from existing operator role and extract OIDC config ID
                aws iam get-role --role-name ${operatorRolePrefix}-kube-system-kms-provider 2>/dev/null | \
                    jq -r '.Role.AssumeRolePolicyDocument.Statement[0].Principal.Federated // empty' | \
                    grep -oP 'oidc\\.op1\\.openshiftapps\\.com/\\K[a-z0-9]+' || echo ''
            """,
            returnStdout: true
        ).trim()

        if (existingOidcId) {
            echo "Found existing operator roles bound to OIDC config: ${existingOidcId}"
            oidcConfigId = existingOidcId
        } else {
            echo 'No existing operator roles found. Creating new OIDC config...'
            oidcConfigId = sh(
                script: """
                    export PATH="\$HOME/.local/bin:\$PATH"
                    export AWS_DEFAULT_REGION=${params.region}
                    rosa create oidc-config --mode auto --managed --yes -o json | jq -r '.id'
                """,
                returnStdout: true
            ).trim()

            if (!oidcConfigId) {
                error 'Failed to create OIDC config'
            }

            // Create operator roles for this OIDC config
            echo "Creating operator roles for OIDC config: ${oidcConfigId}"
            sh """
                export PATH="\$HOME/.local/bin:\$PATH"
                export AWS_DEFAULT_REGION=${params.region}
                rosa create operator-roles --hosted-cp --prefix ${operatorRolePrefix} \
                    --oidc-config-id ${oidcConfigId} \
                    --installer-role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/${operatorRolePrefix}-HCP-ROSA-Installer-Role \
                    --mode auto --yes
            """
        }

        echo "Using OIDC config ID: ${oidcConfigId}"
    }

    echo "Creating ROSA HCP cluster: ${clusterName}"
    echo "  Region: ${params.region}"
    echo "  OpenShift Version: ${resolvedVersion}"
    echo "  Operator Role Prefix: ${operatorRolePrefix}"
    echo "  Worker Replicas: ${params.replicas}"
    echo "  Instance Type: ${params.instanceType}"
    echo "  OIDC Config ID: ${oidcConfigId}"

    // Get subnet IDs - create VPC if not provided
    def subnetIds = params.subnetIds
    def vpcCidr = params.machineCidr ?: '10.0.0.0/16'
    if (!subnetIds) {
        echo 'Creating VPC network stack for ROSA HCP...'
        def stackName = "${clusterName}-vpc"
        def buildNum = (env.BUILD_NUMBER as int) % 250
        vpcCidr = "10.${buildNum}.0.0/16"

        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${params.region}

            rosa create network rosa-quickstart-default-vpc \\
                --param Region=${params.region} \\
                --param Name=${stackName} \\
                --param AvailabilityZoneCount=1 \\
                --param VpcCidr=${vpcCidr} \\
                --mode=auto \\
                --yes
        """

        // Get subnet IDs from CloudFormation stack
        subnetIds = sh(
            script: """
                aws cloudformation describe-stacks --stack-name ${stackName} --region ${params.region} --query "Stacks[0].Outputs[?OutputKey=='PrivateSubnets' || OutputKey=='PublicSubnets'].OutputValue" --output text | tr '\\t' ','
            """,
            returnStdout: true
        ).trim()

        echo "Created VPC with subnets: ${subnetIds}"
        echo "VPC CIDR: ${vpcCidr}"
    }

    // Build rosa create cluster command with explicit role ARNs
    def installerRoleArn = "arn:aws:iam::${AWS_ACCOUNT_ID}:role/${operatorRolePrefix}-HCP-ROSA-Installer-Role"
    def supportRoleArn = "arn:aws:iam::${AWS_ACCOUNT_ID}:role/${operatorRolePrefix}-HCP-ROSA-Support-Role"
    def workerRoleArn = "arn:aws:iam::${AWS_ACCOUNT_ID}:role/${operatorRolePrefix}-HCP-ROSA-Worker-Role"

    def createCmd = """
        export PATH="\$HOME/.local/bin:\$PATH"

        rosa create cluster \\
            --cluster-name=${clusterName} \\
            --region=${params.region} \\
            --version=${resolvedVersion} \\
            --replicas=${params.replicas} \\
            --compute-machine-type=${params.instanceType} \\
            --oidc-config-id=${oidcConfigId} \\
            --operator-roles-prefix=${operatorRolePrefix} \\
            --role-arn=${installerRoleArn} \\
            --support-role-arn=${supportRoleArn} \\
            --worker-iam-role=${workerRoleArn} \\
            --subnet-ids=${subnetIds} \\
            --machine-cidr=${vpcCidr} \\
            --hosted-cp \\
            --sts \\
            --mode=auto \\
            --yes
    """

    sh createCmd

    echo 'Waiting for cluster to be ready (this takes ~10-15 minutes)...'

    // Poll for cluster to be ready
    def maxAttempts = 60  // 30 minutes with 30s intervals
    def attempt = 0
    def clusterReady = false

    while (!clusterReady && attempt < maxAttempts) {
        attempt++
        def status = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                export AWS_DEFAULT_REGION=${params.region}
                rosa describe cluster -c ${clusterName} -o json | jq -r '.state'
            """,
            returnStdout: true
        ).trim()

        echo "Cluster status (attempt ${attempt}/${maxAttempts}): ${status}"

        if (status == 'ready') {
            clusterReady = true
            echo 'Cluster is ready!'
        } else if (status == 'error' || status == 'uninstalling') {
            error "Cluster creation failed with status: ${status}"
        } else {
            sleep(30)
        }
    }

    if (!clusterReady) {
        error 'Timeout waiting for cluster to be ready'
    }

    // Verify cluster status
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        export AWS_DEFAULT_REGION=${params.region}
        rosa describe cluster --cluster=${clusterName}
    """

    // Get cluster info
    def clusterId = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${params.region}
            rosa describe cluster --cluster=${clusterName} -o json | jq -r '.id'
        """,
        returnStdout: true
    ).trim()

    def apiUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${params.region}
            rosa describe cluster --cluster=${clusterName} -o json | jq -r '.api.url'
        """,
        returnStdout: true
    ).trim()

    def consoleUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${params.region}
            rosa describe cluster --cluster=${clusterName} -o json | jq -r '.console.url'
        """,
        returnStdout: true
    ).trim()

    echo 'ROSA HCP cluster created successfully!'
    echo "  Cluster ID: ${clusterId}"
    echo "  API URL: ${apiUrl}"
    echo "  Console URL: ${consoleUrl}"

    return [
        clusterName: clusterName,
        clusterId: clusterId,
        apiUrl: apiUrl,
        consoleUrl: consoleUrl,
        region: params.region,
        openshiftVersion: resolvedVersion
    ]
}

/**
 * Creates cluster admin user and configures kubectl/oc access.
 *
 * @param config Map containing:
 *   - clusterName: ROSA cluster name (required)
 *   - kubeconfigPath: Path to save kubeconfig (optional, default: './kubeconfig/config')
 *   - region: AWS region (optional, default: 'us-east-2')
 *
 * @return Map containing:
 *   - username: Admin username
 *   - password: Admin password
 *   - kubeconfigPath: Path to kubeconfig file
 *   - apiUrl: API server URL
 */
def configureAccess(Map config) {
    if (!config.clusterName) {
        error 'clusterName is required'
    }

    def kubeconfigPath = config.kubeconfigPath ?: "${env.WORKSPACE}/kubeconfig/config"
    def region = config.region ?: DEFAULT_REGION

    echo "Configuring access to ROSA cluster: ${config.clusterName}"

    // Create cluster admin user
    def adminPassword = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${region}

            # Create admin user (if not exists) - save output to file
            rosa create admin --cluster=${config.clusterName} 2>&1 > /tmp/rosa-admin-output.txt

            # Extract password from output
            grep -oE '[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}' /tmp/rosa-admin-output.txt | head -1
        """,
        returnStdout: true
    ).trim()

    def apiUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${region}
            rosa describe cluster --cluster=${config.clusterName} -o json | jq -r '.api.url'
        """,
        returnStdout: true
    ).trim()

    // Wait for admin user to be ready (ROSA needs ~90 seconds to propagate credentials)
    echo 'Waiting for admin user to be ready (up to 5 minutes)...'
    sleep(time: 90, unit: 'SECONDS')

    // Login with oc and save kubeconfig
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        mkdir -p \$(dirname ${kubeconfigPath})

        # Login with cluster-admin credentials
        oc login ${apiUrl} \\
            --username=cluster-admin \\
            --password='${adminPassword}' \\
            --insecure-skip-tls-verify=true

        # Save kubeconfig
        cp ~/.kube/config ${kubeconfigPath} || true

        # Verify access
        oc whoami
        oc get nodes
    """

    echo 'Cluster access configured successfully'

    return [
        username: 'cluster-admin',
        password: adminPassword,
        kubeconfigPath: kubeconfigPath,
        apiUrl: apiUrl
    ]
}

/**
 * Deletes a ROSA cluster and cleans up associated resources.
 *
 * @param config Map containing:
 *   - clusterName: ROSA cluster name to delete (required)
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - deleteOidc: Whether to delete OIDC provider (optional, default: false)
 *   - deleteOperatorRoles: Whether to delete operator roles (optional, default: true)
 *   - deleteVpc: Whether to delete VPC CloudFormation stack (optional, default: true)
 *
 * @return Map containing:
 *   - deleted: boolean indicating success
 *   - clusterName: Name of deleted cluster
 *   - clusterId: ID of deleted cluster
 */
def deleteCluster(Map config) {
    if (!config.clusterName) {
        error 'clusterName is required'
    }

    def params = [
        deleteOidc: false,  // Keep OIDC config for other clusters by default
        deleteOperatorRoles: true,
        deleteVpc: true
    ] + config

    def region = params.region ?: DEFAULT_REGION
    echo "Deleting ROSA cluster: ${params.clusterName}"

    // Get cluster ID before deletion (needed for OIDC/operator-roles cleanup)
    def clusterId = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${region}
            rosa describe cluster --cluster=${params.clusterName} -o json 2>/dev/null | jq -r '.id' || echo ''
        """,
        returnStdout: true
    ).trim()

    // Delete the cluster
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        export AWS_DEFAULT_REGION=${region}

        rosa delete cluster \\
            --cluster=${params.clusterName} \\
            --yes
    """

    // Clean up OIDC provider
    if (params.deleteOidc && clusterId) {
        echo "Cleaning up OIDC provider for cluster ID: ${clusterId}"
        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${region}
            rosa delete oidc-provider -c ${clusterId} --mode auto --yes || true
        """
    }

    // Clean up operator roles
    if (params.deleteOperatorRoles && clusterId) {
        echo "Cleaning up operator roles for cluster ID: ${clusterId}"
        sh """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${region}
            rosa delete operator-roles -c ${clusterId} --mode auto --yes || true
        """
    }

    // Clean up VPC CloudFormation stack if it exists
    if (params.deleteVpc) {
        def vpcStackName = "${params.clusterName}-vpc"
        echo "Checking for VPC stack: ${vpcStackName}"
        sh """
            aws cloudformation delete-stack --stack-name ${vpcStackName} --region ${region} || true
            echo "VPC stack deletion initiated (if it existed)"
        """
    }

    echo "ROSA cluster ${params.clusterName} deleted successfully"

    return [
        deleted: true,
        clusterName: params.clusterName,
        clusterId: clusterId
    ]
}

/**
 * Lists all ROSA clusters, optionally filtered by prefix.
 *
 * @param config Map containing:
 *   - region: AWS region to filter (optional)
 *   - prefix: Cluster name prefix to filter (optional)
 *
 * @return List of cluster info maps
 */
def listClusters(Map config = [:]) {
    echo 'Listing ROSA clusters...'

    def region = config.region ?: DEFAULT_REGION
    def prefix = config.prefix

    // First verify ROSA CLI can reach the API
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        export AWS_DEFAULT_REGION=${region}
        echo "Checking ROSA CLI connectivity (region: ${region})..."
        rosa whoami || echo "WARNING: rosa whoami failed"
    """

    def clustersJson = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${region}
            rosa list clusters -o json 2>&1 || echo '[]'
        """,
        returnStdout: true
    ).trim()

    // Handle case where stderr was mixed in with output
    def jsonStart = clustersJson.indexOf('[')
    if (jsonStart > 0) {
        echo "Debug output before JSON: ${clustersJson.substring(0, jsonStart)}"
        clustersJson = clustersJson.substring(jsonStart)
    }

    def jsonSlurper = new JsonSlurper()
    def allClusters = jsonSlurper.parseText(clustersJson)

    // Filter by prefix if specified
    def filteredClusters = allClusters
    if (prefix) {
        filteredClusters = allClusters.findAll { cluster ->
            cluster.name?.startsWith(prefix)
        }
    }

    // Filter by region if specified
    if (config.region) {
        filteredClusters = filteredClusters.findAll { cluster ->
            cluster.region?.id == config.region
        }
    }

    def result = filteredClusters.collect { cluster ->
        [
            name: cluster.name,
            id: cluster.id,
            state: cluster.state,
            region: cluster.region?.id,
            version: cluster.openshift_version,
            createdAt: cluster.creation_timestamp
        ]
    }

    // Sort by createdAt (newest first)
    result.sort { a, b -> b.createdAt <=> a.createdAt }

    echo "Found ${result.size()} ROSA cluster(s)"
    return result
}

// ============================================================================
// Utility Functions
// ============================================================================

/**
 * Generates a random password.
 *
 * @param length Password length (optional, default: 16)
 * @return String Random password
 */
def generatePassword(int length = 16) {
    def chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    def random = new Random()
    return (1..length).collect { chars[random.nextInt(chars.length())] }.join('')
}

/**
 * Checks if the number of existing clusters exceeds the limit.
 *
 * @param config Map containing:
 *   - maxClusters: Maximum allowed clusters (optional, default: 10)
 *   - prefix: Cluster name prefix to filter (optional)
 *   - region: AWS region (optional)
 *
 * @return boolean true if under limit, false if at/over limit
 */
def checkClusterLimit(Map config = [:]) {
    def maxClusters = config.maxClusters ?: DEFAULT_MAX_CLUSTERS

    def clusters = listClusters([prefix: config.prefix, region: config.region])
    def activeCount = clusters.findAll { it.state != 'uninstalling' }.size()

    if (activeCount >= maxClusters) {
        echo "ERROR: Maximum cluster limit (${maxClusters}) reached. Active clusters: ${activeCount}"
        return false
    }

    echo "Cluster limit check passed: ${activeCount}/${maxClusters}"
    return true
}

/**
 * Gets cluster age in hours.
 *
 * @param createdAt ISO timestamp of cluster creation
 * @return int Age in hours
 */
def getClusterAgeHours(String createdAt) {
    if (!createdAt) {
        return 0
    }

    try {
        def created = Date.parse("yyyy-MM-dd'T'HH:mm:ss", createdAt.replaceAll('Z$', '').replaceAll('\\.\\d+', ''))
        def now = new Date()
        def diffMs = now.time - created.time
        return (diffMs / (1000 * 60 * 60)).intValue()
    } catch (Exception e) {
        echo "WARNING: Could not parse date: ${createdAt}"
        return 0
    }
}

/**
 * Formats cluster list for display.
 *
 * @param clusters List of cluster maps
 * @param title Optional title for the output
 * @return String Formatted table
 */
def formatClustersSummary(List clusters, String title = 'ROSA CLUSTERS') {
    def output = new StringBuilder()
    output.append("=" * 80)
    output.append("\n${title}\n")
    output.append("=" * 80)
    output.append("\n")

    if (clusters.isEmpty()) {
        output.append("No clusters found.\n")
    } else {
        output.append(String.format("%-30s %-12s %-10s %-8s %s\n",
            'NAME', 'STATE', 'VERSION', 'AGE(h)', 'REGION'))
        output.append("-" * 80)
        output.append("\n")

        clusters.each { cluster ->
            def age = getClusterAgeHours(cluster.createdAt)
            output.append(String.format("%-30s %-12s %-10s %-8s %s\n",
                cluster.name ?: 'N/A',
                cluster.state ?: 'N/A',
                cluster.version ?: 'N/A',
                age.toString(),
                cluster.region ?: 'N/A'))
        }
    }

    output.append("=" * 80)
    return output.toString()
}

// ============================================================================
// ECR Pull-Through Cache
// ============================================================================

/**
 * Configures ECR pull-through cache access on ROSA cluster.
 *
 * ECR pull-through cache proxies Docker Hub images through AWS ECR,
 * avoiding Docker Hub rate limits.
 *
 * @param config Map containing:
 *   - region: AWS region (optional, default: 'us-east-2')
 *
 * @return Map containing:
 *   - ecrRegistry: ECR registry URL
 *   - ecrPrefix: Full ECR prefix for image paths
 */
def configureEcrPullThrough(Map config = [:]) {
    def region = config.region ?: ECR_REGION

    echo 'Configuring ECR pull-through cache access...'

    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        ECR_TOKEN=\$(aws ecr get-login-password --region ${region})
        ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com"

        oc get secret/pull-secret -n openshift-config \\
            --template='{{index .data ".dockerconfigjson" | base64decode}}' > /tmp/pull-secret.json

        oc registry login --registry="\${ECR_REGISTRY}" \\
            --auth-basic="AWS:\${ECR_TOKEN}" \\
            --to=/tmp/pull-secret.json

        oc set data secret/pull-secret -n openshift-config \\
            --from-file=.dockerconfigjson=/tmp/pull-secret.json

        rm -f /tmp/pull-secret.json
    """

    return [
        ecrRegistry: "${AWS_ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com",
        ecrPrefix: ECR_PREFIX
    ]
}

// ============================================================================
// PMM Deployment (Standalone)
// ============================================================================

/**
 * Installs standalone PMM on ROSA cluster.
 *
 * @param config Map containing:
 *   - namespace: Kubernetes namespace (optional, default: 'pmm')
 *   - imageRepository: PMM image repository (optional)
 *   - imageTag: PMM image tag (optional, default: 'latest')
 *   - adminPassword: PMM admin password (optional, auto-generated)
 *   - storageClass: StorageClass (optional, default: 'gp3-csi')
 *   - storageSize: PVC size (optional, default: '10Gi')
 *   - useEcr: Use ECR pull-through cache (optional, default: true)
 *   - chartVersion: Helm chart version (optional)
 *
 * @return Map containing deployment info
 */
def installPmmStandalone(Map config = [:]) {
    def params = [
        namespace: 'pmm',
        imageTag: 'latest',
        storageClass: 'gp3-csi',
        storageSize: '10Gi',
        useEcr: true
    ] + config

    echo "Installing standalone PMM on ROSA cluster"
    echo "  Namespace: ${params.namespace}"
    echo "  Image Tag: ${params.imageTag}"

    def adminPassword = params.adminPassword ?: generatePassword()

    // Create namespace
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        oc create namespace ${params.namespace} || true
    """

    // Configure ECR if enabled
    if (params.useEcr) {
        configureEcrPullThrough([region: params.region ?: ECR_REGION])
    }

    // Install Helm if needed
    sh '''
        export PATH="$HOME/.local/bin:$PATH"
        if ! command -v helm &>/dev/null; then
            curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
        fi
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo update
    '''

    // Build Helm args
    def helmArgs = [
        "--namespace ${params.namespace}",
        "--set secret.pmm_password='${adminPassword}'",
        "--set storage.storageClassName=${params.storageClass}",
        "--set storage.size=${params.storageSize}"
    ]

    if (params.imageRepository) {
        def repo = params.useEcr ? "${ECR_PREFIX}/${params.imageRepository}" : params.imageRepository
        helmArgs.add("--set image.repository=${repo}")
    }
    if (params.imageTag) {
        helmArgs.add("--set image.tag=${params.imageTag}")
    }
    if (params.chartVersion) {
        helmArgs.add("--version ${params.chartVersion}")
    }

    helmArgs.add('--wait --timeout 10m')

    echo 'Installing PMM via Helm...'
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        helm upgrade --install pmm percona/pmm ${helmArgs.join(' ')}
    """

    // Verify
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        oc get pods -n ${params.namespace}
        oc get svc -n ${params.namespace}
    """

    echo 'PMM installed successfully'

    return [
        namespace: params.namespace,
        adminPassword: adminPassword,
        serviceName: 'pmm'
    ]
}

return this
