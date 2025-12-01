/**
 * ROSA HCP (Red Hat OpenShift Service on AWS with Hosted Control Planes) operations library
 * for PMM HA testing.
 *
 * This library provides ROSA cluster lifecycle management optimized for PMM High Availability
 * testing. It uses ROSA HCP for fast (~10-15 min) cluster provisioning.
 *
 * Prerequisites:
 * - rosa CLI installed on Jenkins agents
 * - Red Hat offline token stored as Jenkins credential
 * - AWS credentials with ROSA permissions
 *
 * @since 1.0.0
 *
 * @example Library Usage (for QA team):
 * // Import the library
 * library changelog: false, identifier: 'lib@master', retriever: modernSCM([
 *     $class: 'GitSCMSource',
 *     remote: 'https://github.com/Percona-Lab/jenkins-pipelines'
 * ])
 *
 * // Create ROSA HCP cluster
 * pmmHaRosa.createCluster([
 *     clusterName: 'pmm-ha-rosa-1',
 *     region: 'us-east-2',
 *     replicas: 3
 * ])
 *
 * // Deploy PMM HA to existing ROSA cluster
 * pmmHaRosa.installPmm([
 *     namespace: 'pmm',
 *     chartBranch: 'PMM-14420',
 *     imageTag: '3.4.0'
 * ])
 *
 * // Create Route with Route53 DNS
 * pmmHaRosa.createRoute([
 *     namespace: 'pmm',
 *     domain: 'pmm-ha-rosa-1.cd.percona.com',
 *     r53ZoneName: 'cd.percona.com'
 * ])
 */

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.Field

// ============================================================================
// Configuration Constants
// ============================================================================

@Field static final String CLUSTER_PREFIX = 'pmm-ha-rosa-'
@Field static final String DEFAULT_REGION = 'us-east-2'
@Field static final String DEFAULT_OPENSHIFT_VERSION = '4.16'
@Field static final String DEFAULT_INSTANCE_TYPE = 'm5.xlarge'
@Field static final int DEFAULT_REPLICAS = 3
@Field static final int MAX_CLUSTERS = 5

// ============================================================================
// Tool Installation
// ============================================================================

/**
 * Installs ROSA CLI if not already available.
 *
 * Downloads and installs the latest ROSA CLI from Red Hat's official mirror.
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
 *   - version: OpenShift version (optional, default: '4.16')
 *
 * @return String The installed oc version
 */
