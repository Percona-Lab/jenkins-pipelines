/**
 * PMM HA ROSA operations library.
 *
 * Extends openshiftRosa with PMM High Availability specific deployment.
 * For generic ROSA operations (cluster lifecycle), this library delegates to openshiftRosa.
 *
 * Prerequisites:
 * - rosa CLI installed on Jenkins agents
 * - Red Hat offline token stored as Jenkins credential
 * - AWS credentials with ROSA permissions
 *
 * @example
 * // Deploy PMM HA
 * pmmHaRosa.installPmm([
 *     namespace: 'pmm',
 *     chartBranch: 'PMM-14420',
 *     imageTag: '3.4.0'
 * ])
 */

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.Field

// ============================================================================
// Configuration Constants (PMM HA specific)
// ============================================================================

@Field static final String CLUSTER_PREFIX = 'pmm-ha-rosa-'
@Field static final int MAX_CLUSTERS = 5

// ============================================================================
// Delegated Operations (from openshiftRosa)
// ============================================================================

/**
 * Installs ROSA CLI. Delegates to openshiftRosa.
 */
def installRosaCli(Map config = [:]) {
    return openshiftRosa.installRosaCli(config)
}

/**
 * Installs OpenShift CLI (oc). Delegates to openshiftRosa.
 */
def installOcCli(Map config = [:]) {
    return openshiftRosa.installOcCli(config)
}

/**
 * Logs into ROSA. Delegates to openshiftRosa.
 */
def login(Map config) {
    return openshiftRosa.login(config)
}

/**
 * Creates a ROSA HCP cluster with PMM HA prefix.
 *
 * Wraps openshiftRosa.createCluster() to add the PMM HA cluster prefix.
 *
 * @param config Map containing cluster configuration (see openshiftRosa.createCluster)
 * @return Map containing cluster information
 */
def createCluster(Map config) {
    // Ensure cluster name has PMM HA prefix
    def clusterName = config.clusterName
    if (clusterName && !clusterName.startsWith(CLUSTER_PREFIX)) {
        config = config + [clusterName: "${CLUSTER_PREFIX}${clusterName}"]
    }

    return openshiftRosa.createCluster(config)
}

/**
 * Configures cluster access. Delegates to openshiftRosa.
 */
def configureAccess(Map config) {
    return openshiftRosa.configureAccess(config)
}

/**
 * Deletes a ROSA cluster. Delegates to openshiftRosa.
 */
def deleteCluster(Map config) {
    return openshiftRosa.deleteCluster(config)
}

/**
 * Lists PMM HA ROSA clusters (filtered by prefix).
 */
def listClusters(Map config = [:]) {
    return openshiftRosa.listClusters(config + [prefix: CLUSTER_PREFIX])
}

/**
 * Checks cluster limit for PMM HA clusters.
 */
def checkClusterLimit(Map config = [:]) {
    def maxClusters = config.maxClusters ?: MAX_CLUSTERS
    return openshiftRosa.checkClusterLimit(config + [
        prefix: CLUSTER_PREFIX,
        maxClusters: maxClusters
    ])
}

/**
 * Gets cluster age in hours. Delegates to openshiftRosa.
 */
def getClusterAgeHours(String createdAt) {
    return openshiftRosa.getClusterAgeHours(createdAt)
}


/**
 * Generates a random password. Delegates to openshiftRosa.
 */
def generatePassword() {
    return openshiftRosa.generatePassword()
}

/**
 * Formats cluster list for display. Delegates to openshiftRosa.
 */
def formatClustersSummary(List clusters, String title = 'PMM HA ROSA CLUSTERS') {
    return openshiftRosa.formatClustersSummary(clusters, title)
}

// ============================================================================
// PMM HA Specific Operations
// ============================================================================

// ECR prefix for image paths (must match openshiftRosa.ECR_PREFIX)
@Field static final String ECR_PREFIX = '119175775298.dkr.ecr.us-east-2.amazonaws.com/docker-hub'

/**
 * Configures ECR pull-through cache. Delegates to openshiftRosa.
 */
