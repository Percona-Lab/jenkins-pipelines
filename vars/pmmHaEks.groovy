/**
 * PMM HA EKS Shared Library
 *
 * Reusable functions for PMM High Availability testing on EKS clusters.
 *
 * Sections:
 *   1. Constants
 *   2. Validation Helpers
 *   3. AWS Resource Resolution
 *   4. Credential Management
 *   5. EKS Cluster Setup
 *   6. PMM Installation
 *   7. Cluster Lifecycle (list, delete, cleanup)
 *
 * Related:
 *   - Create pipeline: pmm/v3/pmm3-ha-eks.groovy
 *   - Cleanup pipeline: pmm/v3/pmm3-ha-eks-cleanup.groovy
 */

import groovy.transform.Field

// ============================================
// 1. CONSTANTS
// ============================================

@Field static final String CLUSTER_PREFIX = 'pmm-ha-test-'
@Field static final int MAX_CLUSTERS = 5
@Field static final int DEFAULT_RETENTION_HOURS = 24
@Field static final int MAX_RETENTION_DAYS = 7
@Field static final int ALB_WAIT_ATTEMPTS = 30
@Field static final int ALB_WAIT_INTERVAL_SEC = 10

// ============================================
// 2. VALIDATION HELPERS
// ============================================

/**
 * Validate and normalize retention days parameter.
 *
 * @param retentionDays Input value (String or Integer)
 * @return Integer between 1 and MAX_RETENTION_DAYS
 */
def validateRetentionDays(def retentionDays) {
    def days = 1
    try {
        days = retentionDays ? (retentionDays.toString() as int) : 1
    } catch (Exception e) {
        echo "WARNING: Invalid RETENTION_DAYS '${retentionDays}', using default 1"
        days = 1
    }
    if (days < 1) {
        days = 1
    }
    if (days > MAX_RETENTION_DAYS) {
        days = MAX_RETENTION_DAYS
    }
    return days
}

/**
 * Clone helm charts from theTibi or percona repo with fallthrough logic.
 *
 * Tries theTibi fork first (has PMM-14420 development), then percona repo.
 * Only accepts a repo if it has both pmm-ha and pmm-ha-dependencies charts.
 *
 * @param chartBranch Branch name to clone
 * @param targetDir   Directory to clone into (default: charts-repo)
 * @return String name of repo source ('theTibi' or 'percona')
 */
def cloneHelmCharts(String chartBranch, String targetDir = 'charts-repo') {
    def repoSource = sh(
        script: """
            set -e
            TARGET_DIR="${targetDir}"
            BRANCH="${chartBranch}"
            rm -rf "\${TARGET_DIR}"

            TIBI_REPO="https://github.com/theTibi/percona-helm-charts.git"
            PERCONA_REPO="https://github.com/percona/percona-helm-charts.git"

            # Helper to check if required charts exist
            has_required_charts() {
                [ -d "\${TARGET_DIR}/charts/pmm-ha" ] && [ -d "\${TARGET_DIR}/charts/pmm-ha-dependencies" ]
            }

            REPO_SOURCE=""

            # Try theTibi fork first (has PMM-14420 development)
            if git clone --depth 1 --branch "\${BRANCH}" "\${TIBI_REPO}" "\${TARGET_DIR}" 2>/dev/null; then
                if has_required_charts; then
                    REPO_SOURCE="theTibi"
                else
                    echo "Branch '\${BRANCH}' found in theTibi but missing required charts, trying percona..." >&2
                    rm -rf "\${TARGET_DIR}"
                fi
            fi

            # Try percona repo if theTibi didn't have branch or charts
            if [ -z "\${REPO_SOURCE}" ]; then
                if git clone --depth 1 --branch "\${BRANCH}" "\${PERCONA_REPO}" "\${TARGET_DIR}" 2>/dev/null; then
                    if has_required_charts; then
                        REPO_SOURCE="percona"
                    else
                        echo "ERROR: Branch '\${BRANCH}' found in percona but missing required charts" >&2
                        ls -la "\${TARGET_DIR}/charts/" >&2 || true
                        rm -rf "\${TARGET_DIR}"
                        exit 1
                    fi
                else
                    echo "ERROR: Branch '\${BRANCH}' not found in theTibi or percona helm chart repos" >&2
                    exit 1
                fi
            fi

            echo "\${REPO_SOURCE}"
        """,
        returnStdout: true
    ).trim()

    return repoSource
}

/**
 * Validate Helm chart branch exists and contains required charts.
 *
 * @param chartBranch Branch name to validate
 * @return String name of repo source ('theTibi' or 'percona')
 */
