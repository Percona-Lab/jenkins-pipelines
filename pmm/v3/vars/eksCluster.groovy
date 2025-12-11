// EKS Cluster Operations Library - PMM-scoped
import groovy.transform.Field

@Field static final String DEFAULT_REGION = 'us-east-2'

/** Grant EKS admin access via Access Entries API
 *  @param clusterName, region, adminRoles, adminUsers, adminGroupName, discoverSSO, ssoPattern */
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

    withEnv([
        "CLUSTER=${clusterName}",
        "REGION=${region}",
        "ROLES_ARG=${rolesArg}",
        "USERS_ARG=${usersArg}",
        "ADMIN_GROUP_NAME=${adminGroupName}",
        "DISCOVER_SSO=${discoverSSO}",
        "SSO_PATTERN=${ssoPattern}"
    ]) {
        sh '''
            set -euo pipefail
            grant_admin() {
                local arn="$1"
                echo "Granting admin access to: ${arn}"
                aws eks create-access-entry --cluster-name "${CLUSTER}" --region "${REGION}" --principal-arn "${arn}" 2>/dev/null || echo "  (entry may already exist)"
                aws eks associate-access-policy --cluster-name "${CLUSTER}" --region "${REGION}" --principal-arn "${arn}" \
                    --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy --access-scope type=cluster 2>/dev/null || echo "  (policy may already be associated)"
            }
            ACCOUNT=$(aws sts get-caller-identity --query Account --output text)

            # Grant access to specified roles
            for role in ${ROLES_ARG:-}; do
                [ -n "${role}" ] && grant_admin "arn:aws:iam::${ACCOUNT}:role/${role}"
            done

            # Grant access to specified users
            for user in ${USERS_ARG:-}; do
                [ -n "${user}" ] && grant_admin "arn:aws:iam::${ACCOUNT}:user/${user}"
            done

            # Grant access to IAM group members
            if [ -n "${ADMIN_GROUP_NAME:-}" ]; then
                GROUP_MEMBERS=$(aws iam get-group --group-name "${ADMIN_GROUP_NAME}" --query 'Users[].Arn' --output text) || true
                for arn in ${GROUP_MEMBERS:-}; do
                    grant_admin "${arn}"
                done
            fi

            # Discover and grant access to SSO admin role
            if [ "${DISCOVER_SSO}" = "true" ]; then
                SSO=$(aws iam list-roles --query "Roles[?contains(RoleName,'${SSO_PATTERN}')].Arn|[0]" --output text | head -1) || true
                [ -n "${SSO}" ] && [ "${SSO}" != "None" ] && grant_admin "${SSO}"
            fi
        '''
    }
}

/** Setup GP3 StorageClass and Spot NodePool for EKS Auto Mode
 *  Uses current kubeconfig context (set by caller) */
def setupClusterComponents() {
    // Note: EKS Auto Mode doesn't create any default StorageClass, so we create one
    sh '''
        # Configure GP3 as default storage for persistent volume claims
        cat <<'EOF' | kubectl apply -f -
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: auto-ebs-sc
  annotations:
    storageclass.kubernetes.io/is-default-class: "true"
allowedTopologies:
  - matchLabelExpressions:
      - key: eks.amazonaws.com/compute-type
        values:
          - auto
provisioner: ebs.csi.eks.amazonaws.com
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
parameters:
  type: gp3
  encrypted: "true"
EOF

            # Configure Spot instances via Karpenter (70% cost savings vs on-demand)
            cat <<EOF | kubectl apply -f -
apiVersion: karpenter.sh/v1
kind: NodePool
metadata:
  name: spot-pool
spec:
  template:
    spec:
      nodeClassRef:
        group: eks.amazonaws.com
        kind: NodeClass
        name: default
      requirements:
        - key: eks.amazonaws.com/instance-category
          operator: In
          values: ["m", "c", "r"]
        - key: eks.amazonaws.com/instance-generation
          operator: Gt
          values: ["4"]
        - key: eks.amazonaws.com/instance-cpu
          operator: In
          values: ["4", "8", "16"]
        - key: kubernetes.io/arch
          operator: In
          values: ["amd64"]
        - key: karpenter.sh/capacity-type
          operator: In
          values: ["spot"]
  disruption:
    consolidationPolicy: WhenEmptyOrUnderutilized
    consolidateAfter: 1m
  limits:
    cpu: 100
    memory: 200Gi
EOF
    '''
}

