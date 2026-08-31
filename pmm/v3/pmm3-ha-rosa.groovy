library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

def notifySlack(String color, String message) {
    if (!params.NOTIFY) {
        return
    }

    def text = "[${env.JOB_NAME}]: ${message}\nCluster: ${env.CLUSTER_NAME ?: 'not created'} | Owner: @${env.OWNER ?: 'unknown'} | Build: ${env.BUILD_URL}"

    slackSend botUser: true, channel: '#pmm-notifications', color: color, message: text
}

// Writes ${WORKSPACE}/kubeconfig with a ServiceAccount token inside it, replacing the OAuth token
// from "oc login" that expires after 24h. This one never expires, so it lasts as long as the cluster.
def mintAdminKubeconfig() {
    sh '''
        set +x   # Jenkins runs sh with -xe; keep the token out of the console

        MINT_OUT="${WORKSPACE}/kubeconfig"

        kubectl -n kube-system create serviceaccount pmm-ha-admin
        kubectl create clusterrolebinding pmm-ha-admin \
            --clusterrole=cluster-admin \
            --serviceaccount=kube-system:pmm-ha-admin

        # Unlike "kubectl create token", the token in this Secret never expires.
        kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: pmm-ha-admin-token
  namespace: kube-system
  annotations:
    kubernetes.io/service-account.name: pmm-ha-admin
type: kubernetes.io/service-account-token
EOF

        echo "Waiting for the token controller to populate the secret..."
        for i in $(seq 1 30); do
            TOKEN=$(kubectl -n kube-system get secret pmm-ha-admin-token -o jsonpath='{.data.token}' | base64 -d)
            if [ -n "${TOKEN}" ]; then
                break
            fi
            sleep 2
        done

        if [ -z "${TOKEN}" ]; then
            echo "ERROR: the token secret was not populated after 60 seconds."
            exit 1
        fi

        # Copy the working config so the server URL and TLS settings carry over as-is.
        kubectl config view --raw --minify --flatten > "${MINT_OUT}"

        # Swap the copied credential for the ServiceAccount token
        kubectl --kubeconfig "${MINT_OUT}" config unset users
        kubectl --kubeconfig "${MINT_OUT}" config set-credentials pmm-ha-admin --token="${TOKEN}"
        kubectl --kubeconfig "${MINT_OUT}" config set-context --current --user=pmm-ha-admin
        chmod 600 "${MINT_OUT}"

        # Nothing else in this build touches the published file, so this is its only test.
        kubectl --kubeconfig "${MINT_OUT}" get nodes
    '''
}

