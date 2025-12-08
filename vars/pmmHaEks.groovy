// PMM HA EKS Library
// PMM-specific functions for deploying and managing PMM High Availability on EKS.
// Uses eksCluster.groovy for generic EKS operations.
import groovy.transform.Field
import groovy.json.JsonSlurper

@Field static final String CLUSTER_PREFIX = 'pmm-ha-test-'
@Field static final int MAX_CLUSTERS = 5
@Field static final int MAX_RETENTION_DAYS = 7
@Field static final String PMM_INGRESS_NAME = 'pmm-ha-alb'
@Field static final String PMM_SERVICE_NAME = 'pmm-ha-haproxy'
@Field static final String PMM_DEFAULT_NAMESPACE = 'pmm'
@Field static final String PMM_DEFAULT_R53_ZONE = 'cd.percona.com'

// === VALIDATION ===

// Validate helm chart branch exists before cluster creation
def validateHelmChart(String chartBranch) {
    cloneHelmCharts(chartBranch, 'validate-charts')
    sh 'rm -rf validate-charts'
    echo "Validated helm chart branch: ${chartBranch}"
}

// === HELM CHARTS ===

// Clone helm charts from theTibi or percona repo (tries theTibi first)
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

// Extract all credentials from pmm-secret (PMM, PostgreSQL, ClickHouse, VictoriaMetrics)
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
 * @param config.domain          Public domain for PMM URL
 * @param config.namespace       K8s namespace (default: 'pmm')
 * @param config.hasPublicIngress Include ALB hostname (default: true)
 * @return Map with creds and albHostname
 */