def validateHelmChart(String chartBranch) {
    def repoSource = cloneHelmCharts(chartBranch, 'charts-repo-check')
    sh 'rm -rf charts-repo-check'
    echo "Helm charts validated: ${repoSource}/${chartBranch}"
    return repoSource
}

// ============================================
// 3. AWS RESOURCE RESOLUTION
// ============================================

/**
 * Resolve Route53 hosted zone ID from zone name.
 *
 * @param zoneName Route53 zone name (e.g., cd.percona.com)
 * @param region   AWS region
 * @return Zone ID or empty string if not found
 */
def resolveR53ZoneId(String zoneName, String region = 'us-east-2') {
    def zoneId = sh(
        script: """
            R53_ZONE_ID=\$(aws route53 list-hosted-zones-by-name \\
                --dns-name "${zoneName}" \\
                --query 'HostedZones[?Config.PrivateZone==`false` && Name==`'"${zoneName}"'.`].Id' \\
                --output text | sed 's|/hostedzone/||g')

            zone_count=\$(echo "\${R53_ZONE_ID}" | wc -w | tr -d ' ')
            if [ "\${zone_count}" -eq 1 ] && [ -n "\${R53_ZONE_ID}" ] && [ "\${R53_ZONE_ID}" != "None" ]; then
                echo "\${R53_ZONE_ID}"
            else
                echo ""
            fi
        """,
        returnStdout: true
    ).trim()

    if (zoneId) {
        echo "Resolved Route53 zone ID: ${zoneId}"
    } else {
        echo "WARNING: Could not resolve Route53 zone for ${zoneName}"
    }
    return zoneId
}

/**
 * Resolve ACM wildcard certificate ARN for a given zone.
 *
 * Finds the first ISSUED wildcard certificate (*.zoneName) in the specified region.
 *
 * @param zoneName Route53 zone name (e.g., cd.percona.com)
 * @param region   AWS region (default: us-east-2)
 * @return Certificate ARN or empty string if not found
 */
def resolveAcmCertificate(String zoneName, String region = 'us-east-2') {
    def wildcardDomain = "*.${zoneName}"
    def certArn = sh(
        script: 'aws acm list-certificates --region "' + region + '" ' +
                '--certificate-statuses ISSUED ' +
                '--query "CertificateSummaryList[?DomainName==\\`' + wildcardDomain + '\\`].CertificateArn | [0]" ' +
                '--output text',
        returnStdout: true
    ).trim()

    if (certArn && certArn != 'None') {
        echo "Resolved ACM certificate for ${wildcardDomain}: ${certArn}"
        return certArn
    }

    echo "WARNING: No valid ACM wildcard certificate found for ${wildcardDomain}"
    return ''
}

// ============================================
// 4. CREDENTIAL MANAGEMENT
// ============================================

/**
 * Get all credentials from pmm-secret.
 *
 * @param namespace Kubernetes namespace (default: pmm)
 * @return Map with pmm, pg, ch_user, ch, vm_user, vm passwords
 */
def getCredentials(String namespace = 'pmm') {
    // Single kubectl call with go-template to decode all secrets
    def output = sh(
        script: """
            kubectl get secret pmm-secret -n ${namespace} -o go-template='pmm={{index .data "PMM_ADMIN_PASSWORD" | base64decode}}
pg={{index .data "PG_PASSWORD" | base64decode}}
ch_user={{index .data "PMM_CLICKHOUSE_USER" | base64decode}}
ch={{index .data "PMM_CLICKHOUSE_PASSWORD" | base64decode}}
vm_user={{index .data "VMAGENT_remoteWrite_basicAuth_username" | base64decode}}
vm={{index .data "VMAGENT_remoteWrite_basicAuth_password" | base64decode}}'
        """,
        returnStdout: true
    ).trim()

    def creds = [:]
    output.split('\n').each { line ->
        def parts = line.split('=', 2)
        if (parts.size() == 2) {
            creds[parts[0]] = parts[1]
        }
    }
    return creds
}

/**
 * Write access-info.txt artifact with all credentials.
 *
 * @param clusterName EKS cluster name
 * @param buildNumber Jenkins build number
 * @param region      AWS region
 * @param domain      PMM domain (FQDN)
 * @param namespace   Kubernetes namespace (default: pmm)
 */