def cleanupCluster() {
    withCredentials([aws(credentialsId: 'pmm-staging-slave'),
                     string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')]) {
        sh '''
            export AWS_RETRY_MODE=adaptive
            export AWS_MAX_ATTEMPTS=10

            rosa login --token="${ROSA_TOKEN}"

            # Read before the delete below, while the cluster still exists. Empty if it never did.
            CLUSTER_ID=$(rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" \
                -o json 2>/dev/null | jq -r '.id // empty')

            if rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" >/dev/null 2>&1; then
                rosa delete cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" --yes --watch
            fi

            # Always clean up IAM resources (may exist even if cluster creation failed midway)
            rosa delete operator-roles --prefix "${CLUSTER_NAME}" --region "${REGION}" --mode auto --yes || true

            if [ -n "${OIDC_ID}" ]; then
                rosa delete oidc-provider --oidc-config-id "${OIDC_ID}" --region "${REGION}" --mode auto --yes || true
                rosa delete oidc-config --oidc-config-id "${OIDC_ID}" --region "${REGION}" --mode auto --yes || true
            fi

            # Sweep AWS resources tied to this cluster's ID (catches what ROSA may leave behind)
            if [ -n "$CLUSTER_ID" ]; then
                VPC_IDS=$(aws ec2 describe-vpcs --region "${REGION}" \
                    --filters "Name=tag:Name,Values=${VPC_NAME}" "Name=state,Values=available" \
                    --query 'Vpcs[].VpcId' --output text)
                VPC_COUNT=$(echo $VPC_IDS | wc -w)
                if [ "$VPC_COUNT" -ne 1 ]; then
                    echo "Expected exactly 1 VPC matching ${VPC_NAME}, found $VPC_COUNT: $VPC_IDS"
                    exit 1
                fi
                VPC_ID="$VPC_IDS"

                echo "Sweeping AWS resources for cluster ID $CLUSTER_ID in VPC $VPC_ID"

                # Strip cluster tags from subnets tagged for this cluster (batched into one call)
                SUBNETS=$(aws ec2 describe-subnets --region "${REGION}" \
                    --filters "Name=vpc-id,Values=$VPC_ID" \
                              "Name=tag:kubernetes.io/cluster/${CLUSTER_ID},Values=owned,shared" \
                    --query 'Subnets[].SubnetId' --output text)
                if [ -n "$SUBNETS" ]; then
                    aws ec2 delete-tags --region "${REGION}" \
                        --resources $SUBNETS --tags Key="kubernetes.io/cluster/${CLUSTER_ID}"
                fi

                # Delete VPC endpoints (batched into one call)
                VPCES=$(aws ec2 describe-vpc-endpoints --region "${REGION}" \
                    --filters "Name=vpc-id,Values=$VPC_ID" \
                              "Name=tag:kubernetes.io/cluster/${CLUSTER_ID},Values=owned" \
                    --query 'VpcEndpoints[].VpcEndpointId' --output text)
                if [ -n "$VPCES" ]; then
                    echo "  Deleting VPC endpoints: $VPCES"
                    aws ec2 delete-vpc-endpoints --region "${REGION}" --vpc-endpoint-ids $VPCES
                fi

                # Two-pass SG cleanup: revoke all rules first to break cross-references, then delete
                SGS=$(aws ec2 describe-security-groups --region "${REGION}" \
                    --filters "Name=vpc-id,Values=$VPC_ID" "Name=group-name,Values=${CLUSTER_ID}-*" \
                    --query 'SecurityGroups[].GroupId' --output text)

                for sg in $SGS; do
                    INGRESS=$(aws ec2 describe-security-groups --region "${REGION}" --group-ids "$sg" \
                        --query 'SecurityGroups[0].IpPermissions' --output json)
                    EGRESS=$(aws ec2 describe-security-groups --region "${REGION}" --group-ids "$sg" \
                        --query 'SecurityGroups[0].IpPermissionsEgress' --output json)
                    [ "$INGRESS" != "[]" ] && aws ec2 revoke-security-group-ingress --region "${REGION}" \
                        --group-id "$sg" --ip-permissions "$INGRESS"
                    [ "$EGRESS" != "[]" ] && aws ec2 revoke-security-group-egress --region "${REGION}" \
                        --group-id "$sg" --ip-permissions "$EGRESS"
                done

                for sg in $SGS; do
                    echo "  Deleting SG $sg"
                    aws ec2 delete-security-group --region "${REGION}" --group-id "$sg"
                done
            fi
        '''
    }
}

pipeline {
    agent {
        label 'agent-amd64'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 120, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    parameters {
        choice(
            name: 'OCP_VERSION',
            choices: ['4.21', '4.20', '4.19', '4.18'],
            description: 'Select OpenShift cluster version'
        )
        choice(
            name: 'WORKER_COUNT',
            choices: ['3', '4', '5', '6'],
            description: 'Worker nodes in the machinepool. Each PMM replica requests 2 CPU / 3Gi, so raise this when overriding replicas via HELM_VALUES.'
        )
        booleanParam(
            name: 'DEPLOY_PMM',
            defaultValue: true,
            description: 'Deploy PMM HA after the cluster is created. Uncheck to get a bare cluster - the PMM parameters below are then ignored.'
        )
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'main',
            description: 'Branch of percona-helm-charts repo'
        )
        string(
            name: 'PMM_IMAGE_REPOSITORY',
            defaultValue: '',
            description: 'PMM image repository override (initial value is pulled from the Helm chart), i.e. "perconalab/pmm-server-fb" for feature builds'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '',
            description: 'PMM image tag override (initial value is pulled from the Helm chart), e.g. "PR-5500-a1234bc" for feature builds'
        )
        text(
            name: 'PMM_ENV_VARIABLE',
            defaultValue: '',
            description: 'Environment variables for the PMM Server containers, one KEY=VALUE per line. Merged into the pmmEnv values of the chart. Example: PMM_DEBUG=1'
        )
        string(
            name: 'HELM_VALUES',
            defaultValue: 'nodeExporter.mode=openshift,prometheus-node-exporter.enabled=false',
            description: 'Extra pmm-ha chart overrides passed to helm --set, they win over every other parameter. The defaults route node metrics through OpenShift monitoring instead of the bundled node-exporter, keep them when adding your own, e.g. replicas=5,storage.size=20Gi'
        )
        choice(
            name: 'RETENTION_DAYS',
            choices: ['1', '2', '3'],
            description: 'Days to retain cluster before auto-deletion'
        )
        string(
            name: 'PMM_ADMIN_PASSWORD',
            defaultValue: '',
            description: 'PMM admin password'
        )
        booleanParam(
            name: 'ENABLE_EXTERNAL_ACCESS',
            defaultValue: false,
            description: 'Enable external access for PMM HA (creates OpenShift Route)'
        )
        booleanParam(
            name: 'NOTIFY',
            defaultValue: true,
            description: 'Post the build status to #pmm-notifications and DM the build owner'
        )
    }

    environment {
        CLUSTER_NAME = "pmm-ha-rosa-${BUILD_NUMBER}"
        REGION = "us-east-2"
        KUBECONFIG = "${HOME}/.kube/rosa-${BUILD_NUMBER}"
        WORKER_INSTANCE_TYPE = "m7i.2xlarge"
        PATH = "${HOME}/.local/bin:${PATH}"
        VPC_NAME = "pmm-ha-rosa-shared-vpc"
        VPC_CIDR = "10.0.0.0/16"
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    // sets env.OWNER, used by notifySlack
                    getPMMBuildParams('pmm-ha-rosa-')
                    notifySlack('#0000FF', 'build started')
                }
            }
        }

        stage('Install CLI Tools') {
            steps {
                script {
                    // environment{} block vars are applied via withEnv and are only visible to steps in
                    // this pipeline - re-assign through env.X= so it's persisted on the build and exposed
                    // via buildVariables to any caller using build job: 'pmm3-ha-rosa'.
                    env.CLUSTER_NAME = env.CLUSTER_NAME
                }
                withCredentials([string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')]) {
                    sh '''
                        mkdir -p $HOME/.local/bin

                        if ! command -v rosa &>/dev/null; then
                            curl -sL https://mirror.openshift.com/pub/openshift-v4/clients/rosa/latest/rosa-linux.tar.gz -o rosa.tar.gz
                            tar xzf rosa.tar.gz
                            mv rosa $HOME/.local/bin/
                            chmod +x $HOME/.local/bin/rosa
                            rm -f rosa.tar.gz
                        fi
                        rosa version

                        if ! command -v oc &>/dev/null; then
                            curl -sL https://mirror.openshift.com/pub/openshift-v4/clients/ocp/stable/openshift-client-linux.tar.gz -o oc.tar.gz
                            tar xzf oc.tar.gz -C $HOME/.local/bin oc kubectl
                            chmod +x $HOME/.local/bin/oc $HOME/.local/bin/kubectl
                            rm -f oc.tar.gz
                        fi
                        oc version --client
                        
                        # Login once for the entire pipeline
                        rosa login --token="${ROSA_TOKEN}"
                    '''
                }
            }
        }

        stage('Check Existing Clusters') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        EXISTING_COUNT=$(rosa list clusters --region="${REGION}" -o json \
                            | jq '[.[] | select(.name | startswith("pmm-ha-rosa-"))] | length')
                        
                        EXISTING_COUNT=${EXISTING_COUNT:-0}

                        if [ "$EXISTING_COUNT" -ge 5 ]; then
                            echo "ERROR: Maximum limit of 5 test clusters reached."
                            rosa list clusters | grep "pmm-ha-rosa-"
                            exit 1
                        fi

                        echo "Existing ROSA HCP clusters: $EXISTING_COUNT / 5"
                    '''
                }
            }
        }

        stage('Ensure VPC & Networking') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set -euo pipefail

                        tag_resource() {
                            aws ec2 create-tags --region "${REGION}" \
                                --resources "$1" \
                                --tags Key=Name,Value="$2" \
                                       Key=iit-billing-tag,Value=pmm \
                                       Key=purpose,Value=pmm-ha-rosa-testing
                        }

                        # -----------------------------------------------------------
                        # Check if VPC already exists
                        # -----------------------------------------------------------
                        VPC_ID=$(aws ec2 describe-vpcs --region "${REGION}" \
                            --filters "Name=tag:Name,Values=${VPC_NAME}" "Name=state,Values=available" \
                            --query 'Vpcs[0].VpcId' --output text)

                        if [ "${VPC_ID}" != "None" ] && [ -n "${VPC_ID}" ]; then
                            echo "VPC already exists: ${VPC_ID} — skipping creation."

                            # Discover existing subnet IDs
                            PRIVATE_SUBNET_IDS=$(aws ec2 describe-subnets --region "${REGION}" \
                                --filters "Name=vpc-id,Values=${VPC_ID}" "Name=tag:Name,Values=*private*" \
                                --query 'Subnets[*].SubnetId' --output text | tr '\\t' ',')

                            PUBLIC_SUBNET_IDS=$(aws ec2 describe-subnets --region "${REGION}" \
                                --filters "Name=vpc-id,Values=${VPC_ID}" "Name=tag:Name,Values=*public*" \
                                --query 'Subnets[*].SubnetId' --output text | tr '\\t' ',')

                            echo "Private subnets: ${PRIVATE_SUBNET_IDS}"
                            echo "Public subnets:  ${PUBLIC_SUBNET_IDS}"

                            echo "${PRIVATE_SUBNET_IDS}" > private-subnet-ids.txt
                            echo "${PUBLIC_SUBNET_IDS}" > public-subnet-ids.txt
                            exit 0
                        fi

                        echo "VPC not found — creating full networking stack..."

                        # -----------------------------------------------------------
                        # Get first available AZ (Single-AZ deployment)
                        # -----------------------------------------------------------
                        AZ=$(aws ec2 describe-availability-zones --region "${REGION}" \
                            --query "AvailabilityZones[?State=='available'].ZoneName | [0]" \
                            --output text)
                        echo "Using AZ: ${AZ}"

                        # -----------------------------------------------------------
                        # VPC
                        # -----------------------------------------------------------
                        VPC_ID=$(aws ec2 create-vpc --region "${REGION}" \
                            --cidr-block "${VPC_CIDR}" \
                            --query 'Vpc.VpcId' --output text)
                        aws ec2 modify-vpc-attribute --region "${REGION}" --vpc-id "${VPC_ID}" --enable-dns-support
                        aws ec2 modify-vpc-attribute --region "${REGION}" --vpc-id "${VPC_ID}" --enable-dns-hostnames
                        tag_resource "${VPC_ID}" "${VPC_NAME}"
                        echo "VPC: ${VPC_ID}"

                        # -----------------------------------------------------------
                        # Internet Gateway
                        # -----------------------------------------------------------
                        IGW_ID=$(aws ec2 create-internet-gateway --region "${REGION}" \
                            --query 'InternetGateway.InternetGatewayId' --output text)
                        aws ec2 attach-internet-gateway --region "${REGION}" \
                            --vpc-id "${VPC_ID}" --internet-gateway-id "${IGW_ID}"
                        tag_resource "${IGW_ID}" "${VPC_NAME}-igw"
                        echo "IGW: ${IGW_ID}"

                        # -----------------------------------------------------------
                        # Public Subnet (Single-AZ)
                        # -----------------------------------------------------------
                        PUBLIC_SUBNET=$(aws ec2 create-subnet --region "${REGION}" \
                            --vpc-id "${VPC_ID}" --cidr-block "10.0.1.0/24" \
                            --availability-zone "${AZ}" \
                            --query 'Subnet.SubnetId' --output text)
                        aws ec2 create-tags --region "${REGION}" \
                            --resources "${PUBLIC_SUBNET}" \
                            --tags Key=Name,Value="${VPC_NAME}-public-${AZ}" \
                                   Key=iit-billing-tag,Value=pmm \
                                   Key=purpose,Value=pmm-ha-rosa-testing \
                                   Key=kubernetes.io/role/elb,Value=1
                        aws ec2 modify-subnet-attribute --region "${REGION}" --subnet-id "${PUBLIC_SUBNET}" --map-public-ip-on-launch
                        echo "Public subnet: ${PUBLIC_SUBNET}"

                        # -----------------------------------------------------------
                        # Public Route Table
                        # -----------------------------------------------------------
                        PUB_RT=$(aws ec2 create-route-table --region "${REGION}" \
                            --vpc-id "${VPC_ID}" \
                            --query 'RouteTable.RouteTableId' --output text)
                        aws ec2 create-route --region "${REGION}" \
                            --route-table-id "${PUB_RT}" \
                            --destination-cidr-block "0.0.0.0/0" \
                            --gateway-id "${IGW_ID}" > /dev/null
                        aws ec2 associate-route-table --region "${REGION}" --subnet-id "${PUBLIC_SUBNET}" --route-table-id "${PUB_RT}" > /dev/null
                        tag_resource "${PUB_RT}" "${VPC_NAME}-public-rt"

                        # -----------------------------------------------------------
                        # NAT Gateway
                        # -----------------------------------------------------------
                        echo "Creating NAT Gateway (takes ~2 minutes)..."
                        EIP_ALLOC=$(aws ec2 allocate-address --region "${REGION}" \
                            --domain vpc \
                            --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=${VPC_NAME}-nat-eip},{Key=iit-billing-tag,Value=pmm}]" \
                            --query 'AllocationId' --output text)

                        NAT_GW=$(aws ec2 create-nat-gateway --region "${REGION}" \
                            --subnet-id "${PUBLIC_SUBNET}" \
                            --allocation-id "${EIP_ALLOC}" \
                            --tag-specifications "ResourceType=natgateway,Tags=[{Key=Name,Value=${VPC_NAME}-nat},{Key=iit-billing-tag,Value=pmm}]" \
                            --query 'NatGateway.NatGatewayId' --output text)

                        aws ec2 wait nat-gateway-available --region "${REGION}" --nat-gateway-ids "${NAT_GW}"
                        echo "NAT GW: ${NAT_GW}"

                        # -----------------------------------------------------------
                        # Private Subnet (Single-AZ)
                        # -----------------------------------------------------------
                        PRIVATE_SUBNET=$(aws ec2 create-subnet --region "${REGION}" \
                            --vpc-id "${VPC_ID}" --cidr-block "10.0.64.0/20" \
                            --availability-zone "${AZ}" \
                            --query 'Subnet.SubnetId' --output text)
                        aws ec2 create-tags --region "${REGION}" \
                            --resources "${PRIVATE_SUBNET}" \
                            --tags Key=Name,Value="${VPC_NAME}-private-${AZ}" \
                                   Key=iit-billing-tag,Value=pmm \
                                   Key=purpose,Value=pmm-ha-rosa-testing \
                                   Key=kubernetes.io/role/internal-elb,Value=1
                        echo "Private subnet: ${PRIVATE_SUBNET}"

                        # -----------------------------------------------------------
                        # Private Route Table
                        # -----------------------------------------------------------
                        PRIV_RT=$(aws ec2 create-route-table --region "${REGION}" \
                            --vpc-id "${VPC_ID}" \
                            --query 'RouteTable.RouteTableId' --output text)
                        aws ec2 create-route --region "${REGION}" \
                            --route-table-id "${PRIV_RT}" \
                            --destination-cidr-block "0.0.0.0/0" \
                            --nat-gateway-id "${NAT_GW}" > /dev/null
                        aws ec2 associate-route-table --region "${REGION}" --subnet-id "${PRIVATE_SUBNET}" --route-table-id "${PRIV_RT}" > /dev/null
                        tag_resource "${PRIV_RT}" "${VPC_NAME}-private-rt"

                        echo ""
                        echo "Single-AZ VPC networking created successfully in ${AZ}."

                        echo "${PRIVATE_SUBNET}" > private-subnet-ids.txt
                        echo "${PUBLIC_SUBNET}" > public-subnet-ids.txt
                    '''
                }
            }
        }

        stage('Create ROSA HCP Cluster') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        env.OIDC_ID = sh(
                            returnStdout: true,
                            script: '''
                                rosa create oidc-config --region="${REGION}" --mode auto --managed --yes \
                                    -o json | jq -r '.id'
                            '''
                        ).trim()
                        echo "OIDC Config ID: ${env.OIDC_ID}"
                    }
                    sh '''
                        PRIVATE_SUBNET_IDS=$(cat private-subnet-ids.txt)
                        PUBLIC_SUBNET_IDS=$(cat public-subnet-ids.txt)
                        ALL_SUBNETS="${PRIVATE_SUBNET_IDS},${PUBLIC_SUBNET_IDS}"
                        AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

                        echo "Creating ROSA HCP cluster: ${CLUSTER_NAME}"
                        echo "  OpenShift version: ${OCP_VERSION}"
                        echo "  Instance type:     ${WORKER_INSTANCE_TYPE}"
                        echo "  Workers:           ${WORKER_COUNT}"
                        echo "  Subnets:           ${ALL_SUBNETS}"

                        # Create operator roles
                        rosa create operator-roles \
                            --hosted-cp \
                            --prefix "${CLUSTER_NAME}" \
                            --oidc-config-id "${OIDC_ID}" \
                            --installer-role-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:role/ManagedOpenShift-HCP-ROSA-Installer-Role" \
                            --region "${REGION}" \
                            --mode auto \
                            --yes

                        echo "Resolving latest patch version for OpenShift ${OCP_VERSION}..."
                        RESOLVED_VERSION=$(rosa list versions --region="${REGION}" --hosted-cp -o json | \
                            jq -r --arg ver "${OCP_VERSION}" \
                            '[.[] | select(.raw_id | startswith($ver + ".")) | .raw_id] | sort_by(split(".") | map(tonumber)) | last')

                        if [ -z "${RESOLVED_VERSION}" ] || [ "${RESOLVED_VERSION}" = "null" ]; then
                            echo "ERROR: No available version found for OpenShift ${OCP_VERSION}"
                            echo "Available HCP versions:"
                            rosa list versions --region="${REGION}" --hosted-cp
                            exit 1
                        fi
                        echo "Resolved version: ${RESOLVED_VERSION}"

                        rosa create cluster \
                            --cluster-name "${CLUSTER_NAME}" \
                            --region "${REGION}" \
                            --version "${RESOLVED_VERSION}" \
                            --hosted-cp \
                            --sts \
                            --role-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:role/ManagedOpenShift-HCP-ROSA-Installer-Role" \
                            --support-role-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:role/ManagedOpenShift-HCP-ROSA-Support-Role" \
                            --worker-iam-role "arn:aws:iam::${AWS_ACCOUNT_ID}:role/ManagedOpenShift-HCP-ROSA-Worker-Role" \
                            --operator-roles-prefix "${CLUSTER_NAME}" \
                            --oidc-config-id "${OIDC_ID}" \
                            --subnet-ids "${ALL_SUBNETS}" \
                            --compute-machine-type "${WORKER_INSTANCE_TYPE}" \
                            --replicas "${WORKER_COUNT}" \
                            --tags "iit-billing-tag pmm,created-by jenkins,build-number ${BUILD_NUMBER},retention-days ${RETENTION_DAYS},creation-time $(date -u +%s),delete-cluster-after-hours $((RETENTION_DAYS * 24)),purpose pmm-ha-rosa-testing" \
                            --yes

                        echo "Creating OIDC provider for the cluster..."
                        rosa create oidc-provider --cluster="${CLUSTER_NAME}" --region="${REGION}" --mode auto --yes

                        echo "Waiting for cluster to be ready (this may take 15-25 minutes)..."
                        while true; do
                            STATE=$(rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" -o json | jq -r '.status.state')
                            echo "  Cluster state: ${STATE}"

                            if [ "${STATE}" = "ready" ]; then
                                echo "Cluster is ready!"
                                break
                            elif [ "${STATE}" = "error" ]; then
                                echo "ERROR: Cluster creation failed!"
                                rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}"
                                exit 1
                            fi

                            sleep 60
                        done

                        rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}"
                    '''
                }
            }
        }

        stage('Configure Cluster Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x   # Jenkins runs sh with -xe; keep the admin password out of the console

                        API_URL=$(rosa describe cluster --cluster="${CLUSTER_NAME}" --region="${REGION}" -o json | jq -r '.api.url')
                        echo "API URL: ${API_URL}"

                        # The admin is only a bootstrap: it is what lets us mint the ServiceAccount below
                        ADMIN_PW=$(rosa create admin --cluster="${CLUSTER_NAME}" --region="${REGION}" --yes | awk '/--password/{print $NF}')

                        echo "Waiting for cluster authentication to become available..."
                        sleep 120

                        for i in $(seq 1 12); do
                            if oc login "${API_URL}" \
                                --username=cluster-admin \
                                --password="${ADMIN_PW}" \
                                --insecure-skip-tls-verify=true; then
                                echo "Login successful."
                                break
                            fi
                            echo "Login attempt ${i}/12 failed, retrying in 30s..."
                            sleep 30
                        done

                        oc whoami

                        echo "Waiting for all worker nodes to be ready (timeout: 20m)..."
                        for i in $(seq 1 40); do
                            READY_COUNT=$(oc get nodes --no-headers | grep -c " Ready " || true)
                            echo "Ready nodes: ${READY_COUNT}/${WORKER_COUNT}"
                            if [ "${READY_COUNT}" -ge "${WORKER_COUNT}" ]; then
                                echo "All worker nodes are ready."
                                break
                            fi
                            if [ "$i" -eq 40 ]; then
                                echo "ERROR: Timed out waiting for ${WORKER_COUNT} nodes to be ready after 20 minutes."
                                oc get nodes
                                exit 1
                            fi
                            sleep 30
                        done
                        oc get nodes -o wide
                    '''

                    mintAdminKubeconfig()
                }
            }
        }

        stage('Configure GP3 Storage Class') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        # Remove default annotation from all storage classes
                        for sc in $(oc get storageclass -o name); do
                            oc patch ${sc} -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}' || true
                        done

                        # Set gp3-csi as default
                        oc patch storageclass gp3-csi -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'

                        oc get storageclass
                    '''
                }
            }
        }

        stage('Install Kyverno') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        helm repo add kyverno https://kyverno.github.io/kyverno/ || true
                        helm repo update

                        # Install Kyverno (version 3.6.1 compatible with OpenShift/K8s 1.31+)
                        # HA deployment (3 admission replicas + PDB) prevents webhook outages
                        # that block all pod creation in non-excluded namespaces
                        helm upgrade --install kyverno kyverno/kyverno \
                            --namespace kyverno --create-namespace \
                            --version 3.6.1 \
                            --set admissionController.replicas=3 \
                            --set admissionController.podDisruptionBudget.minAvailable=1 \
                            --set backgroundController.replicas=1 \
                            --set cleanupController.replicas=1 \
                            --set reportsController.replicas=1 \
                            --wait --timeout 5m || echo "Kyverno install completed (post-hooks may timeout but pods should run)"

                        # Wait for admission controller to be ready
                        oc wait --for=condition=ready pod -l app.kubernetes.io/component=admission-controller -n kyverno --timeout=120s || true

                        echo "Waiting for Kyverno webhook TLS to initialize..."
                        for i in $(seq 1 20); do
                            CERT=$(oc get secret kyverno-svc.kyverno.svc.kyverno-tls-pair -n kyverno \
                                -o jsonpath='{.data.tls\\.crt}' 2>/dev/null || true)
                            if [ -n "${CERT}" ]; then
                                echo "Kyverno webhook TLS is ready."
                                break
                            fi
                            echo "  Waiting for TLS cert... (${i}/20)"
                            sleep 10
                        done

                        echo "Creating Docker Hub pull-through cache policy..."

                        cat <<'EOF' | oc apply -f -
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: dockerhub-pull-through-cache
spec:
  background: false
  rules:
    - name: rewrite-containers
      match:
        any:
          - resources:
              kinds: ["Pod"]
              operations: ["CREATE"]
      exclude:
        any:
          - resources:
              namespaces: ["kube-system", "kyverno", "openshift-*"]
      mutate:
        foreach:
          - list: "request.object.spec.containers"
            preconditions:
              any:
                - key: '{{images.containers."{{element.name}}".registry}}'
                  operator: Equals
                  value: "docker.io"
            patchesJson6902: |-
              - op: replace
                path: /spec/containers/{{elementIndex}}/image
                value: "reg-19jf01na.percona.com/dockerhub-cache/{{images.containers.\"{{element.name}}\".path}}:{{images.containers.\"{{element.name}}\".tag}}"
    - name: rewrite-init-containers
      match:
        any:
          - resources:
              kinds: ["Pod"]
              operations: ["CREATE"]
      exclude:
        any:
          - resources:
              namespaces: ["kube-system", "kyverno", "openshift-*"]
      mutate:
        foreach:
          - list: "request.object.spec.initContainers || `[]`"
            preconditions:
              any:
                - key: '{{images.initContainers."{{element.name}}".registry}}'
                  operator: Equals
                  value: "docker.io"
            patchesJson6902: |-
              - op: replace
                path: /spec/initContainers/{{elementIndex}}/image
                value: "reg-19jf01na.percona.com/dockerhub-cache/{{images.initContainers.\"{{element.name}}\".path}}:{{images.initContainers.\"{{element.name}}\".tag}}"