def installOcCli(Map config = [:]) {
    def version = config.version ?: '4.16'

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

    def region = config.region ?: 'us-east-2'

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
 *   - clusterName: Name for the cluster (required, will be prefixed with 'pmm-ha-rosa-')
 *   - region: AWS region (optional, default: 'us-east-2')
 *   - openshiftVersion: OpenShift version (optional, default: '4.16')
 *   - replicas: Number of worker nodes (optional, default: 3)
 *   - instanceType: EC2 instance type (optional, default: 'm5.xlarge')
 *   - subnetIds: Comma-separated subnet IDs for existing VPC (optional)
 *   - token: Red Hat offline token (required if not already logged in)
 *
 * @return Map containing cluster information:
 *   - clusterName: Full cluster name
 *   - clusterId: ROSA cluster ID
 *   - apiUrl: API server URL
 *   - consoleUrl: OpenShift console URL
 *   - region: AWS region
 *
 * @example
 * def cluster = pmmHaRosa.createCluster([
 *     clusterName: 'test-1',
 *     region: 'us-east-2',
 *     replicas: 3
 * ])
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

    // Ensure cluster name has the correct prefix
    def fullClusterName = params.clusterName.startsWith(CLUSTER_PREFIX) ?
        params.clusterName : "${CLUSTER_PREFIX}${params.clusterName}"

    // Resolve version if only major.minor is provided (e.g., 4.16 -> 4.16.52)
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
    def oidcConfigId = params.oidcConfigId
    if (!oidcConfigId) {
        echo 'Looking up existing OIDC config...'
        oidcConfigId = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                export AWS_DEFAULT_REGION=${params.region}
                rosa list oidc-config -o json 2>/dev/null | jq -r '.[0].id // empty'
            """,
            returnStdout: true
        ).trim()

        if (!oidcConfigId) {
            echo 'No OIDC config found. Creating one...'
            oidcConfigId = sh(
                script: """
                    export PATH="\$HOME/.local/bin:\$PATH"
                    export AWS_DEFAULT_REGION=${params.region}
                    rosa create oidc-config --mode=auto --yes -o json | jq -r '.id'
                """,
                returnStdout: true
            ).trim()
        }
        echo "Using OIDC config ID: ${oidcConfigId}"
    }

    echo "Creating ROSA HCP cluster: ${fullClusterName}"
    echo "  Region: ${params.region}"
    echo "  OpenShift Version: ${resolvedVersion}"
    echo "  Worker Replicas: ${params.replicas}"
    echo "  Instance Type: ${params.instanceType}"
    echo "  OIDC Config ID: ${oidcConfigId}"

    // Get subnet IDs - create VPC if not provided
    def subnetIds = params.subnetIds
    def vpcCidr = params.machineCidr ?: '10.0.0.0/16'  // Default CIDR
    if (!subnetIds) {
        echo 'Creating VPC network stack for ROSA HCP...'
        def stackName = "${fullClusterName}-vpc"
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

    // Build rosa create cluster command
    def createCmd = """
        export PATH="\$HOME/.local/bin:\$PATH"

        rosa create cluster \\
            --cluster-name=${fullClusterName} \\
            --region=${params.region} \\
            --version=${resolvedVersion} \\
            --replicas=${params.replicas} \\
            --compute-machine-type=${params.instanceType} \\
            --oidc-config-id=${oidcConfigId} \\
            --subnet-ids=${subnetIds} \\
            --machine-cidr=${vpcCidr} \\
            --hosted-cp \\
            --sts \\
            --mode=auto \\
            --yes
    """

    sh createCmd

    echo 'Waiting for cluster to be ready (this takes ~10-15 minutes)...'

    // Poll for cluster to be ready (rosa doesn't have a wait command)
    def maxAttempts = 60  // 30 minutes with 30s intervals
    def attempt = 0
    def clusterReady = false

    while (!clusterReady && attempt < maxAttempts) {
        attempt++
        def status = sh(
            script: """
                export PATH="\$HOME/.local/bin:\$PATH"
                export AWS_DEFAULT_REGION=${params.region}
                rosa describe cluster -c ${fullClusterName} -o json | jq -r '.state'
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
        rosa describe cluster --cluster=${fullClusterName}
    """

    // Get cluster info
    def clusterId = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${params.region}
            rosa describe cluster --cluster=${fullClusterName} -o json | jq -r '.id'
        """,
        returnStdout: true
    ).trim()

    def apiUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${params.region}
            rosa describe cluster --cluster=${fullClusterName} -o json | jq -r '.api.url'
        """,
        returnStdout: true
    ).trim()

    def consoleUrl = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            export AWS_DEFAULT_REGION=${params.region}
            rosa describe cluster --cluster=${fullClusterName} -o json | jq -r '.console.url'
        """,
        returnStdout: true
    ).trim()

    echo 'ROSA HCP cluster created successfully!'
    echo "  Cluster ID: ${clusterId}"
    echo "  API URL: ${apiUrl}"
    echo "  Console URL: ${consoleUrl}"

    return [
        clusterName: fullClusterName,
        clusterId: clusterId,
        apiUrl: apiUrl,
        consoleUrl: consoleUrl,
        region: params.region
    ]
}