def writeAccessInfo(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def buildNumber = config.buildNumber ?: error('buildNumber is required')
    def region = config.region ?: 'us-east-2'
    def domain = config.domain ?: error('domain is required')
    def namespace = config.namespace ?: 'pmm'

    def creds = getCredentials(namespace)
    def albHostname = sh(
        script: "kubectl get ingress pmm-ha-alb -n ${namespace} -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo 'pending'",
        returnStdout: true
    ).trim()

    sh 'mkdir -p pmm-credentials'
    writeFile file: 'pmm-credentials/access-info.txt', text: """PMM HA Access Information
=========================
Cluster: ${clusterName}
Build:   ${buildNumber}
Region:  ${region}

PMM URL: https://${domain}
ALB:     ${albHostname}

PMM/Grafana Login:
  Username: admin
  Password: ${creds.pmm}

PostgreSQL:
  Password: ${creds.pg}

ClickHouse:
  Username: ${creds.ch_user}
  Password: ${creds.ch}

VictoriaMetrics:
  Username: ${creds.vm_user}
  Password: ${creds.vm}

kubectl access:
  aws eks update-kubeconfig --name ${clusterName} --region ${region}
  kubectl get pods -n ${namespace}
"""
    return [creds: creds, albHostname: albHostname]
}

// ============================================
// 5. EKS CLUSTER SETUP
// ============================================

/**
 * Configure EKS Access Entries for cluster authentication.
 *
 * Grants cluster admin access to:
 * - EKSAdminRole (for automation)
 * - Members of pmm-eks-admins IAM group (dynamically resolved)
 * - SSO AdministratorAccess role (for console users)
 *
 * @param clusterName    EKS cluster name (required)
 * @param region         AWS region (default: us-east-2)
 * @param adminGroupName IAM group for admin access (default: pmm-eks-admins)
 */
def configureAccess(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: 'us-east-2'
    def adminGroupName = config.adminGroupName ?: 'pmm-eks-admins'

    sh """
        set -euo pipefail

        CLUSTER_NAME="${clusterName}"
        REGION="${region}"

        # Helper function to grant cluster admin access to a principal
        grant_cluster_admin() {
            local principal_arn="\$1"
            aws eks create-access-entry \\
                --cluster-name "\${CLUSTER_NAME}" \\
                --region "\${REGION}" \\
                --principal-arn "\${principal_arn}" || true

            aws eks associate-access-policy \\
                --cluster-name "\${CLUSTER_NAME}" \\
                --region "\${REGION}" \\
                --principal-arn "\${principal_arn}" \\
                --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \\
                --access-scope type=cluster || true
        }

        ACCOUNT_ID=\$(aws sts get-caller-identity --query Account --output text)
        echo "AWS Account ID: \${ACCOUNT_ID}"

        # Add EKSAdminRole with cluster admin access
        echo "Adding EKSAdminRole..."
        grant_cluster_admin "arn:aws:iam::\${ACCOUNT_ID}:role/EKSAdminRole"

        # Add IAM group members dynamically
        USERS=\$(aws iam get-group --group-name ${adminGroupName} --query 'Users[].Arn' --output text 2>/dev/null || echo "")
        for USER_ARN in \${USERS}; do
            echo "Adding access for \${USER_ARN}..."
            grant_cluster_admin "\${USER_ARN}"
        done

        # Add SSO AdministratorAccess role (discover dynamically)
        SSO_ROLE_ARN=\$(aws iam list-roles \\
            --query "Roles[?contains(RoleName, 'AWSReservedSSO_AdministratorAccess')].Arn | [0]" \\
            --output text 2>/dev/null | head -1 | tr -d '[:space:]')

        if [ -n "\${SSO_ROLE_ARN}" ] && [ "\${SSO_ROLE_ARN}" != "None" ]; then
            echo "Adding SSO role: \${SSO_ROLE_ARN}"
            grant_cluster_admin "\${SSO_ROLE_ARN}"
        else
            echo "No SSO AdministratorAccess role found, skipping"
        fi

        echo "Access entries configured:"
        aws eks list-access-entries --cluster-name "\${CLUSTER_NAME}" --region "\${REGION}"
    """
}

/**
 * Setup EKS infrastructure components for PMM HA.
 *
 * Installs and configures:
 * - GP3 storage class (encrypted, default)
 * - AWS Node Termination Handler (for spot instance draining)
 * - AWS Load Balancer Controller (for ALB ingress)
 *
 * @param clusterName EKS cluster name (required)
 * @param region      AWS region (default: us-east-2)
 */