EOF
                    '''
                }
            }
        }

        stage('Install PMM HA') {
            when {
                expression { params.DEPLOY_PMM }
            }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    dir('helm-charts') {
                        git poll: false, branch: params.HELM_CHART_BRANCH, url: 'https://github.com/percona/percona-helm-charts.git'
                    }

                    sh '''
                        # The cluster's API hostname can suffer a brief DNS blip right after
                        # creation, retry so one bad lookup doesn't kill a 20+ minute stage.
                        retry() {
                            for i in $(seq 1 5); do
                                if "$@"; then
                                    return 0
                                fi
                                echo "Attempt ${i}/5 failed, retrying in 15s: $*"
                                sleep 15
                            done
                            echo "Giving up after 5 attempts: $*"
                            return 1
                        }

                        oc create namespace pmm

                        # Grant anyuid SCC to all service accounts in pmm namespace
                        oc adm policy add-scc-to-group anyuid system:serviceaccounts:pmm

                        # OpenShift uses dns-default.openshift-dns instead of kube-dns.kube-system
                        sed -i 's/kube-dns.kube-system.svc.cluster.local/dns-default.openshift-dns.svc.cluster.local/g' helm-charts/charts/pmm-ha/templates/haproxy-configmap.yaml

                        helm repo add percona https://percona.github.io/percona-helm-charts/
                        helm repo add vm https://victoriametrics.github.io/helm-charts/
                        helm repo add altinity https://helm.altinity.com || true
                        helm repo update

                        helm dependency update helm-charts/charts/pmm-ha-dependencies
                        helm upgrade --install pmm-operators helm-charts/charts/pmm-ha-dependencies -n pmm --wait --timeout 10m

                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=victoria-metrics-operator -n pmm --timeout=10m
                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=altinity-clickhouse-operator -n pmm --timeout=10m
                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=pg-operator -n pmm --timeout=10m

                        # Wait for operator webhooks to be fully initialized
                        # Operators report ready before their admission webhooks have TLS certificates configured
                        echo "Waiting for operator webhooks to initialize..."
                        sleep 60

                        if [ -n "${PMM_ADMIN_PASSWORD}" ]; then
                            PMM_PW="${PMM_ADMIN_PASSWORD}"
                        else
                            PMM_PW="$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16)"
                        fi
                        PG_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
                        GF_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
                        CH_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
                        VM_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)

                        oc create secret generic pmm-secret -n pmm \
                            --from-literal=PMM_ADMIN_PASSWORD="${PMM_PW}" \
                            --from-literal=GF_SECURITY_ADMIN_PASSWORD="${PMM_PW}" \
                            --from-literal=PG_PASSWORD="${PG_PW}" \
                            --from-literal=GF_PASSWORD="${GF_PW}" \
                            --from-literal=PMM_CLICKHOUSE_USER="clickhouse_pmm" \
                            --from-literal=PMM_CLICKHOUSE_PASSWORD="${CH_PW}" \
                            --from-literal=VMAGENT_remoteWrite_basicAuth_username="victoriametrics_pmm" \
                            --from-literal=VMAGENT_remoteWrite_basicAuth_password="${VM_PW}" \
                            --dry-run=client -o yaml | oc apply -f -

                        helm dependency update helm-charts/charts/pmm-ha

                        # Fold PMM_ENV_VARIABLE into the pmmEnv values of the chart. They end up in a
                        # ConfigMap, whose data must be strings, hence --set-string rather than --set.
                        PMM_ENV_ARGS=""
                        for kv in ${PMM_ENV_VARIABLE}; do
                            case "${kv}" in
                                *=*) PMM_ENV_ARGS="${PMM_ENV_ARGS} --set-string pmmEnv.${kv}" ;;
                                *)   echo "ERROR: PMM_ENV_VARIABLE entry '${kv}' is not a KEY=VALUE pair"; exit 1 ;;
                            esac
                        done

                        set +e

                        # Install pmm-ha chart (creates component service accounts)
                        # Resources stay at the chart defaults; raise WORKER_COUNT when pods do not fit
                        helm upgrade --install pmm-ha helm-charts/charts/pmm-ha -n pmm \
                            --timeout 20m \
                            --set secret.create=false \
                            --set secret.name=pmm-secret \
                            ${PMM_IMAGE_REPOSITORY:+--set image.repository=${PMM_IMAGE_REPOSITORY}} \
                            ${PMM_IMAGE_TAG:+--set image.tag=${PMM_IMAGE_TAG}} \
                            ${PMM_ENV_ARGS} \
                            ${HELM_VALUES:+--set ${HELM_VALUES}}

                        HELM_EXIT_CODE=$?

                        set -e

                        if [ "$HELM_EXIT_CODE" -ne 0 ]; then
                            echo "Helm failed — collecting diagnostics"

                            mkdir -p helm-debug

                            oc get pods -n pmm -o wide > helm-debug/pods.txt || true
                            oc get events -n pmm --sort-by=.metadata.creationTimestamp > helm-debug/events.txt || true

                            for pod in $(oc get pods -n pmm --no-headers | awk '{print $1}'); do
                                oc describe pod "$pod" -n pmm >> helm-debug/describe-$pod.txt || true

                                for container in $(oc get pod "$pod" -n pmm -o jsonpath='{.spec.containers[*].name}'); do
                                    oc logs "$pod" -n pmm -c "$container" \
                                        --tail=200 > "helm-debug/${pod}-${container}.log" || true
                                done
                            done

                            oc get statefulset pmm-ha -n pmm -o yaml > helm-debug/statefulset.yaml || true

                            exit $HELM_EXIT_CODE
                        fi

                        # Grant additional SCCs for monitoring components created by the chart
                        # node-exporter needs host-level access (hostNetwork, hostPID, hostPath)
                        oc adm policy add-scc-to-user node-exporter -z pmm-ha-prometheus-node-exporter -n pmm || true
                        # kube-state-metrics needs seccomp + nonroot UID outside default range
                        oc adm policy add-scc-to-user nonroot-v2 -z pmm-ha-kube-state-metrics -n pmm || true

                        echo "Waiting for all PMM HA components to be ready..."

                        # PMM servers
                        retry oc rollout status statefulset/pmm-ha -n pmm --timeout=30m

                        # ClickHouse
                        retry oc wait --for=condition=ready pod -l clickhouse.altinity.com/chi=pmm-ha -n pmm --timeout=10m

                        # ClickHouse Keeper
                        retry oc wait --for=condition=ready pod -l clickhouse-keeper.altinity.com/chk=pmm-ha-keeper -n pmm --timeout=10m

                        # PostgreSQL instances
                        retry oc wait --for=condition=ready pod -l postgres-operator.crunchydata.com/cluster=pmm-ha-pg-db,postgres-operator.crunchydata.com/data=postgres -n pmm --timeout=10m

                        # PgBouncer
                        retry oc wait --for=condition=ready pod -l postgres-operator.crunchydata.com/cluster=pmm-ha-pg-db,postgres-operator.crunchydata.com/role=pgbouncer -n pmm --timeout=10m

                        # HAProxy
                        retry oc wait --for=condition=Available deployment/pmm-ha-haproxy -n pmm --timeout=15m

                        # VictoriaMetrics
                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=vmstorage,app.kubernetes.io/instance=pmm-ha-vmcluster -n pmm --timeout=10m
                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=vminsert,app.kubernetes.io/instance=pmm-ha-vmcluster -n pmm --timeout=5m
                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=vmselect,app.kubernetes.io/instance=pmm-ha-vmcluster -n pmm --timeout=5m
                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=vmauth -n pmm --timeout=5m
                        retry oc wait --for=condition=ready pod -l app.kubernetes.io/name=vmagent -n pmm --timeout=5m

                        echo ""
                        oc get pods -n pmm
                    '''
                }
            }
        }

        stage('Configure External Access') {
            when {
                expression { params.DEPLOY_PMM && params.ENABLE_EXTERNAL_ACCESS }
            }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        sh '''
                            export KUBECONFIG="${KUBECONFIG}"

                            cat <<EOF | oc apply -f -
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: pmm-ha-route
  namespace: pmm
  labels:
    app: pmm-ha
