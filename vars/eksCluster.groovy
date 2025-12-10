// EKS Cluster Operations Library
// Generic functions for EKS cluster management and lifecycle.
// Product-agnostic - usable by PMM, PXC, PSMDB, or any team needing EKS clusters.
import groovy.transform.Field

@Field static final int DEFAULT_MAX_RETENTION_DAYS = 7
@Field static final String DEFAULT_REGION = 'us-east-2'

// === CLUSTER SETUP ===

/**
 * Clamp retention days to valid range (1 to maxDays)
 * @param retentionDays Input value (any type, safely converted)
 * @param maxDays Maximum allowed days (default: DEFAULT_MAX_RETENTION_DAYS)
 * @return int between 1 and maxDays
 */
def validateRetentionDays(def retentionDays, int maxDays = DEFAULT_MAX_RETENTION_DAYS) {
    def days = 1
    try { days = retentionDays ? (retentionDays.toString() as int) : 1 } catch (Exception e) { days = 1 }
    return Math.max(1, Math.min(days, maxDays))
}

/**
 * Grant cluster admin access to specified principals
 * @param config.clusterName    EKS cluster name (required)
 * @param config.region         AWS region (default: us-east-2)
 * @param config.adminRoles     List of IAM role names to grant admin access
 * @param config.adminUsers     List of IAM user names to grant admin access
 * @param config.adminGroupName IAM group whose members get admin access
 * @param config.discoverSSO    Auto-discover and grant access to SSO admin role (default: false)
 * @param config.ssoPattern     Pattern to match SSO role name (default: 'AWSReservedSSO_AdministratorAccess')
 */
def configureAccess(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION
    def adminRoles = config.adminRoles ?: []
    def adminUsers = config.adminUsers ?: []
    def adminGroupName = config.adminGroupName ?: ''
    def discoverSSO = config.discoverSSO ?: false
    def ssoPattern = config.ssoPattern ?: 'AWSReservedSSO_AdministratorAccess'

    def rolesArg = adminRoles.join(' ')
    def usersArg = adminUsers.join(' ')

    sh """
        set -euo pipefail
        CLUSTER="${clusterName}" REGION="${region}"
        grant_admin() {
            local arn="\$1"
            echo "Granting admin access to: \${arn}"
            aws eks create-access-entry --cluster-name "\${CLUSTER}" --region "\${REGION}" --principal-arn "\${arn}" 2>/dev/null || echo "  (entry may already exist)"
            aws eks associate-access-policy --cluster-name "\${CLUSTER}" --region "\${REGION}" --principal-arn "\${arn}" \\
                --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy --access-scope type=cluster 2>/dev/null || echo "  (policy may already be associated)"
        }
        ACCOUNT=\$(aws sts get-caller-identity --query Account --output text)

        # Grant access to specified roles
        for role in ${rolesArg}; do
            [ -n "\${role}" ] && grant_admin "arn:aws:iam::\${ACCOUNT}:role/\${role}"
        done

        # Grant access to specified users
        for user in ${usersArg}; do
            [ -n "\${user}" ] && grant_admin "arn:aws:iam::\${ACCOUNT}:user/\${user}"
        done

        # Grant access to IAM group members
        if [ -n "${adminGroupName}" ]; then
            for arn in \$(aws iam get-group --group-name "${adminGroupName}" --query 'Users[].Arn' --output text 2>/dev/null); do
                grant_admin "\${arn}"
            done
        fi

        # Discover and grant access to SSO admin role
        if [ "${discoverSSO}" = "true" ]; then
            SSO=\$(aws iam list-roles --query "Roles[?contains(RoleName,'${ssoPattern}')].Arn|[0]" --output text 2>/dev/null | head -1)
            [ -n "\${SSO}" ] && [ "\${SSO}" != "None" ] && grant_admin "\${SSO}"
        fi
    """
}

/**
 * Install cluster components: GP3 StorageClass, Node Termination Handler
 * @param config.clusterName       EKS cluster name (required)
 * @param config.region            AWS region (default: us-east-2)
 * @param config.installStorageClass Install GP3 StorageClass (default: true)
 * @param config.installNTH        Install Node Termination Handler (default: true)
 * @param config.storageClassName  StorageClass name (default: auto-ebs-sc)
 */
def setupClusterComponents(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION
    def installStorageClass = config.installStorageClass != false  // default: true
    def installNTH = config.installNTH != false                    // default: true
    def storageClassName = config.storageClassName ?: 'auto-ebs-sc'

    if (installStorageClass) {
        sh """
            kubectl patch storageclass gp2 -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' || true
            cat <<EOF | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: ${storageClassName}
  annotations: { storageclass.kubernetes.io/is-default-class: "true" }
provisioner: ebs.csi.aws.com
parameters: { type: gp3, fsType: ext4, encrypted: "true" }
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
EOF
        """
    }
    if (installNTH) {
        sh '''
            helm repo add eks https://aws.github.io/eks-charts || true && helm repo update
            helm upgrade --install aws-node-termination-handler eks/aws-node-termination-handler -n kube-system \\
                --set enableSpotInterruptionDraining=true --set enableScheduledEventDraining=true --wait
        '''
    }
}

// === CLUSTER LIFECYCLE ===

/**
 * Check if a cluster exists
 * @param config.clusterName EKS cluster name (required)
 * @param config.region AWS region (default: us-east-2)
 * @return true if cluster exists
 */
