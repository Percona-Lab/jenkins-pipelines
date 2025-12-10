// PMM HA EKS Library
// PMM-specific functions for deploying and managing PMM High Availability on EKS.
// Uses eksCluster.groovy for generic EKS operations.
import groovy.transform.Field

@Field static final String CLUSTER_PREFIX = 'pmm-ha-test-'
@Field static final int MAX_CLUSTERS = 5
@Field static final int MAX_RETENTION_DAYS = 7
@Field static final String PMM_SERVICE_NAME = 'pmm-ha-haproxy'
@Field static final String PMM_DEFAULT_NAMESPACE = 'pmm'

// === VALIDATION ===

/**
 * Validate helm chart branch exists before cluster creation (fail-fast)
 * @param chartBranch Branch name to validate in percona-helm-charts repo
 */
def validateHelmChart(String chartBranch) {
    cloneHelmCharts(chartBranch, 'validate-charts')
    sh 'rm -rf validate-charts'
    echo "Validated helm chart branch: ${chartBranch}"
}

// === HELM CHARTS ===

/**
 * Clone PMM HA helm charts from theTibi or percona repo
 * Tries theTibi/percona-helm-charts first (feature branches), falls back to percona/percona-helm-charts
 * @param chartBranch Branch name to clone
 * @param targetDir Target directory for clone (default: 'charts-repo')
 * @return String repo source ('theTibi' or 'percona')
 */
def cloneHelmCharts(String chartBranch, String targetDir = 'charts-repo') {
    def repoSource = sh(script: """
        set -e
        rm -rf "${targetDir}"
        has_charts() { [ -d "${targetDir}/charts/pmm-ha" ] && [ -d "${targetDir}/charts/pmm-ha-dependencies" ]; }
        if git clone --depth 1 --branch "${chartBranch}" https://github.com/theTibi/percona-helm-charts.git "${targetDir}" 2>/dev/null && has_charts; then
            echo "theTibi"
        elif git clone --depth 1 --branch "${chartBranch}" https://github.com/percona/percona-helm-charts.git "${targetDir}" 2>/dev/null && has_charts; then
            echo "percona"
        else
            echo "ERROR: Branch '${chartBranch}' not found or missing charts" >&2; exit 1
        fi
    """, returnStdout: true).trim()
    return repoSource
}

// === CREDENTIALS ===

/**
 * Extract all credentials from pmm-secret
 * @param namespace K8s namespace (default: 'pmm')
 * @return Map with keys: pmm, pg, ch_user, ch, vm_user, vm
 */
def getCredentials(String namespace = PMM_DEFAULT_NAMESPACE) {
    def output = sh(script: """
        kubectl get secret pmm-secret -n ${namespace} -o go-template='pmm={{index .data "PMM_ADMIN_PASSWORD" | base64decode}}
pg={{index .data "PG_PASSWORD" | base64decode}}
ch_user={{index .data "PMM_CLICKHOUSE_USER" | base64decode}}
ch={{index .data "PMM_CLICKHOUSE_PASSWORD" | base64decode}}
vm_user={{index .data "VMAGENT_remoteWrite_basicAuth_username" | base64decode}}
vm={{index .data "VMAGENT_remoteWrite_basicAuth_password" | base64decode}}'
    """, returnStdout: true).trim()
    def creds = [:]
    output.split('\n').each { line ->
        def parts = line.split('=', 2)
        if (parts.size() == 2) { creds[parts[0]] = parts[1] }
    }
    return creds
}

/**
 * Write access-info.txt artifact with cluster and credential details
 * @param config.clusterName     EKS cluster name (required)
 * @param config.buildNumber     Jenkins build number (required)
 * @param config.region          AWS region (default: 'us-east-2')
 * @param config.namespace       K8s namespace (default: 'pmm')
 * @return Map with creds
 */
def writeAccessInfo(Map config) {
    def clusterName = config.clusterName ?: error('clusterName required')
    def buildNumber = config.buildNumber ?: error('buildNumber required')
    def region = config.region ?: 'us-east-2'
    def namespace = config.namespace ?: PMM_DEFAULT_NAMESPACE

    def creds = getCredentials(namespace)

    sh 'mkdir -p pmm-credentials'
    def accessInfo = """PMM HA Access Information
=========================
Cluster: ${clusterName}
Build:   ${buildNumber}
Region:  ${region}

Access: kubectl port-forward svc/${PMM_SERVICE_NAME} 8443:443 -n ${namespace}
        Then open https://localhost:8443

PMM/Grafana: admin / ${creds.pmm}
PostgreSQL:  ${creds.pg}
ClickHouse:  ${creds.ch_user} / ${creds.ch}
VictoriaMetrics: ${creds.vm_user} / ${creds.vm}

kubectl: aws eks update-kubeconfig --name ${clusterName} --region ${region}
"""
    writeFile file: 'pmm-credentials/access-info.txt', text: accessInfo
    return [creds: creds]
}

// === PMM DEPLOYMENT ===