def setupInfrastructure(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: 'us-east-2'

    sh """
        set -euo pipefail

        CLUSTER_NAME="${clusterName}"
        REGION="${region}"

        ACCOUNT_ID=\$(aws sts get-caller-identity --query Account --output text)
        echo "AWS Account ID: \${ACCOUNT_ID}"

        # Configure GP3 as default storage class
        kubectl patch storageclass gp2 -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' || true

        cat <<EOF | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: auto-ebs-sc
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  fsType: ext4
  encrypted: "true"
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
EOF

        # Install Node Termination Handler
        helm repo add eks https://aws.github.io/eks-charts || true
        helm repo update

        helm upgrade --install aws-node-termination-handler \\
            eks/aws-node-termination-handler \\
            --namespace kube-system \\
            --set enableSpotInterruptionDraining=true \\
            --set enableScheduledEventDraining=true \\
            --wait

        # Install AWS Load Balancer Controller
        eksctl create iamserviceaccount \\
            --cluster "\${CLUSTER_NAME}" \\
            --namespace kube-system \\
            --name aws-load-balancer-controller \\
            --role-name "AmazonEKSLoadBalancerControllerRole-\${CLUSTER_NAME}" \\
            --attach-policy-arn "arn:aws:iam::\${ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy" \\
            --region "\${REGION}" \\
            --approve || true

        VPC_ID=\$(aws eks describe-cluster --name "\${CLUSTER_NAME}" --region "\${REGION}" \\
            --query "cluster.resourcesVpcConfig.vpcId" --output text)

        helm upgrade --install aws-load-balancer-controller \\
            eks/aws-load-balancer-controller \\
            --namespace kube-system \\
            --set clusterName="\${CLUSTER_NAME}" \\
            --set serviceAccount.create=false \\
            --set serviceAccount.name=aws-load-balancer-controller \\
            --set region="\${REGION}" \\
            --set vpcId="\${VPC_ID}" \\
            --wait

        kubectl wait --for=condition=available deployment/aws-load-balancer-controller \\
            -n kube-system --timeout=120s

        echo "Infrastructure setup complete"
    """
}

// ============================================
// 6. PMM INSTALLATION
// ============================================

/**
 * Install PMM HA stack on the cluster.
 *
 * Deploys:
 * - Helm chart dependencies (VictoriaMetrics, ClickHouse, PostgreSQL operators)
 * - PMM secrets (user-provided or auto-generated passwords)
 * - PMM HA Helm chart
 *
 * Clones percona-helm-charts (or theTibi fork for dev branches).
 *
 * @param namespace     Kubernetes namespace (default: pmm)
 * @param chartBranch   Helm charts git branch (default: main)
 * @param imageTag      PMM Server image tag, empty for chart default (default: '')
 * @param adminPassword PMM admin password, empty for auto-generated (default: '')
 */