def clusterExists(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION
    return sh(script: "aws eks describe-cluster --region ${region} --name ${clusterName} >/dev/null 2>&1", returnStatus: true) == 0
}

/**
 * Create cluster from config file
 * @param config.configFile Path to eksctl config file (required)
 * @param config.timeout Cluster creation timeout (default: 40m)
 */
def createCluster(Map config) {
    def configFile = config.configFile ?: error('configFile is required')
    def timeout = config.timeout ?: '40m'
    sh "eksctl create cluster -f ${configFile} --timeout=${timeout} --verbose=4"
}

/**
 * Export kubeconfig for cluster
 * @param config.clusterName EKS cluster name (required)
 * @param config.region AWS region (default: us-east-2)
 * @param config.kubeconfigPath Path to write kubeconfig (required)
 */
def exportKubeconfig(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION
    def kubeconfigPath = config.kubeconfigPath ?: error('kubeconfigPath is required')
    sh """
        rm -rf ${kubeconfigPath}
        aws eks update-kubeconfig --name ${clusterName} --region ${region} --kubeconfig ${kubeconfigPath}
    """
}

/**
 * List clusters matching prefix, sorted by creation time (newest first)
 * @param config.region AWS region (default: us-east-2)
 * @param config.prefix Cluster name prefix (required)
 * @return List of cluster names
 */
def listClusters(Map config) {
    def region = config.region ?: DEFAULT_REGION
    def prefix = config.prefix ?: error('prefix is required')
    // Fetch clusters with creation timestamp, sort newest first
    def output = sh(script: """
        aws eks list-clusters --region ${region} --output json | jq -r '.clusters[]|select(startswith("${prefix}"))' | while read c; do
            t=\$(aws eks describe-cluster --name "\$c" --region ${region} --query 'cluster.createdAt' --output text 2>/dev/null)
            [ -n "\$t" ] && [ "\$t" != "None" ] && echo "\$t|\$c"
        done | sort -r | cut -d'|' -f2
    """, returnStdout: true).trim()
    return output ? output.split('\n').findAll { it } : []
}

/**
 * List clusters with age information (for display purposes)
 * @param config.region AWS region (default: us-east-2)
 * @param config.prefix Cluster name prefix (required)
 */
def listClustersWithAge(Map config) {
    def region = config.region ?: DEFAULT_REGION
    def prefix = config.prefix ?: error('prefix is required')

    def clusters = listClusters(region: region, prefix: prefix)
    if (!clusters) {
        echo "No clusters found with prefix '${prefix}'."
        return
    }

    echo "Found ${clusters.size()} cluster(s):"
    clusters.each { clusterName ->
        def info = sh(script: """
            CREATED=\$(aws eks describe-cluster --name ${clusterName} --region ${region} --query 'cluster.createdAt' --output text)
            CREATED_EPOCH=\$(date -d "\${CREATED}" +%s)
            AGE_HOURS=\$(( ( \$(date +%s) - CREATED_EPOCH ) / 3600 ))
            echo "\${CREATED}|\${AGE_HOURS}"
        """, returnStdout: true).trim()
        def parts = info.split('\\|')
        echo "* ${clusterName} | Created: ${parts[0]} | Age: ${parts[1]}h"
    }
}

/**
 * Filter clusters by retention tag expiration
 * @param clusters List of cluster names to check
 * @param region AWS region
 * @param verbose Print DELETE/KEEP status for each cluster (default: true)
 * @return List of clusters eligible for deletion (expired or missing tag)
 */
def filterByRetention(List clusters, String region, boolean verbose = true) {
    def now = (long)(System.currentTimeMillis() / 1000)
    def filtered = []
    clusters.each { c ->
        def tag = sh(script: "aws eks describe-cluster --name ${c} --region ${region} --query 'cluster.tags.\"delete-after\"' --output text 2>/dev/null || echo ''", returnStdout: true).trim()
        if (tag && tag != 'None' && tag != 'null' && tag != '') {
            def exp = tag as long
            if (now > exp) {
                if (verbose) { echo "DELETE: ${c} - expired" }
                filtered.add(c)
            } else if (verbose) {
                echo "KEEP: ${c} - ${(int)((exp - now) / 3600)}h remaining"
            }
        } else {
            if (verbose) { echo "DELETE: ${c} - no retention tag" }
            filtered.add(c)
        }
    }
    return filtered
}

/**
 * Delete cluster via eksctl
 * @param config.clusterName      EKS cluster name (required)
 * @param config.region           AWS region (default: us-east-2)
 */
def deleteCluster(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION

    sh """
        set -euo pipefail
        CLUSTER="${clusterName}" REGION="${region}"
        # Disable termination protection on eksctl stacks (required before deletion)
        for s in \$(aws cloudformation list-stacks --region "\${REGION}" --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \\
            --query "StackSummaries[?starts_with(StackName,'eksctl-\${CLUSTER}')].StackName" --output text 2>/dev/null); do
            aws cloudformation update-termination-protection --region "\${REGION}" --stack-name "\${s}" --no-enable-termination-protection 2>/dev/null || true
        done
        eksctl delete cluster --region "\${REGION}" --name "\${CLUSTER}" --disable-nodegroup-eviction --wait
    """
}

