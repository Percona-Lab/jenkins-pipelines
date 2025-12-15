// PMM HA EKS Library - uses eksCluster.groovy for generic EKS operations
import groovy.transform.Field

@Field static final String CLUSTER_PREFIX = 'pmm-ha-test-'
@Field static final int MAX_CLUSTERS = 5
@Field static final int MAX_RETENTION_DAYS = 7
@Field static final int SKIP_NEWEST_THRESHOLD_HOURS = 4  // Only skip "newest" if created within this window
@Field static final String PMM_SERVICE_NAME = 'pmm-ha-haproxy'
@Field static final String PMM_DEFAULT_NAMESPACE = 'pmm'

/** Clamp retention days to valid range (1 to MAX_RETENTION_DAYS)
 *  @param retentionDays, maxDays */
def validateRetentionDays(def retentionDays, int maxDays = MAX_RETENTION_DAYS) {
    def days = 1
    try { days = retentionDays ? (retentionDays.toString() as int) : 1 } catch (Exception e) { days = 1 }

    return Math.max(1, Math.min(days, maxDays))
}

/** Validate helm chart branch exists (fail fast before cluster creation)
 *  @param chartBranch */
def validateHelmChart(String chartBranch) {
    cloneHelmCharts(chartBranch, 'validate-charts')
    sh 'rm -rf validate-charts'
    echo "Validated helm chart branch: ${chartBranch}"
}

/** Clone PMM HA helm charts (tries theTibi fork first, falls back to percona)
 *  @param chartBranch, targetDir */
def cloneHelmCharts(String chartBranch, String targetDir = 'charts-repo') {
    def repoSource

    withEnv([
        "TARGET_DIR=${targetDir}",
        "CHART_BRANCH=${chartBranch}"
    ]) {
        repoSource = sh(script: '''
            set -e
            rm -rf "${TARGET_DIR}"
            has_charts() { [ -d "${TARGET_DIR}/charts/pmm-ha" ] && [ -d "${TARGET_DIR}/charts/pmm-ha-dependencies" ]; }
            if git clone --depth 1 --branch "${CHART_BRANCH}" https://github.com/theTibi/percona-helm-charts.git "${TARGET_DIR}" 2>/dev/null && has_charts; then
                echo "theTibi"
            elif git clone --depth 1 --branch "${CHART_BRANCH}" https://github.com/percona/percona-helm-charts.git "${TARGET_DIR}" 2>/dev/null && has_charts; then
                echo "percona"
            else
                echo "ERROR: Branch '${CHART_BRANCH}' not found or missing charts" >&2; exit 1
            fi
        ''', returnStdout: true).trim()
    }

    return repoSource
}

/** Extract credentials from pmm-secret
 *  @param namespace */
def getCredentials(String namespace = PMM_DEFAULT_NAMESPACE) {
    def output

    // Single kubectl call with go-template: 1 API call vs 6, atomic snapshot, built-in base64decode
    withEnv([
        "NAMESPACE=${namespace}"
    ]) {
        output = sh(script: '''
            kubectl get secret pmm-secret -n "${NAMESPACE}" -o go-template='pmm={{index .data "PMM_ADMIN_PASSWORD" | base64decode}}
pg={{index .data "PG_PASSWORD" | base64decode}}
ch_user={{index .data "PMM_CLICKHOUSE_USER" | base64decode}}
ch={{index .data "PMM_CLICKHOUSE_PASSWORD" | base64decode}}
vm_user={{index .data "VMAGENT_remoteWrite_basicAuth_username" | base64decode}}
vm={{index .data "VMAGENT_remoteWrite_basicAuth_password" | base64decode}}'
        ''', returnStdout: true).trim()
    }

    def creds = [:]
    output.split('\n').each { line ->
        def parts = line.split('=', 2)
        if (parts.size() == 2) { creds[parts[0]] = parts[1] }
    }

    return creds
}

/** Write access-info.txt artifact with cluster and credential details
 *  @param clusterName, buildNumber, region, namespace */
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

/** Install PMM HA stack (operators, secrets, PMM pods)
 *  @param namespace, chartBranch, imageTag, adminPassword */