/** Check if cluster exists
 *  @param clusterName, region */
def clusterExists(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION

    withEnv([
        "CLUSTER_NAME=${clusterName}",
        "REGION=${region}"
    ]) {
        return sh(script: 'aws eks describe-cluster --region "${REGION}" --name "${CLUSTER_NAME}" >/dev/null 2>&1', returnStatus: true) == 0
    }
}

/** Create cluster from eksctl config file
 *  @param configFile, timeout */
def createCluster(Map config) {
    def configFile = config.configFile ?: error('configFile is required')
    def timeout = config.timeout ?: '40m'

    withEnv([
        "CONFIG_FILE=${configFile}",
        "TIMEOUT=${timeout}"
    ]) {
        sh 'eksctl create cluster -f "${CONFIG_FILE}" --timeout=${TIMEOUT} --verbose=4'
    }
}

/** Export kubeconfig for cluster
 *  @param clusterName, region, kubeconfigPath */
def exportKubeconfig(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION
    def kubeconfigPath = config.kubeconfigPath ?: error('kubeconfigPath is required')

    withEnv([
        "CLUSTER_NAME=${clusterName}",
        "REGION=${region}",
        "KUBECONFIG_PATH=${kubeconfigPath}"
    ]) {
        sh '''
            rm -rf "${KUBECONFIG_PATH}"
            aws eks update-kubeconfig --name "${CLUSTER_NAME}" --region "${REGION}" --kubeconfig "${KUBECONFIG_PATH}"
        '''
    }
}

/** List clusters by prefix, sorted newest first (always shows age info)
 *  @param region, prefix */
def listClusters(Map config) {
    def region = config.region ?: DEFAULT_REGION
    def prefix = config.prefix ?: error('prefix is required')
    def output

    withEnv([
        "REGION=${region}",
        "PREFIX=${prefix}"
    ]) {
        output = sh(script: '''
            aws eks list-clusters --region "${REGION}" --output json | jq -r '.clusters[]|select(startswith("'"${PREFIX}"'"))' | while read c; do
                t=$(aws eks describe-cluster --name "$c" --region "${REGION}" --query 'cluster.createdAt' --output text) || continue
                [ -n "$t" ] && [ "$t" != "None" ] && echo "$t|$c"
            done | sort -r | cut -d'|' -f2
        ''', returnStdout: true).trim()
    }

    def clusters = output ? output.split('\n').findAll { it } : []
    if (clusters) {
        echo "Found ${clusters.size()} cluster(s):"
        clusters.each { clusterName ->
            def info
            withEnv([
                "CLUSTER_NAME=${clusterName}",
                "REGION=${region}"
            ]) {
                info = sh(script: '''
                    CREATED=$(aws eks describe-cluster --name "${CLUSTER_NAME}" --region "${REGION}" --query 'cluster.createdAt' --output text)
                    CREATED_EPOCH=$(date -d "${CREATED}" +%s)
                    AGE_HOURS=$(( ( $(date +%s) - CREATED_EPOCH ) / 3600 ))
                    echo "${CREATED}|${AGE_HOURS}"
                ''', returnStdout: true).trim()
            }
            def parts = info.split('\\|')
            echo "* ${clusterName} | Created: ${parts[0]} | Age: ${parts[1]}h"
        }
    } else {
        echo "No clusters found with prefix '${prefix}'."
    }

    return clusters
}

/** Delete cluster via eksctl
 *  @param clusterName, region */
def deleteCluster(Map config) {
    def clusterName = config.clusterName ?: error('clusterName is required')
    def region = config.region ?: DEFAULT_REGION

    withEnv([
        "CLUSTER=${clusterName}",
        "REGION=${region}"
    ]) {
        sh '''
            set -euo pipefail

            # Disable termination protection on eksctl stacks (best-effort, may not exist)
            STACKS=$(aws cloudformation list-stacks --region "${REGION}" \
                --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \
                --query "StackSummaries[?starts_with(StackName,'eksctl-${CLUSTER}')].StackName" \
                --output text) || true

            for s in ${STACKS}; do
                echo "Disabling termination protection on: ${s}"
                aws cloudformation update-termination-protection \
                    --region "${REGION}" --stack-name "${s}" \
                    --no-enable-termination-protection || true
            done

            eksctl delete cluster --region "${REGION}" --name "${CLUSTER}" --disable-nodegroup-eviction --wait
        '''
    }
}