spec:
  to:
    kind: Service
    name: pmm-ha-haproxy
    weight: 100
  port:
    targetPort: https
  tls:
    termination: passthrough
    insecureEdgeTerminationPolicy: Redirect
  wildcardPolicy: None
EOF

                            echo "Waiting for route to be available..."
                            sleep 30
                        '''

                        def routeHost = sh(
                            returnStdout: true,
                            script: '''
                                export KUBECONFIG="${KUBECONFIG}"
                                oc get route pmm-ha-route -n pmm -o jsonpath='{.spec.host}'
                            '''
                        ).trim()

                        env.PMM_URL = "https://${routeHost}"
                    }
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x
                        export KUBECONFIG="${KUBECONFIG}"

                        echo "ROSA HCP Cluster Summary"
                        echo "=============================="

                        echo "Name:    ${CLUSTER_NAME}"
                        echo "Version: OpenShift ${OCP_VERSION}"
                        echo "Region:  ${REGION}"
                        echo "Build:   ${BUILD_NUMBER}"
                        echo ""

                        oc get nodes -o wide
                        echo ""
                        oc get clusterversion
                        echo ""
                        oc get storageclass
                        echo ""

                        echo "kubectl/oc access (local):"
                        echo "  # Download the kubeconfig artifact - valid for the cluster's whole life."
                        echo "  export KUBECONFIG=./kubeconfig"
                        echo ""

                        echo "Scale workers:"
                        echo "  Run the pmm3-ha-rosa-scale job with CLUSTER_NAME=${CLUSTER_NAME} (3-6 workers)"
                        echo ""

                        # Everything below describes PMM, so a bare cluster stops here.
                        [ "${DEPLOY_PMM}" = "true" ] || exit 0

                        echo "Internal Component Credentials"
                        echo "------------------------------"

                        get_secret() {
                            oc get secret pmm-secret -n pmm \
                                -o "jsonpath={.data.$1}" 2>/dev/null | base64 --decode
                        }
                        echo "PMM/Grafana:     admin / $(get_secret PMM_ADMIN_PASSWORD)"
                        echo "PostgreSQL:      $(get_secret PG_PASSWORD)"
                        echo "ClickHouse:      $(get_secret PMM_CLICKHOUSE_USER) / $(get_secret PMM_CLICKHOUSE_PASSWORD)"
                        echo "VictoriaMetrics: $(get_secret VMAGENT_remoteWrite_basicAuth_username) / $(get_secret VMAGENT_remoteWrite_basicAuth_password)"
                        echo ""

                        echo "PMM access:"
                        echo "  oc port-forward svc/pmm-ha-haproxy 8443:443 -n pmm"
                        echo "  # Then access https://localhost:8443"
                        echo ""

                        if [ "${ENABLE_EXTERNAL_ACCESS}" = "true" ]; then
                            echo "External Access (OpenShift Route)"
                            echo "------------------------------"
                            echo "  ${PMM_URL}"
                            echo ""
                        fi
                    '''
                }
            }
        }

        stage('Archive kubeconfig') {
            steps {
                archiveArtifacts artifacts: 'kubeconfig', fingerprint: true
            }
        }
    }

    post {
        success {
            script {
                // env.PMM_URL is only set when the Route stage ran.
                def pmmStatus = params.DEPLOY_PMM ? (env.PMM_URL ?: 'port-forward required') : 'not deployed'

                currentBuild.description = "Cluster: ${env.CLUSTER_NAME} | OCP: ${params.OCP_VERSION} | PMM: ${pmmStatus}"
                notifySlack('#00FF00', "cluster is ready, it expires in ${params.RETENTION_DAYS} day(s)\nPMM: ${pmmStatus}")
            }
            echo "ROSA HCP cluster ${CLUSTER_NAME} created successfully."
            echo "Download the kubeconfig artifact to access the cluster."
        }
        failure {
            notifySlack('#FF0000', 'build failed, cleaning up')
            echo "Build FAILED — cleaning up cluster"
            archiveArtifacts artifacts: 'helm-debug/**', allowEmptyArchive: true
            cleanupCluster()
        }
        aborted {
            notifySlack('#808080', 'build aborted, cleaning up')
            echo "Build ABORTED — cleaning up cluster"
            cleanupCluster()
        }
    }
}