def installPmm(Map config) {
    def namespace = config.namespace ?: PMM_DEFAULT_NAMESPACE
    def chartBranch = config.chartBranch ?: 'main'
    def imageTag = config.imageTag ?: ''
    def adminPassword = config.adminPassword ?: ''

    def repoSource = cloneHelmCharts(chartBranch)
    writeFile file: '.chart-repo-source', text: repoSource
    echo "Installing PMM HA from ${repoSource}/${chartBranch}"

    withEnv([
        "NS=${namespace}",
        "TAG=${imageTag}",
        "ADMIN_PW=${adminPassword}"
    ]) {
        sh '''
            set -euo pipefail
            helm repo add percona https://percona.github.io/percona-helm-charts/ || true
            helm repo add vm https://victoriametrics.github.io/helm-charts/ || true
            helm repo add altinity https://docs.altinity.com/helm-charts/ || true
            helm repo update

            helm dependency update charts-repo/charts/pmm-ha-dependencies
            helm upgrade --install pmm-operators charts-repo/charts/pmm-ha-dependencies -n "${NS}" --create-namespace --wait --timeout 10m

            kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=victoria-metrics-operator -n "${NS}" --timeout=300s || true
            kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=altinity-clickhouse-operator -n "${NS}" --timeout=300s || true
            kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=pg-operator -n "${NS}" --timeout=300s || true

            PMM_PW=${ADMIN_PW:-$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16)}
            PG_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
            GF_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
            CH_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
            VM_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)

            kubectl create secret generic pmm-secret -n "${NS}" \
                --from-literal=PMM_ADMIN_PASSWORD="${PMM_PW}" \
                --from-literal=GF_SECURITY_ADMIN_PASSWORD="${PMM_PW}" \
                --from-literal=PG_PASSWORD="${PG_PW}" \
                --from-literal=GF_PASSWORD="${GF_PW}" \
                --from-literal=PMM_CLICKHOUSE_USER="clickhouse_pmm" \
                --from-literal=PMM_CLICKHOUSE_PASSWORD="${CH_PW}" \
                --from-literal=VMAGENT_remoteWrite_basicAuth_username="victoriametrics_pmm" \
                --from-literal=VMAGENT_remoteWrite_basicAuth_password="${VM_PW}" \
                --dry-run=client -o yaml | kubectl apply -f -

            helm dependency update charts-repo/charts/pmm-ha
            helm upgrade --install pmm-ha charts-repo/charts/pmm-ha -n "${NS}" \
                --set secret.create=false \
                --set secret.name=pmm-secret \
                --set clickhouse.resources.requests.memory=4Gi \
                --set clickhouse.resources.limits.memory=10Gi \
                --wait --timeout 15m \
                ${TAG:+--set image.tag=${TAG}}  # Only add if TAG is non-empty

            kubectl rollout status statefulset/pmm-ha -n "${NS}" --timeout=600s || true
            kubectl wait --for=condition=ready pod -l clickhouse.altinity.com/chi=pmm-ha -n "${NS}" --timeout=600s || true
            kubectl get pods -n "${NS}"
        '''
    }
}

/** Filter clusters by retention tag (returns expired or untagged clusters)
 *  @param clusters, region, verbose */
def filterByRetention(List clusters, String region, boolean verbose = true) {
    def now = (long)(System.currentTimeMillis() / 1000)
    def filtered = []

    clusters.each { c ->
        def tag
        // Fetch the 'delete-after' tag for the current cluster (may return empty/None/null if not set)
        withEnv([
            "CLUSTER=${c}",
            "REGION=${region}"
        ]) {
            tag = sh(script: '''
                aws eks describe-cluster --name "${CLUSTER}" --region "${REGION}" --query 'cluster.tags."delete-after"' --output text || echo ''
            ''', returnStdout: true).trim()
        }

        // If the tag exists and is a valid timestamp value
        if (tag && tag != 'None' && tag != 'null' && tag != '') {
            def exp = tag as long // Convert tag to expiration timestamp (seconds)

            // The cluster's retention has expired
            if (now > exp) {
                if (verbose) { echo "DELETE: ${c} - expired" }
                filtered.add(c)
            } else if (verbose) {
                // Still within retention period
                def hours = (int)((exp - now) / 3600)
                echo "KEEP: ${c} - ${hours}h remaining"
            }
        } else {
            // No valid delete-after tag, so treat for deletion
            if (verbose) { echo "DELETE: ${c} - no retention tag" }
            filtered.add(c)
        }
    }

    // Return list of clusters that should be deleted: expired or missing retention tag
    return filtered
}