def installPmm(Map config) {
    def namespace = config.namespace ?: 'pmm'
    def chartBranch = config.chartBranch ?: 'main'
    def imageTag = config.imageTag ?: ''
    def adminPassword = config.adminPassword ?: ''

    // Clone helm charts using shared function
    def repoSource = cloneHelmCharts(chartBranch)
    writeFile file: '.chart-repo-source', text: repoSource
    echo "Installing PMM HA from ${repoSource}/${chartBranch}"

    sh """
        set -euo pipefail

        PMM_NAMESPACE="${namespace}"
        PMM_IMAGE_TAG="${imageTag}"
        PMM_ADMIN_PASSWORD_INPUT="${adminPassword}"

        # Add required Helm repos
        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
        helm repo add vm https://victoriametrics.github.io/helm-charts/ || true
        helm repo add altinity https://docs.altinity.com/helm-charts/ || true
        helm repo update

        # Install PMM HA dependencies (operators)
        helm dependency update charts-repo/charts/pmm-ha-dependencies
        helm upgrade --install pmm-operators charts-repo/charts/pmm-ha-dependencies \\
            --namespace "\${PMM_NAMESPACE}" \\
            --create-namespace \\
            --wait \\
            --timeout 10m

        echo "Waiting for operators to be ready..."
        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=victoria-metrics-operator -n "\${PMM_NAMESPACE}" --timeout=300s || true
        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=altinity-clickhouse-operator -n "\${PMM_NAMESPACE}" --timeout=300s || true
        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=pg-operator -n "\${PMM_NAMESPACE}" --timeout=300s || true

        # Generate passwords (use provided admin password if set)
        if [ -n "\${PMM_ADMIN_PASSWORD_INPUT}" ]; then
            PMM_ADMIN_PASSWORD="\${PMM_ADMIN_PASSWORD_INPUT}"
            echo "Using user-provided PMM admin password"
        else
            PMM_ADMIN_PASSWORD=\$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16)
            echo "Generated PMM admin password"
        fi
        PG_PASSWORD=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
        GF_PASSWORD=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
        CH_PASSWORD=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
        VM_PASSWORD=\$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)

        # Pre-create pmm-secret before helm install
        # The chart's pg-user-credentials-secrets.yaml uses lookup() at template time
        # GF_SECURITY_ADMIN_PASSWORD is needed because with secret.create=false,
        # the chart doesn't explicitly set this env var (only secretRef is used)
        kubectl create secret generic pmm-secret \\
            --namespace "\${PMM_NAMESPACE}" \\
            --from-literal=PMM_ADMIN_PASSWORD="\${PMM_ADMIN_PASSWORD}" \\
            --from-literal=GF_SECURITY_ADMIN_PASSWORD="\${PMM_ADMIN_PASSWORD}" \\
            --from-literal=PG_PASSWORD="\${PG_PASSWORD}" \\
            --from-literal=GF_PASSWORD="\${GF_PASSWORD}" \\
            --from-literal=PMM_CLICKHOUSE_USER="clickhouse_pmm" \\
            --from-literal=PMM_CLICKHOUSE_PASSWORD="\${CH_PASSWORD}" \\
            --from-literal=VMAGENT_remoteWrite_basicAuth_username="victoriametrics_pmm" \\
            --from-literal=VMAGENT_remoteWrite_basicAuth_password="\${VM_PASSWORD}" \\
            --dry-run=client -o yaml | kubectl apply -f -

        helm dependency update charts-repo/charts/pmm-ha

        HELM_CMD="helm upgrade --install pmm-ha charts-repo/charts/pmm-ha"
        HELM_CMD="\${HELM_CMD} --namespace \${PMM_NAMESPACE}"
        HELM_CMD="\${HELM_CMD} --set secret.create=false"
        HELM_CMD="\${HELM_CMD} --set secret.name=pmm-secret"
        # Increase ClickHouse memory for merge operations (default 4Gi is insufficient)
        HELM_CMD="\${HELM_CMD} --set clickhouse.resources.requests.memory=4Gi"
        HELM_CMD="\${HELM_CMD} --set clickhouse.resources.limits.memory=10Gi"
        if [ -n "\${PMM_IMAGE_TAG}" ]; then
            HELM_CMD="\${HELM_CMD} --set image.tag=\${PMM_IMAGE_TAG}"
        fi
        HELM_CMD="\${HELM_CMD} --wait --timeout 15m"

        eval "\${HELM_CMD}"

        echo "Waiting for PMM HA components..."
        kubectl rollout status statefulset/pmm-ha -n "\${PMM_NAMESPACE}" --timeout=600s || true
        kubectl wait --for=condition=ready pod -l clickhouse.altinity.com/chi=pmm-ha -n "\${PMM_NAMESPACE}" --timeout=600s || true
        kubectl wait --for=condition=ready pod -l app.kubernetes.io/component=vmselect -n "\${PMM_NAMESPACE}" --timeout=300s || true
        kubectl wait --for=condition=ready pod -l app.kubernetes.io/component=vmstorage -n "\${PMM_NAMESPACE}" --timeout=300s || true

        echo "PMM HA installed"
        kubectl get pods -n "\${PMM_NAMESPACE}"
    """
}

/**
 * Create ALB Ingress and Route53 DNS record for PMM HA.
 *
 * Creates:
 * - ALB Ingress with ACM certificate (HTTPS)
 * - Route53 alias record pointing to ALB
 *
 * Waits up to 5 minutes for ALB provisioning.
 *
 * @param namespace   Kubernetes namespace (default: pmm)
 * @param domain      FQDN for PMM access (required)
 * @param certArn     ACM certificate ARN for TLS (required)
 * @param r53ZoneName Route53 hosted zone name (required, e.g., cd.percona.com)
 * @param region      AWS region (default: us-east-2)
 */