/**
 * Install PMM HA stack (operators, secrets, PMM pods)
 * @param config.namespace      K8s namespace (default: 'pmm')
 * @param config.chartBranch    Helm chart branch (default: 'main')
 * @param config.imageTag       PMM image tag (default: chart default)
 * @param config.adminPassword  PMM admin password (default: auto-generated)
 */
def installPmm(Map config) {
    def namespace = config.namespace ?: PMM_DEFAULT_NAMESPACE
    def chartBranch = config.chartBranch ?: 'main'
    def imageTag = config.imageTag ?: ''
    def adminPassword = config.adminPassword ?: ''

    def repoSource = cloneHelmCharts(chartBranch)
    writeFile file: '.chart-repo-source', text: repoSource
    echo "Installing PMM HA from ${repoSource}/${chartBranch}"

    sh """
        set -euo pipefail
        NS="${namespace}" TAG="${imageTag}" ADMIN_PW="${adminPassword}"
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo add vm https://victoriametrics.github.io/helm-charts/ || true
        helm repo add altinity https://docs.altinity.com/helm-charts/ || true
        helm repo update

        helm dependency update charts-repo/charts/pmm-ha-dependencies
        helm upgrade --install pmm-operators charts-repo/charts/pmm-ha-dependencies -n "\${NS}" --create-namespace --wait --timeout 10m

        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=victoria-metrics-operator -n "\${NS}" --timeout=300s || true
        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=altinity-clickhouse-operator -n "\${NS}" --timeout=300s || true
        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=pg-operator -n "\${NS}" --timeout=300s || true

        PMM_PW=\${ADMIN_PW:-\$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16)}
        PG_PW=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
        GF_PW=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
        CH_PW=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
        VM_PW=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)

        kubectl create secret generic pmm-secret -n "\${NS}" \\
            --from-literal=PMM_ADMIN_PASSWORD="\${PMM_PW}" \\
            --from-literal=GF_SECURITY_ADMIN_PASSWORD="\${PMM_PW}" \\
            --from-literal=PG_PASSWORD="\${PG_PW}" \\
            --from-literal=GF_PASSWORD="\${GF_PW}" \\
            --from-literal=PMM_CLICKHOUSE_USER="clickhouse_pmm" \\
            --from-literal=PMM_CLICKHOUSE_PASSWORD="\${CH_PW}" \\
            --from-literal=VMAGENT_remoteWrite_basicAuth_username="victoriametrics_pmm" \\
            --from-literal=VMAGENT_remoteWrite_basicAuth_password="\${VM_PW}" \\
            --dry-run=client -o yaml | kubectl apply -f -

        helm dependency update charts-repo/charts/pmm-ha
        helm upgrade --install pmm-ha charts-repo/charts/pmm-ha -n "\${NS}" \\
            --set secret.create=false \\
            --set secret.name=pmm-secret \\
            --set clickhouse.resources.requests.memory=4Gi \\
            --set clickhouse.resources.limits.memory=10Gi \\
            --wait --timeout 15m \\
            \${TAG:+--set image.tag=\${TAG}}  # Only add if TAG is non-empty

        kubectl rollout status statefulset/pmm-ha -n "\${NS}" --timeout=600s || true
        kubectl wait --for=condition=ready pod -l clickhouse.altinity.com/chi=pmm-ha -n "\${NS}" --timeout=600s || true
        kubectl get pods -n "\${NS}"
    """
}

// === CLUSTER LIFECYCLE ===

/**
 * Delete PMM HA cluster
 * @param config.clusterName  EKS cluster name (required, must start with pmm-ha-test-)
 * @param config.region       AWS region (default: 'us-east-2')
 */
def deleteCluster(Map config) {
    def clusterName = config.clusterName ?: error('clusterName required')
    def region = config.region ?: 'us-east-2'

    if (!clusterName.startsWith(CLUSTER_PREFIX)) {
        error("clusterName must start with '${CLUSTER_PREFIX}' for safety")
    }

    eksCluster.deleteCluster(clusterName: clusterName, region: region)
}

/**
 * Delete multiple PMM HA clusters (respects retention tags, optionally skips newest)
 * @param config.region           AWS region (default: 'us-east-2')
 * @param config.skipNewest       Protect newest cluster (default: true)
 * @param config.respectRetention Only delete expired clusters (default: true)
 */
def deleteAllClusters(Map config = [:]) {
    def region = config.region ?: 'us-east-2'
    def skipNewest = config.skipNewest != null ? config.skipNewest : true
    def respectRetention = config.respectRetention != null ? config.respectRetention : true

    def clusters = eksCluster.listClusters(region: region, prefix: CLUSTER_PREFIX)
    if (!clusters) { echo "No clusters with prefix '${CLUSTER_PREFIX}'"; return }

    def toDelete = skipNewest ? clusters.drop(1) : clusters  // clusters sorted newest-first
    if (skipNewest && clusters) { echo "Skipping newest: ${clusters[0]}" }
    if (respectRetention) { toDelete = eksCluster.filterByRetention(toDelete, region) }
    if (!toDelete) { echo 'No clusters to delete after filtering'; return }

    def parallel_stages = [:]
    toDelete.each { c -> parallel_stages["Delete ${c}"] = { deleteCluster(clusterName: c, region: region) } }
    parallel parallel_stages
}