def configureEcrPullThrough(Map config = [:]) {
    return openshiftRosa.configureEcrPullThrough(config)
}

/**
 * Installs PMM HA on the ROSA cluster.
 *
 * Deploys PMM in High Availability mode using the pmm-ha Helm chart.
 *
 * @param config Map containing:
 *   - namespace: Kubernetes namespace for PMM (optional, default: 'pmm')
 *   - chartBranch: percona-helm-charts branch (optional, default: 'PMM-14420')
 *   - imageTag: PMM server image tag (optional, default: 'dev-latest')
 *   - imageRepository: PMM image repository (optional, default: 'perconalab/pmm-server')
 *   - adminPassword: PMM admin password (optional, auto-generated)
 *   - storageClass: StorageClass to use (optional, default: 'gp3-csi')
 *   - dependenciesStorageSize: Storage size for dependencies PVCs (optional, default: '10Gi')
 *   - pmmStorageSize: Storage size for PMM PVC (optional, default: '10Gi')
 *   - useEcr: Use ECR pull-through cache (optional, default: true)
 *   - dockerHubUser: Docker Hub username (fallback if useEcr=false)
 *   - dockerHubPassword: Docker Hub password (fallback if useEcr=false)
 *
 * @return Map containing:
 *   - namespace: Namespace where PMM is deployed
 *   - adminPassword: PMM admin password
 *   - serviceName: PMM service name
 */