/** Check if a cluster was created recently (within threshold hours)
 *  @param clusterName, region, thresholdHours */
def isClusterRecent(String clusterName, String region, int thresholdHours = SKIP_NEWEST_THRESHOLD_HOURS) {
    def createdAt
    withEnv([
        "CLUSTER=${clusterName}",
        "REGION=${region}"
    ]) {
        createdAt = sh(script: '''
            aws eks describe-cluster --name "${CLUSTER}" --region "${REGION}" --query 'cluster.createdAt' --output text 2>/dev/null || echo ''
        ''', returnStdout: true).trim()
    }

    if (!createdAt || createdAt == 'None' || createdAt == 'null') {
        return false  // Can't determine age, assume not recent
    }

    // Parse ISO timestamp and compare to threshold
    def ageHours
    withEnv(["CREATED=${createdAt}"]) {
        ageHours = sh(script: '''
            CREATED_EPOCH=$(date -d "${CREATED}" +%s 2>/dev/null || echo 0)
            NOW_EPOCH=$(date +%s)
            echo $(( (NOW_EPOCH - CREATED_EPOCH) / 3600 ))
        ''', returnStdout: true).trim() as int
    }

    return ageHours < thresholdHours
}

/** Delete PMM HA clusters (parallelizes when multiple provided)
 *  @param clusterNames, region */
def deleteClusters(Map config) {
    def clusterNames = config.clusterNames ?: error('clusterNames required')
    def region = config.region ?: 'us-east-2'

    // Normalize to list if single string provided
    if (clusterNames instanceof String) {
        clusterNames = [clusterNames]
    }
    if (!clusterNames) {
        echo 'No clusters to delete'
        return
    }

    // Validate all names have correct prefix for safety
    clusterNames.each { name ->
        if (!name.startsWith(CLUSTER_PREFIX)) {
            error("Cluster name must start with '${CLUSTER_PREFIX}': ${name}")
        }
    }

    // Single cluster: delete directly; multiple: parallel
    if (clusterNames.size() == 1) {
        eksCluster.deleteCluster(clusterName: clusterNames[0], region: region)
        return
    }

    echo "Deleting ${clusterNames.size()} clusters in parallel: ${clusterNames.join(', ')}"
    def parallelStages = [:]
    clusterNames.each { name ->
        parallelStages["Delete ${name}"] = {
            eksCluster.deleteCluster(clusterName: name, region: region)
        }
    }
    parallel parallelStages
}

/** Delete all PMM HA clusters (with optional filters)
 *  @param region, skipNewest, respectRetention */
def deleteAllClusters(Map config = [:]) {
    def region = config.region ?: 'us-east-2'
    def skipNewest = config.skipNewest != null ? config.skipNewest : true
    def respectRetention = config.respectRetention != null ? config.respectRetention : true
    def clusters = eksCluster.listClusters(region: region, prefix: CLUSTER_PREFIX)

    if (!clusters) {
        echo "No clusters found with prefix '${CLUSTER_PREFIX}'"
        return
    }

    def toDelete = clusters

    // Skip newest cluster ONLY if it was created recently (protects in-progress builds)
    // Don't skip old clusters just because they happen to be "the only one"
    if (skipNewest && clusters.size() > 0) {
        def newest = clusters[0]
        if (isClusterRecent(newest, region)) {
            echo "Skipping newest cluster: ${newest} (created within last ${SKIP_NEWEST_THRESHOLD_HOURS}h)"
            toDelete = clusters.drop(1)
        } else {
            echo "Newest cluster ${newest} is older than ${SKIP_NEWEST_THRESHOLD_HOURS}h - not protecting"
        }
    }

    // Filter by retention tags (only delete expired clusters)
    if (respectRetention) {
        toDelete = filterByRetention(toDelete, region)
    }
    if (!toDelete) {
        echo 'No clusters to delete after applying filters'
        return
    }

    deleteClusters(clusterNames: toDelete, region: region)
}