/**
 * Creates cluster admin user and configures kubectl/oc access.
 *
 * @param config Map containing:
 *   - clusterName: ROSA cluster name (required)
 *   - kubeconfigPath: Path to save kubeconfig (optional, default: './kubeconfig')
 *
 * @return Map containing:
 *   - username: Admin username
 *   - password: Admin password
 *   - kubeconfigPath: Path to kubeconfig file
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

            # Create admin user (if not exists) - save output to file, suppress stdout
            rosa create admin --cluster=${config.clusterName} 2>&1 > /tmp/rosa-admin-output.txt

            # Extract password from output
            # ROSA outputs a line like: oc login <url> --username cluster-admin --password XXXXX-XXXXX-XXXXX-XXXXX
            # The password format is: 5 alphanumeric chars, dash, repeated 4 times
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
 *   - deleteOidc: Whether to delete OIDC provider (optional, default: true)
 *   - deleteOperatorRoles: Whether to delete operator roles (optional, default: true)
 *
 * @return Map containing:
 *   - deleted: boolean indicating success
 *   - clusterName: Name of deleted cluster
 */
def deleteCluster(Map config) {
    if (!config.clusterName) {
        error 'clusterName is required'
    }

    def params = [
        deleteOidc: true,
        deleteOperatorRoles: true
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
            --yes \\
            --watch
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
    def vpcStackName = "${params.clusterName}-vpc"
    echo "Checking for VPC stack: ${vpcStackName}"
    sh """
        aws cloudformation delete-stack --stack-name ${vpcStackName} --region ${region} || true
        echo "VPC stack deletion initiated (if it existed)"
    """

    echo "ROSA cluster ${params.clusterName} deleted successfully"

    return [
        deleted: true,
        clusterName: params.clusterName,
        clusterId: clusterId
    ]
}

/**
 * Lists all ROSA clusters matching the PMM HA prefix.
 *
 * @param config Map containing:
 *   - region: AWS region to filter (optional)
 *
 * @return List of cluster info maps
 */
def listClusters(Map config = [:]) {
    echo 'Listing ROSA clusters...'

    def clustersJson = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            rosa list clusters -o json 2>/dev/null || echo '[]'
        """,
        returnStdout: true
    ).trim()

    def jsonSlurper = new JsonSlurper()
    def allClusters = jsonSlurper.parseText(clustersJson)

    // Filter to only PMM HA clusters
    def pmmClusters = allClusters.findAll { cluster ->
        cluster.name?.startsWith(CLUSTER_PREFIX)
    }

    // Filter by region if specified
    if (config.region) {
        pmmClusters = pmmClusters.findAll { cluster ->
            cluster.region?.id == config.region
        }
    }

    def result = pmmClusters.collect { cluster ->
        [
            name: cluster.name,
            id: cluster.id,
            state: cluster.state,
            region: cluster.region?.id,
            version: cluster.openshift_version,
            createdAt: cluster.creation_timestamp
        ]
    }

    echo "Found ${result.size()} PMM HA ROSA cluster(s)"
    return result
}

// ============================================================================
// PMM HA Installation
// ============================================================================

/**
 * Installs PMM HA on the ROSA cluster.
 *
 * Deploys PMM in High Availability mode using the pmm-ha Helm chart.
 * This includes:
 * - Creating namespace with OpenShift SCC permissions
 * - Installing pmm-ha-dependencies (PostgreSQL, ClickHouse, VictoriaMetrics)
 * - Installing pmm-ha
 *
 * @param config Map containing:
 *   - namespace: Kubernetes namespace for PMM (optional, default: 'pmm')
 *   - chartBranch: percona-helm-charts branch (optional, default: 'main')
 *   - imageTag: PMM server image tag (optional, default: 'dev-latest')
 *   - imageRepository: PMM image repository (optional, default: 'perconalab/pmm-server')
 *   - adminPassword: PMM admin password (optional, auto-generated if not provided)
 *   - storageClass: StorageClass to use (optional, default: 'gp3-csi')
 *   - dependenciesStorageSize: Storage size for dependencies PVCs (optional, default: '10Gi')
 *   - pmmStorageSize: Storage size for PMM PVC (optional, default: '10Gi')
 *
 * @return Map containing:
 *   - namespace: Namespace where PMM is deployed
 *   - adminPassword: PMM admin password
 *   - serviceName: PMM service name
 */
def installPmm(Map config = [:]) {
    def params = [
        namespace: 'pmm',
        chartBranch: 'pmmha-v3',  // Branch with PMM HA charts
        imageTag: 'dev-latest',
        imageRepository: 'perconalab/pmm-server',
        storageClass: 'gp3-csi',
        dependenciesStorageSize: '10Gi',
        pmmStorageSize: '10Gi'
    ] + config

    echo 'Installing PMM HA on ROSA cluster'
    echo "  Namespace: ${params.namespace}"
    echo "  Chart Branch: ${params.chartBranch}"
    echo "  Image: ${params.imageRepository}:${params.imageTag}"

    // Generate admin password if not provided
    def adminPassword = params.adminPassword ?: generatePassword()

    // Create namespace and configure SCC
    // Note: ROSA HCP doesn't allow modifying default SCCs, so we create a custom one
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        # Create namespace
        oc create namespace ${params.namespace} || true

        # Create custom SCC for PMM HA on ROSA HCP
        # ROSA HCP blocks modifications to default SCCs (anyuid, restricted, etc.)
        # We create a custom SCC with the required service accounts in the users list
        cat <<'EOF' | oc apply -f -
apiVersion: security.openshift.io/v1
kind: SecurityContextConstraints
metadata:
  name: pmm-anyuid
allowHostDirVolumePlugin: false
allowHostIPC: false
allowHostNetwork: false
allowHostPID: false
allowHostPorts: false
allowPrivilegeEscalation: true
allowPrivilegedContainer: false
allowedCapabilities: null
defaultAddCapabilities: null
fsGroup:
  type: RunAsAny
priority: 10
readOnlyRootFilesystem: false
requiredDropCapabilities:
  - MKNOD
runAsUser:
  type: RunAsAny
seLinuxContext:
  type: MustRunAs
supplementalGroups:
  type: RunAsAny
users:
  - system:serviceaccount:${params.namespace}:default
  - system:serviceaccount:${params.namespace}:pmm-service-account
  - system:serviceaccount:${params.namespace}:pmm-ha-haproxy
  - system:serviceaccount:${params.namespace}:pmm-ha-pg-db
  - system:serviceaccount:${params.namespace}:pmm-ha-pmmdb
  - system:serviceaccount:${params.namespace}:pmm-ha-vmagent
  - system:serviceaccount:${params.namespace}:pmm-ha-secret-generator
  - system:serviceaccount:${params.namespace}:pmm-ha-dependencies-altinity-clickhouse-operator
  - system:serviceaccount:${params.namespace}:pmm-ha-dependencies-pg-operator
  - system:serviceaccount:${params.namespace}:pmm-ha-dependencies-victoria-metrics-operator
volumes:
  - configMap
  - csi
  - downwardAPI
  - emptyDir
  - ephemeral
  - persistentVolumeClaim
  - projected
  - secret
EOF

        echo "Custom SCC 'pmm-anyuid' created for PMM HA workloads"
    """

    // Pre-create pmm-secret with all required passwords
    // This is done before helm install so the chart uses existing secret
    // Keys are based on charts/pmm-ha/templates/secret.yaml and vmauth.yaml requirements
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        # Delete existing secret if any
        oc delete secret pmm-secret -n ${params.namespace} 2>/dev/null || true

        # Create pmm-secret with all required keys
        # These match the keys expected by pmm-ha helm chart templates:
        # - secret.yaml: PMM_ADMIN_PASSWORD, PMM_CLICKHOUSE_USER, PMM_CLICKHOUSE_PASSWORD,
        #                VMAGENT_remoteWrite_basicAuth_username, VMAGENT_remoteWrite_basicAuth_password,
        #                PG_PASSWORD, GF_PASSWORD
        # - vmauth.yaml: VMAGENT_remoteWrite_basicAuth_username, VMAGENT_remoteWrite_basicAuth_password (uses b64dec)
        # - clickhouse-cluster.yaml: PMM_CLICKHOUSE_USER (uses b64dec)
        oc create secret generic pmm-secret -n ${params.namespace} \\
            --from-literal=PMM_ADMIN_PASSWORD='${adminPassword}' \\
            --from-literal=PMM_CLICKHOUSE_USER='clickhouse_pmm' \\
            --from-literal=PMM_CLICKHOUSE_PASSWORD='${adminPassword}' \\
            --from-literal=VMAGENT_remoteWrite_basicAuth_username='victoriametrics_pmm' \\
            --from-literal=VMAGENT_remoteWrite_basicAuth_password='${adminPassword}' \\
            --from-literal=PG_PASSWORD='${adminPassword}' \\
            --from-literal=GF_PASSWORD='${adminPassword}'

        echo "Pre-created pmm-secret with all required keys"
    """

    // Add Docker Hub credentials to the global OpenShift pull secret
    // This is the proper way to avoid rate limiting cluster-wide in OpenShift
    // Reference: https://access.redhat.com/solutions/6159832
    if (config.dockerHubUser && config.dockerHubPassword) {
        sh """
            export PATH="\$HOME/.local/bin:\$PATH"

            # Extract existing global pull secret
            oc get secret/pull-secret -n openshift-config \\
                --template='{{index .data ".dockerconfigjson" | base64decode}}' > /tmp/pull-secret.json

            # Add Docker Hub credentials using oc registry login
            oc registry login --registry="docker.io" \\
                --auth-basic="${config.dockerHubUser}:${config.dockerHubPassword}" \\
                --to=/tmp/pull-secret.json

            # Update the global pull secret
            oc set data secret/pull-secret -n openshift-config \\
                --from-file=.dockerconfigjson=/tmp/pull-secret.json

            # Clean up
            rm -f /tmp/pull-secret.json

            echo "Docker Hub credentials added to global OpenShift pull secret"
        """
    } else {
        echo 'WARNING: Docker Hub credentials not provided. Image pulls may be rate-limited.'
    }

    // Clone percona-helm-charts
    def chartsDir = "${env.WORKSPACE}/percona-helm-charts"
    sh """
        rm -rf ${chartsDir}
        git clone --depth 1 --branch ${params.chartBranch} \\
            https://github.com/percona/percona-helm-charts.git ${chartsDir}
    """

    // Install Helm if not available and add required repos
    sh '''
        export PATH="$HOME/.local/bin:$PATH"
        if ! command -v helm &>/dev/null; then
            echo "Installing Helm..."
            curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
        fi
        helm version

        # Add required helm repos for PMM HA dependencies
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo add vm https://victoriametrics.github.io/helm-charts/ || true
        helm repo add altinity https://docs.altinity.com/helm-charts/ || true
        helm repo add haproxytech https://haproxytech.github.io/helm-charts/ || true
        helm repo update
    '''

    // Update chart dependencies (download sub-charts)
    echo 'Updating chart dependencies...'
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        helm dependency update ${chartsDir}/charts/pmm-ha-dependencies
        helm dependency update ${chartsDir}/charts/pmm-ha
    """

    // Install pmm-ha-dependencies
    echo 'Installing PMM HA dependencies...'
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        helm upgrade --install pmm-ha-dependencies ${chartsDir}/charts/pmm-ha-dependencies \\
            --namespace ${params.namespace} \\
            --set global.storageClass=${params.storageClass} \\
            --set postgresql.primary.persistence.size=${params.dependenciesStorageSize} \\
            --set clickhouse.persistence.size=${params.dependenciesStorageSize} \\
            --set victoriametrics.server.persistentVolume.size=${params.dependenciesStorageSize} \\
            --wait --timeout 15m
    """

    // Install pmm-ha
    echo 'Installing PMM HA...'
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        helm upgrade --install pmm-ha ${chartsDir}/charts/pmm-ha \\
            --namespace ${params.namespace} \\
            --set image.repository=${params.imageRepository} \\
            --set image.tag=${params.imageTag} \\
            --set secret.create=false \\
            --set secret.name=pmm-secret \\
            --set persistence.size=${params.pmmStorageSize} \\
            --set persistence.storageClassName=${params.storageClass} \\
            --wait --timeout 15m
    """

    // Verify deployment
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        echo "PMM HA pods:"
        oc get pods -n ${params.namespace}

        echo ""
        echo "PMM HA services:"
        oc get svc -n ${params.namespace}
    """

    echo 'PMM HA installed successfully'

    return [
        namespace: params.namespace,
        adminPassword: adminPassword,
        serviceName: 'pmm-ha'
    ]
}

/**
 * Creates an OpenShift Route for external PMM access and configures Route53 DNS.
 *
 * @param config Map containing:
 *   - namespace: PMM namespace (required)
 *   - domain: Custom domain for PMM (optional, will be generated if not provided)
 *   - clusterName: ROSA cluster name (required for domain generation)
 *   - r53ZoneName: Route53 zone name (optional, default: 'cd.percona.com')
 *   - r53ZoneId: Route53 hosted zone ID (optional, will be looked up from zone name)
 *   - serviceName: PMM service name (optional, default: 'pmm-ha')
 *
 * @return Map containing:
 *   - routeName: Name of created route
 *   - routeHost: Route hostname
 *   - url: Full HTTPS URL for PMM
 *   - dnsRecord: Route53 DNS record name
 */
def createRoute(Map config) {
    if (!config.namespace) {
        error 'namespace is required'
    }

    def params = [
        r53ZoneName: 'cd.percona.com',
        serviceName: 'pmm-ha'
    ] + config

    // Generate domain if not provided
    def domain = params.domain
    if (!domain && params.clusterName) {
        domain = "${params.clusterName}.${params.r53ZoneName}"
    }

    if (!domain) {
        error 'Either domain or clusterName must be provided'
    }

    echo 'Creating OpenShift Route for PMM HA'
    echo "  Domain: ${domain}"
    echo "  Namespace: ${params.namespace}"

    // Create passthrough route (TLS termination at PMM)
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        # Delete existing route if any
        oc delete route pmm-https -n ${params.namespace} || true

        # Create passthrough route
        oc create route passthrough pmm-https \\
            --service=${params.serviceName} \\
            --port=https \\
            --hostname=${domain} \\
            -n ${params.namespace}

        # Verify route
        oc get route pmm-https -n ${params.namespace}
    """

    // Get the router's canonical hostname for Route53 CNAME
    def routerHost = sh(
        script: """
            export PATH="\$HOME/.local/bin:\$PATH"
            oc get route pmm-https -n ${params.namespace} -o jsonpath='{.status.ingress[0].routerCanonicalHostname}' || \\
            oc get service -n openshift-ingress router-default -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
        """,
        returnStdout: true
    ).trim()

    if (routerHost) {
        echo 'Creating Route53 DNS record...'
        echo "  Domain: ${domain}"
        echo "  Target: ${routerHost}"

        // Create Route53 record
        createRoute53Record([
            domain: domain,
            target: routerHost,
            zoneName: params.r53ZoneName,
            zoneId: params.r53ZoneId
        ])
    } else {
        echo 'WARNING: Could not get router hostname for Route53 setup'
    }

    def pmmUrl = "https://${domain}"
    echo "PMM HA accessible at: ${pmmUrl}"

    return [
        routeName: 'pmm-https',
        routeHost: domain,
        url: pmmUrl,
        dnsRecord: domain,
        routerHost: routerHost
    ]
}

/**
 * Deletes Route53 DNS record for a cluster.
 *
 * @param config Map containing:
 *   - domain: DNS record to delete (required)
 *   - zoneName: Route53 zone name (optional, default: 'cd.percona.com')
 *   - zoneId: Route53 hosted zone ID (optional)
 */
def deleteRoute53Record(Map config) {
    if (!config.domain) {
        error 'domain is required'
    }

    def params = [
        zoneName: 'cd.percona.com'
    ] + config

    echo "Deleting Route53 record: ${params.domain}"

    // Get zone ID if not provided
    def zoneId = params.zoneId
    if (!zoneId) {
        zoneId = sh(
            script: """
                aws route53 list-hosted-zones-by-name \\
                    --dns-name ${params.zoneName} \\
                    --query "HostedZones[0].Id" \\
                    --output text | sed 's|/hostedzone/||'
            """,
            returnStdout: true
        ).trim()
    }

    if (!zoneId || zoneId == 'None') {
        echo "WARNING: Could not find Route53 zone for ${params.zoneName}"
        return
    }

    // Get existing record
    def existingRecord = sh(
        script: """
            aws route53 list-resource-record-sets \\
                --hosted-zone-id ${zoneId} \\
                --query "ResourceRecordSets[?Name=='${params.domain}.']" \\
                --output json
        """,
        returnStdout: true
    ).trim()

    if (existingRecord == '[]') {
        echo "No existing Route53 record found for ${params.domain}"
        return
    }

    // Delete the record
    sh """
        # Get record details
        RECORD_TYPE=\$(echo '${existingRecord}' | jq -r '.[0].Type')
        RECORD_VALUE=\$(echo '${existingRecord}' | jq -r '.[0].ResourceRecords[0].Value // .[0].AliasTarget.DNSName')

        # Create change batch for deletion
        cat > /tmp/route53-delete.json <<EOF
{
    "Changes": [{
        "Action": "DELETE",
        "ResourceRecordSet": \$(echo '${existingRecord}' | jq '.[0]')
    }]
}
EOF

        aws route53 change-resource-record-sets \\
            --hosted-zone-id ${zoneId} \\
            --change-batch file:///tmp/route53-delete.json || true

        rm -f /tmp/route53-delete.json
    """

    echo "Route53 record ${params.domain} deleted"
}

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Creates a Route53 DNS record.
 *
 * @param config Map containing:
 *   - domain: DNS record name (required)
 *   - target: Target hostname for CNAME (required)
 *   - zoneName: Route53 zone name (optional, default: 'cd.percona.com')
 *   - zoneId: Route53 hosted zone ID (optional)
 *   - ttl: TTL in seconds (optional, default: 300)
 */
def createRoute53Record(Map config) {
    if (!config.domain || !config.target) {
        error 'domain and target are required'
    }

    def params = [
        zoneName: 'cd.percona.com',
        ttl: 300
    ] + config

    // Get zone ID if not provided
    def zoneId = params.zoneId
    if (!zoneId) {
        zoneId = sh(
            script: """
                aws route53 list-hosted-zones-by-name \\
                    --dns-name ${params.zoneName} \\
                    --query "HostedZones[0].Id" \\
                    --output text | sed 's|/hostedzone/||'
            """,
            returnStdout: true
        ).trim()
    }

    if (!zoneId || zoneId == 'None') {
        echo "WARNING: Could not find Route53 zone for ${params.zoneName}"
        return false
    }

    echo "Creating Route53 CNAME record: ${params.domain} -> ${params.target}"

    sh """
        cat > /tmp/route53-change.json <<EOF
{
    "Changes": [{
        "Action": "UPSERT",
        "ResourceRecordSet": {
            "Name": "${params.domain}",
            "Type": "CNAME",
            "TTL": ${params.ttl},
            "ResourceRecords": [{"Value": "${params.target}"}]
        }
    }]
}
EOF

        aws route53 change-resource-record-sets \\
            --hosted-zone-id ${zoneId} \\
            --change-batch file:///tmp/route53-change.json

        rm -f /tmp/route53-change.json
    """

    echo 'Route53 record created successfully'
    return true
}

/**
 * Generates a random password for PMM admin.
 *
 * @return String Random password (16 characters)
 */
def generatePassword() {
    def chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    def random = new Random()
    def password = (1..16).collect { chars[random.nextInt(chars.length())] }.join('')
    return password
}

/**
 * Checks if the number of existing clusters exceeds the limit.
 *
 * @param config Map containing:
 *   - maxClusters: Maximum allowed clusters (optional, default: 5)
 *
 * @return boolean true if under limit, false if at/over limit
 */
def checkClusterLimit(Map config = [:]) {
    def maxClusters = config.maxClusters ?: MAX_CLUSTERS

    def clusters = listClusters()
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

return this