def installPmm(Map config = [:]) {
    def params = [
        namespace: 'pmm',
        chartBranch: 'PMM-14420',
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

    def adminPassword = params.adminPassword ?: generatePassword()

    // Create namespace and configure SCC
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        # Create namespace
        oc create namespace ${params.namespace} || true

        # Create custom SCC for PMM HA on ROSA HCP
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

    // Pre-create pmm-secret
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"

        oc delete secret pmm-secret -n ${params.namespace} 2>/dev/null || true

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

    // Configure ECR pull-through cache
    def useEcr = params.useEcr != false
    def ecrPrefix = ECR_PREFIX

    if (useEcr) {
        configureEcrPullThrough([region: params.region ?: 'us-east-2'])
        echo "Using ECR pull-through cache: ${ecrPrefix}"
    } else if (config.dockerHubUser && config.dockerHubPassword) {
        sh """
            export PATH="\$HOME/.local/bin:\$PATH"

            oc get secret/pull-secret -n openshift-config \\
                --template='{{index .data ".dockerconfigjson" | base64decode}}' > /tmp/pull-secret.json

            oc registry login --registry="docker.io" \\
                --auth-basic="${config.dockerHubUser}:${config.dockerHubPassword}" \\
                --to=/tmp/pull-secret.json

            oc set data secret/pull-secret -n openshift-config \\
                --from-file=.dockerconfigjson=/tmp/pull-secret.json

            rm -f /tmp/pull-secret.json
        """
    }

    // Clone helm charts
    def chartsDir = "${env.WORKSPACE}/percona-helm-charts"
    def tibiRepo = 'https://github.com/theTibi/percona-helm-charts.git'
    def perconaRepo = 'https://github.com/percona/percona-helm-charts.git'

    sh """
        rm -rf ${chartsDir}
        echo "Trying to clone branch ${params.chartBranch}..."

        if git clone --depth 1 --branch ${params.chartBranch} ${tibiRepo} ${chartsDir} 2>/dev/null; then
            echo "Found branch in: ${tibiRepo}"
        elif git clone --depth 1 --branch ${params.chartBranch} ${perconaRepo} ${chartsDir} 2>/dev/null; then
            echo "Found branch in: ${perconaRepo}"
        else
            echo "ERROR: Branch '${params.chartBranch}' not found"
            exit 1
        fi
    """

    // Install Helm and repos
    sh '''
        export PATH="$HOME/.local/bin:$PATH"
        if ! command -v helm &>/dev/null; then
            curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
        fi
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo add vm https://victoriametrics.github.io/helm-charts/ || true
        helm repo add altinity https://docs.altinity.com/helm-charts/ || true
        helm repo add haproxytech https://haproxytech.github.io/helm-charts/ || true
        helm repo update
    '''

    // Update dependencies
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        helm dependency update ${chartsDir}/charts/pmm-ha-dependencies
        helm dependency update ${chartsDir}/charts/pmm-ha
    """

    // Install pmm-ha-dependencies
    def depsHelmArgs = [
        "--namespace ${params.namespace}",
        "--set global.storageClass=${params.storageClass}",
        "--set postgresql.primary.persistence.size=${params.dependenciesStorageSize}",
        "--set clickhouse.persistence.size=${params.dependenciesStorageSize}",
        "--set victoriametrics.server.persistentVolume.size=${params.dependenciesStorageSize}"
    ]

    if (useEcr) {
        depsHelmArgs.addAll([
            "--set pg-operator.operatorImageRepository=${ecrPrefix}/percona/percona-postgresql-operator",
            "--set victoria-metrics-operator.image.repository=${ecrPrefix}/victoriametrics/operator",
            '--set victoria-metrics-operator.image.tag=v0.56.4',
            "--set altinity-clickhouse-operator.operator.image.repository=${ecrPrefix}/altinity/clickhouse-operator",
            "--set altinity-clickhouse-operator.metrics.image.repository=${ecrPrefix}/altinity/metrics-exporter"
        ])
    }

    depsHelmArgs.add('--wait --timeout 15m')

    echo 'Installing PMM HA dependencies...'
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        helm upgrade --install pmm-ha-dependencies ${chartsDir}/charts/pmm-ha-dependencies ${depsHelmArgs.join(' ')}
    """

    // Install pmm-ha
    def helmArgs = [
        "--namespace ${params.namespace}",
        '--set secret.create=false',
        '--set secret.name=pmm-secret',
        "--set persistence.size=${params.pmmStorageSize}",
        "--set persistence.storageClassName=${params.storageClass}"
    ]

    if (params.imageRepository?.trim()) {
        def imageRepo = params.imageRepository
        if (useEcr && !imageRepo.startsWith(ecrPrefix)) {
            imageRepo = "${ecrPrefix}/${imageRepo}"
        }
        helmArgs.add("--set image.repository=${imageRepo}")
    }
    if (params.imageTag?.trim()) {
        helmArgs.add("--set image.tag=${params.imageTag}")
    }

    if (useEcr) {
        helmArgs.addAll([
            "--set clickhouse.image.repository=${ecrPrefix}/altinity/clickhouse-server",
            "--set clickhouse.keeper.image.repository=${ecrPrefix}/clickhouse/clickhouse-keeper",
            "--set haproxy.image.repository=${ecrPrefix}/haproxytech/haproxy-alpine",
            "--set pg-db.image=${ecrPrefix}/percona/percona-distribution-postgresql:17.6-1",
            "--set pg-db.proxy.pgBouncer.image=${ecrPrefix}/percona/percona-pgbouncer:1.24.1-1",
            "--set pg-db.backups.pgbackrest.image=${ecrPrefix}/percona/percona-pgbackrest:2.56.0-1",
            "--set vmcluster.spec.vmselect.image=${ecrPrefix}/victoriametrics/vmselect:v1.110.0-cluster",
            "--set vmcluster.spec.vminsert.image=${ecrPrefix}/victoriametrics/vminsert:v1.110.0-cluster",
            "--set vmcluster.spec.vmstorage.image=${ecrPrefix}/victoriametrics/vmstorage:v1.110.0-cluster",
            "--set vmauth.spec.image=${ecrPrefix}/victoriametrics/vmauth:v1.110.0",
            "--set vmagent.spec.image=${ecrPrefix}/victoriametrics/vmagent:v1.110.0"
        ])
    }

    helmArgs.add('--wait --timeout 15m')

    echo 'Installing PMM HA...'
    sh """
        export PATH="\$HOME/.local/bin:\$PATH"
        helm upgrade --install pmm-ha ${chartsDir}/charts/pmm-ha ${helmArgs.join(' ')}
    """

    // Verify
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

return this