def createIngress(Map config) {
    def namespace = config.namespace ?: 'pmm'
    def domain = config.domain ?: error('domain is required')
    def certArn = config.certArn ?: error('certArn is required')
    def r53ZoneName = config.r53ZoneName ?: error('r53ZoneName is required')
    def region = config.region ?: 'us-east-2'

    def r53ZoneId = resolveR53ZoneId(r53ZoneName, region)
    if (!r53ZoneId) {
        error("No public Route53 zone found for ${r53ZoneName}")
    }

    sh """
        set -euo pipefail

        PMM_NAMESPACE="${namespace}"
        PMM_DOMAIN="${domain}"
        ACM_CERT_ARN="${certArn}"
        R53_ZONE_ID="${r53ZoneId}"
        REGION="${region}"

        # Create ALB Ingress
        cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: pmm-ha-alb
  namespace: \${PMM_NAMESPACE}
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/certificate-arn: \${ACM_CERT_ARN}
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
    alb.ingress.kubernetes.io/backend-protocol: HTTPS
    alb.ingress.kubernetes.io/healthcheck-protocol: HTTPS
    alb.ingress.kubernetes.io/healthcheck-path: /v1/readyz
    alb.ingress.kubernetes.io/healthcheck-port: "443"
    alb.ingress.kubernetes.io/success-codes: "200"
spec:
  ingressClassName: alb
  rules:
    - host: \${PMM_DOMAIN}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: pmm-ha-haproxy
                port:
                  number: 443
EOF

        echo "Waiting for ALB to be provisioned..."
        ALB_HOSTNAME=""
        for attempt in \$(seq 1 30); do
            ALB_HOSTNAME=\$(kubectl get ingress pmm-ha-alb -n "\${PMM_NAMESPACE}" \\
                -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "")
            if [ -n "\${ALB_HOSTNAME}" ]; then
                echo "ALB provisioned: \${ALB_HOSTNAME}"
                break
            fi
            echo "Waiting for ALB... (\${attempt}/30)"
            sleep 10
        done

        if [ -z "\${ALB_HOSTNAME}" ]; then
            echo "WARNING: ALB not provisioned within timeout"
            kubectl describe ingress pmm-ha-alb -n "\${PMM_NAMESPACE}"
            exit 1
        fi

        ALB_ZONE_ID=\$(aws elbv2 describe-load-balancers --region "\${REGION}" \\
            --query "LoadBalancers[?DNSName=='\${ALB_HOSTNAME}'].CanonicalHostedZoneId" \\
            --output text)

        if [ -n "\${ALB_ZONE_ID}" ]; then
            aws route53 change-resource-record-sets \\
                --hosted-zone-id "\${R53_ZONE_ID}" \\
                --change-batch '{
                    "Changes": [{
                        "Action": "UPSERT",
                        "ResourceRecordSet": {
                            "Name": "'"\${PMM_DOMAIN}"'",
                            "Type": "A",
                            "AliasTarget": {
                                "HostedZoneId": "'"\${ALB_ZONE_ID}"'",
                                "DNSName": "'"\${ALB_HOSTNAME}"'",
                                "EvaluateTargetHealth": true
                            }
                        }
                    }]
                }'
            echo "Route53 record created: \${PMM_DOMAIN} -> \${ALB_HOSTNAME}"
        else
            echo "WARNING: Could not get ALB zone ID, skipping Route53 record"
        fi
    """
}

// ============================================
// 7. CLUSTER LIFECYCLE
// ============================================

/**
 * List PMM HA test clusters sorted by creation time (newest first).
 *
 * @param region AWS region (default: us-east-2)
 * @return List of cluster names sorted newest first, empty list if none found
 */
def listClusters(String region = 'us-east-2') {
    def output = sh(
        script: """
            aws eks list-clusters --region ${region} --output json 2>/dev/null | \\
                jq -r '.clusters[] | select(startswith("${CLUSTER_PREFIX}"))' | \\
                while read cluster; do
                    CREATED=\$(aws eks describe-cluster --name "\$cluster" --region ${region} \\
                        --query 'cluster.createdAt' --output text 2>/dev/null)
                    [ -n "\$CREATED" ] && [ "\$CREATED" != "None" ] && echo "\$CREATED|\$cluster"
                done | sort -r | cut -d'|' -f2
        """,
        returnStdout: true
    ).trim()

    if (!output) {
        return []
    }

    return output.split('\n').findAll { it }
}

/**
 * Delete PMM HA EKS cluster and all associated AWS resources.
 *
 * Cleanup order (to avoid dependency errors):
 * 1. Route53 alias record
 * 2. ALB Ingress (triggers ALB deletion)
 * 3. EKS cluster via eksctl
 *
 * @param clusterName  EKS cluster name (required)
 * @param region       AWS region (default: us-east-2)
 * @param r53ZoneName  Route53 hosted zone name (default: cd.percona.com)
 */