def writeAccessInfo(Map config) {
    def clusterName = config.clusterName ?: error('clusterName required')
    def buildNumber = config.buildNumber ?: error('buildNumber required')
    def region = config.region ?: 'us-east-2'
    def domain = config.domain ?: ''
    def namespace = config.namespace ?: PMM_DEFAULT_NAMESPACE
    def hasPublicIngress = config.hasPublicIngress != false

    def creds = getCredentials(namespace)
    def albHostname = ''
    if (hasPublicIngress) {
        albHostname = sh(script: "kubectl get ingress ${PMM_INGRESS_NAME} -n ${namespace} -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo 'pending'", returnStdout: true).trim()
    }

    sh 'mkdir -p pmm-credentials'
    def accessInfo = """PMM HA Access Information
=========================
Cluster: ${clusterName}
Build:   ${buildNumber}
Region:  ${region}
"""
    if (hasPublicIngress && domain) {
        accessInfo += "\nPMM URL: https://${domain}\nALB:     ${albHostname}\n"
    } else {
        accessInfo += "\nAccess: Internal only (kubectl port-forward svc/pmm-ha-haproxy 8443:443 -n ${namespace})\n"
    }
    accessInfo += """
PMM/Grafana: admin / ${creds.pmm}
PostgreSQL:  ${creds.pg}
ClickHouse:  ${creds.ch_user} / ${creds.ch}
VictoriaMetrics: ${creds.vm_user} / ${creds.vm}

kubectl: aws eks update-kubeconfig --name ${clusterName} --region ${region}
"""
    writeFile file: 'pmm-credentials/access-info.txt', text: accessInfo
    return [creds: creds, albHostname: albHostname]
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

// === NETWORKING HELPERS ===

// Get public Route53 hosted zone ID for a domain
def resolveR53ZoneId(String zoneName, String region = 'us-east-2') {
    def zoneId = sh(script: """
        id=\$(aws route53 list-hosted-zones-by-name --dns-name "${zoneName}" \\
            --query 'HostedZones[?Config.PrivateZone==`false`&&Name==`'"${zoneName}"'.`].Id' --output text | sed 's|/hostedzone/||g')
        [ \$(echo "\$id"|wc -w) -eq 1 ] && [ -n "\$id" ] && [ "\$id" != "None" ] && echo "\$id"
    """, returnStdout: true).trim()
    return zoneId
}

// Get ARN of wildcard ACM certificate (*.zoneName) for HTTPS
def resolveAcmCertificate(String zoneName, String region = 'us-east-2') {
    def cert = sh(script: """
        aws acm list-certificates --region "${region}" --certificate-statuses ISSUED \\
            --query "CertificateSummaryList[?DomainName=='*.${zoneName}'].CertificateArn|[0]" --output text
    """, returnStdout: true).trim()
    return (cert && cert != 'None') ? cert : ''
}

// Get ALB's canonical hosted zone ID (needed for Route53 alias records)
def getAlbZoneId(String albHostname, String region = 'us-east-2') {
    def z = sh(script: """aws elbv2 describe-load-balancers --region "${region}" --query "LoadBalancers[?DNSName=='${albHostname}'].CanonicalHostedZoneId" --output text""", returnStdout: true).trim()
    return (z && z != 'None') ? z : ''
}

// Create Route53 A record aliased to ALB
def createRoute53Alias(Map config) {
    def zoneName = config.zoneName ?: error('zoneName required')
    def recordName = config.recordName ?: error('recordName required')
    def albHostname = config.albHostname ?: error('albHostname required')
    def region = config.region ?: 'us-east-2'

    def r53Zone = resolveR53ZoneId(zoneName, region)
    if (!r53Zone) { error("No Route53 zone for ${zoneName}") }
    def albZone = getAlbZoneId(albHostname, region)
    if (!albZone) { echo 'WARNING: Could not get ALB zone ID'; return }

    sh """
        aws route53 change-resource-record-sets --hosted-zone-id "${r53Zone}" --change-batch '{
            "Changes":[{"Action":"UPSERT","ResourceRecordSet":{"Name":"${recordName}","Type":"A",
            "AliasTarget":{"HostedZoneId":"${albZone}","DNSName":"${albHostname}","EvaluateTargetHealth":true}}}]}'
    """
            }

// Delete Route53 A alias record
def deleteRoute53Record(Map config) {
    def zoneName = config.zoneName ?: error('zoneName required')
    def recordName = config.recordName ?: error('recordName required')
    def region = config.region ?: 'us-east-2'

    def r53Zone = resolveR53ZoneId(zoneName, region)
    if (!r53Zone) { echo "WARNING: No Route53 zone for ${zoneName}"; return }
    def fqdn = recordName.endsWith('.') ? recordName : "${recordName}."

    sh """
        set -euo pipefail
        alias=\$(aws route53 list-resource-record-sets --hosted-zone-id "${r53Zone}" \\
            --query "ResourceRecordSets[?Name=='${fqdn}'&&Type=='A'].AliasTarget|[0]" --output json 2>/dev/null)
        [ -z "\$alias" ] || [ "\$alias" = "null" ] && exit 0
        # jq -c builds compact JSON from multi-line AWS response (avoids shell word-splitting)
        change_batch=\$(echo "\$alias" | jq -c '{Changes:[{Action:"DELETE",ResourceRecordSet:{Name:"${fqdn}",Type:"A",AliasTarget:.}}]}')
        aws route53 change-resource-record-sets --hosted-zone-id "${r53Zone}" --change-batch "\$change_batch"
    """
}

// Poll until ALB hostname is assigned to ingress
def waitForAlb(String namespace, String ingressName, int maxAttempts = 30, int intervalSec = 10) {
    def hostname = sh(script: """
        for i in \$(seq 1 ${maxAttempts}); do
            h=\$(kubectl get ingress ${ingressName} -n ${namespace} -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "")
            [ -n "\$h" ] && echo "\$h" && exit 0
            sleep ${intervalSec}
        done
    """, returnStdout: true).trim()
    return hostname
}

// === EXTERNAL ACCESS ===

/**
 * Create ALB Ingress and Route53 DNS record for public HTTPS access
 * @param config.namespace    K8s namespace (default: 'pmm')
 * @param config.domain       Public FQDN for PMM (required)
 * @param config.certArn      ACM certificate ARN (required)
 * @param config.r53ZoneName  Route53 zone for DNS record
 * @param config.region       AWS region (default: 'us-east-2')
 * @return ALB hostname
 */
def createIngress(Map config) {
    def namespace = config.namespace ?: PMM_DEFAULT_NAMESPACE
    def domain = config.domain ?: error('domain required')
    def certArn = config.certArn ?: error('certArn required')
    def r53ZoneName = config.r53ZoneName ?: ''
    def region = config.region ?: 'us-east-2'

    // Create ALB Ingress for PMM HA
    sh """
        cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ${PMM_INGRESS_NAME}
  namespace: ${namespace}
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/certificate-arn: ${certArn}
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS":443}]'
    alb.ingress.kubernetes.io/backend-protocol: HTTPS
    alb.ingress.kubernetes.io/healthcheck-protocol: HTTPS
    alb.ingress.kubernetes.io/healthcheck-path: /v1/readyz
    alb.ingress.kubernetes.io/healthcheck-port: "443"
    alb.ingress.kubernetes.io/success-codes: "200"
spec:
  ingressClassName: alb
  rules:
  - host: ${domain}
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: ${PMM_SERVICE_NAME}
            port: { number: 443 }
EOF
    """

    def albHostname = waitForAlb(namespace, PMM_INGRESS_NAME)
    if (!albHostname) { error('ALB not provisioned within timeout') }

    if (r53ZoneName) {
        createRoute53Alias(zoneName: r53ZoneName, recordName: domain, albHostname: albHostname, region: region)
    }
    return albHostname
}

// === CLUSTER LIFECYCLE ===

// Get cluster tags as Map (for retention and ingress settings)
def getClusterTags(String clusterName, String region = 'us-east-2') {
    def tagsJson = sh(script: "aws eks describe-cluster --name ${clusterName} --region ${region} --query 'cluster.tags' --output json 2>/dev/null || echo '{}'", returnStdout: true).trim()
    def tags = [:]
    try {
        def parsed = new JsonSlurper().parseText(tagsJson)
        if (parsed instanceof Map) { tags = parsed.collectEntries { k, v -> [k, v] } }
    } catch (Exception e) {
        echo "Warning: Could not parse cluster tags: ${e.message}"
    }
    return tags
}

/**
 * Delete PMM HA cluster with Route53 DNS cleanup
 * @param config.clusterName  EKS cluster name (required)
 * @param config.region       AWS region (default: 'us-east-2')
 * @param config.r53ZoneName  Route53 zone override (default: from cluster tags)
 */
def deleteCluster(Map config) {
    def clusterName = config.clusterName ?: error('clusterName required')
    def region = config.region ?: 'us-east-2'
    def r53ZoneOverride = config.r53ZoneName ?: ''

    def tags = getClusterTags(clusterName, region)
    def installPmm = tags['install-pmm']
    def publicIngress = tags['public-ingress']
    def r53ZoneTag = tags['r53-zone']

    // Determine Route53 zone for DNS cleanup ('none' tag means no public ingress, skip cleanup)
    def zoneName = r53ZoneOverride ?: (r53ZoneTag == 'none' ? '' : r53ZoneTag) ?: PMM_DEFAULT_R53_ZONE

    // Check if cluster has PMM with public ingress (based on creation-time tags)
    def hasPmmIngress = installPmm?.toLowerCase() != 'false' && publicIngress?.toLowerCase() != 'false'
    if (hasPmmIngress && zoneName) {
        deleteRoute53Record(zoneName: zoneName, recordName: "${clusterName}.${zoneName}", region: region)
    }

    // Empty strings skip ingress cleanup in eksCluster.deleteCluster
    def cleanupNs = hasPmmIngress ? PMM_DEFAULT_NAMESPACE : ''
    def cleanupIngress = hasPmmIngress ? PMM_INGRESS_NAME : ''
    eksCluster.deleteCluster(clusterName: clusterName, region: region, cleanupNamespace: cleanupNs, cleanupIngress: cleanupIngress)
}

/**
 * Delete multiple clusters (respects retention tags, optionally skips newest)
 * @param config.region           AWS region (default: 'us-east-2')
 * @param config.r53ZoneName      Route53 zone for DNS cleanup
 * @param config.skipNewest       Protect newest cluster (default: true)
 * @param config.respectRetention Only delete expired clusters (default: true)
 */
def deleteAllClusters(Map config = [:]) {
    def region = config.region ?: 'us-east-2'
    def r53ZoneName = config.r53ZoneName ?: ''
    def skipNewest = config.skipNewest != null ? config.skipNewest : true
    def respectRetention = config.respectRetention != null ? config.respectRetention : true

    def clusters = eksCluster.listClusters(region: region, prefix: CLUSTER_PREFIX)
    if (!clusters) { echo "No clusters with prefix '${CLUSTER_PREFIX}'"; return }

    def toDelete = skipNewest ? clusters.drop(1) : clusters  // clusters sorted newest-first
    if (skipNewest && clusters) { echo "Skipping newest: ${clusters[0]}" }
    if (respectRetention) { toDelete = eksCluster.filterByRetention(toDelete, region) }
    if (!toDelete) { echo 'No clusters to delete after filtering'; return }

    def parallel_stages = [:]
    toDelete.each { c -> parallel_stages["Delete ${c}"] = { deleteCluster(clusterName: c, region: region, r53ZoneName: r53ZoneName) } }
    parallel parallel_stages
}