def deleteCluster(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: 'us-east-2'
    def r53ZoneName = config.r53ZoneName ?: 'cd.percona.com'

    def r53ZoneId = resolveR53ZoneId(r53ZoneName, region)

    sh """
        set -euo pipefail

        cluster_name="${clusterName}"
        REGION="${region}"
        R53_ZONE_ID="${r53ZoneId}"
        R53_ZONE_NAME="${r53ZoneName}"

        echo "============================================"
        echo "Cleaning up cluster: \${cluster_name}"
        echo "============================================"

        # Delete Route53 record (if zone was resolved)
        domain_name="\${cluster_name}.\${R53_ZONE_NAME}"
        if [ -n "\${R53_ZONE_ID}" ]; then
            echo "Deleting Route53 record for \${domain_name}..."
            record=\$(aws route53 list-resource-record-sets \\
                --hosted-zone-id "\${R53_ZONE_ID}" \\
                --query "ResourceRecordSets[?Name=='\${domain_name}.']" \\
                --output json 2>/dev/null || echo "[]")

            if [ "\${record}" != "[]" ] && [ -n "\${record}" ]; then
                record_type=\$(echo "\${record}" | jq -r '.[0].Type')
                if [ "\${record_type}" = "A" ]; then
                    alias_target=\$(echo "\${record}" | jq -r '.[0].AliasTarget')
                    aws route53 change-resource-record-sets \\
                        --hosted-zone-id "\${R53_ZONE_ID}" \\
                        --change-batch '{
                            "Changes": [{
                                "Action": "DELETE",
                                "ResourceRecordSet": {
                                    "Name": "'"\${domain_name}"'",
                                    "Type": "A",
                                    "AliasTarget": '"\${alias_target}"'
                                }
                            }]
                        }' && echo "Route53 record deleted" || echo "Warning: Failed to delete Route53 record"
                fi
            else
                echo "No Route53 record found for \${domain_name}"
            fi
        else
            echo "Skipping Route53 record deletion (zone not resolved)"
        fi

        # Delete ALB ingress (triggers ALB deletion)
        # Use per-cluster kubeconfig to avoid race conditions during parallel deletions
        echo "Deleting ALB ingress..."
        TEMP_KUBECONFIG="\$(mktemp)"
        if aws eks update-kubeconfig --name "\${cluster_name}" --region "\${REGION}" --kubeconfig "\${TEMP_KUBECONFIG}" 2>/dev/null; then
            KUBECONFIG="\${TEMP_KUBECONFIG}" kubectl delete ingress pmm-ha-alb -n pmm --ignore-not-found=true || true
        fi
        rm -f "\${TEMP_KUBECONFIG}"

        # Wait for ALB cleanup
        echo "Waiting for ALB cleanup..."
        sleep 30

        # Disable termination protection on all CloudFormation stacks for this cluster
        echo "Disabling termination protection on CloudFormation stacks..."
        for stack_name in \$(aws cloudformation list-stacks --region "\${REGION}" \\
            --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \\
            --query "StackSummaries[?starts_with(StackName, 'eksctl-\${cluster_name}')].StackName" \\
            --output text 2>/dev/null); do
            echo "  Disabling protection: \${stack_name}"
            aws cloudformation update-termination-protection \\
                --region "\${REGION}" \\
                --stack-name "\${stack_name}" \\
                --no-enable-termination-protection 2>/dev/null || true
        done

        echo "Deleting EKS cluster \${cluster_name}..."
        eksctl delete cluster --region "\${REGION}" --name "\${cluster_name}" \\
            --disable-nodegroup-eviction --wait
    """
}

/**
 * Delete multiple clusters with optional SKIP_NEWEST and retention-aware filtering.
 *
 * Deletion Rules:
 *   Cluster Type                  | Action
 *   ------------------------------|------------------------------
 *   Unexpired delete-after tag    | KEEP (unless respectRetention=false)
 *   Expired delete-after tag      | DELETE
 *   No delete-after tag           | DELETE
 *   Newest (skipNewest=true)      | SKIP
 *
 * @param region           AWS region (default: us-east-2)
 * @param r53ZoneName      Route53 hosted zone name (default: cd.percona.com)
 * @param skipNewest       Skip the most recent cluster (default: true)
 * @param respectRetention Respect delete-after tags; false = force delete (default: true)
 */
def deleteAllClusters(Map config = [:]) {
    def region = config.region ?: 'us-east-2'
    def r53ZoneName = config.r53ZoneName ?: 'cd.percona.com'
    def skipNewest = config.skipNewest != null ? config.skipNewest : true
    def respectRetention = config.respectRetention != null ? config.respectRetention : true

    def clusterList = listClusters(region)

    if (!clusterList) {
        echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
        return
    }

    def clustersToDelete = clusterList
    if (skipNewest) {
        clustersToDelete = clusterList.drop(1)
        echo "Skipping newest cluster: ${clusterList[0]} (SKIP_NEWEST=true)"
    }

    // Filter by retention tags
    if (respectRetention) {
        def nowEpoch = (long)(System.currentTimeMillis() / 1000)
        def filtered = []

        clustersToDelete.each { clusterName ->
            def deleteAfterTag = sh(
                script: """
                    aws eks describe-cluster --name ${clusterName} --region ${region} \\
                        --query 'cluster.tags."delete-after"' --output text 2>/dev/null || echo ''
                """,
                returnStdout: true
            ).trim()

            if (deleteAfterTag && deleteAfterTag != 'None' && deleteAfterTag != 'null' && deleteAfterTag != '') {
                // Cluster has retention tag - check if expired
                def deleteAfterEpoch = deleteAfterTag as long
                if (nowEpoch > deleteAfterEpoch) {
                    echo "DELETE: ${clusterName} - retention expired (delete-after: ${deleteAfterEpoch})"
                    filtered.add(clusterName)
                } else if (respectRetention) {
                    def hoursLeft = (int)((deleteAfterEpoch - nowEpoch) / 3600)
                    echo "KEEP: ${clusterName} - ${hoursLeft}h retention remaining"
                } else {
                    echo "DELETE: ${clusterName} - retention override (respectRetention=false)"
                    filtered.add(clusterName)
                }
            } else {
                // No delete-after tag - delete immediately
                echo "DELETE: ${clusterName} - no retention tag"
                filtered.add(clusterName)
            }
        }
        clustersToDelete = filtered
    }

    if (!clustersToDelete) {
        echo 'No clusters to delete after applying filters.'
        return
    }

    // Delete clusters in parallel
    def parallelStages = [:]
    clustersToDelete.each { clusterName ->
        parallelStages["Delete ${clusterName}"] = {
            deleteCluster(
                clusterName: clusterName,
                region: region,
                r53ZoneName: r53ZoneName
            )
        }
    }
    parallel parallelStages
}

/**
 * Clean up orphaned VPCs and failed CloudFormation stacks.
 *
 * Finds:
 * - VPCs tagged with purpose=pmm-ha-testing but no matching EKS cluster
 * - CloudFormation stacks in DELETE_FAILED or ROLLBACK_COMPLETE state
 *
 * @param region AWS region (default: us-east-2)
 */
def cleanupOrphans(Map config = [:]) {
    def region = config.region ?: 'us-east-2'

    // Get list of active EKS clusters
    def activeClusters = sh(
        script: """
            aws eks list-clusters --region ${region} \\
                --query "clusters[?starts_with(@, '${CLUSTER_PREFIX}')]" \\
                --output text 2>/dev/null || echo ''
        """,
        returnStdout: true
    ).trim().split(/\s+/).findAll { it }

    echo "Active EKS clusters: ${activeClusters}"

    // Find orphaned VPCs by tag (more reliable than name pattern matching).
    // VPCs inherit tags from eksctl cluster config. When the EKS cluster is deleted
    // via AWS console or eksctl fails midway, the VPC and CF stacks remain.
    // We extract cluster name from the Name tag and use eksctl to clean up.
    def orphanedVpcs = sh(
        script: """
            aws ec2 describe-vpcs --region ${region} \\
                --filters "Name=tag:purpose,Values=pmm-ha-testing" \\
                --query 'Vpcs[*].[VpcId,Tags[?Key==`Name`].Value|[0]]' \\
                --output text 2>/dev/null || echo ''
        """,
        returnStdout: true
    ).trim()

    if (orphanedVpcs) {
        orphanedVpcs.split('\n').each { line ->
            def parts = line.split('\t')
            if (parts.size() >= 2) {
                def vpcId = parts[0]
                def vpcName = parts[1] ?: ''
                // Extract cluster name from VPC name (eksctl-pmm-ha-test-XX-cluster/VPC)
                def matcher = vpcName =~ /eksctl-(${CLUSTER_PREFIX}\d+)-cluster/
                if (matcher) {
                    def clusterName = matcher[0][1]
                    if (!activeClusters.contains(clusterName)) {
                        echo "Found orphaned VPC: ${vpcId} (${vpcName}) - cluster ${clusterName} does not exist"
                        sh """
                            eksctl delete cluster --name ${clusterName} --region ${region} --wait=false 2>/dev/null || true
                        """
                    }
                }
            }
        }
    } else {
        echo 'No orphaned VPCs found.'
    }

    // Find and delete failed CloudFormation stacks
    def failedStacks = sh(
        script: """
            aws cloudformation list-stacks --region ${region} \\
                --stack-status-filter DELETE_FAILED ROLLBACK_COMPLETE \\
                --query "StackSummaries[?contains(StackName, '${CLUSTER_PREFIX}')].StackName" \\
                --output text 2>/dev/null || echo ''
        """,
        returnStdout: true
    ).trim()

    if (failedStacks) {
        failedStacks.split(/\s+/).each { stackName ->
            echo "Deleting failed stack: ${stackName}"
            sh "aws cloudformation delete-stack --region ${region} --stack-name ${stackName} || true"
        }
    } else {
        echo 'No failed CloudFormation stacks found.'
    }
}
