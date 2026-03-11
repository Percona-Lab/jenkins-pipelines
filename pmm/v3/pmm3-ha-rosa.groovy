/**
 * PMM HA on ROSA HCP - Creates a ROSA cluster and deploys PMM in HA mode.
 *
 * Self-contained single-file pipeline (no external library dependencies).
 * Validated shell script logic ported from /tmp/rosa-ha-create.sh
 */

pipeline {
    agent { label 'agent-amd64-ol9' }

    parameters {
        string(
            name: 'CLUSTER_NAME',
            defaultValue: '',
            description: 'Cluster name (leave empty for auto-generated pmm-ha-rosa-BUILD_NUMBER)'
        )
        choice(
            name: 'OPENSHIFT_VERSION',
            choices: ['4.17', '4.18', '4.16'],
            description: 'OpenShift version for ROSA HCP cluster'
        )
        choice(
            name: 'REPLICAS',
            choices: ['2', '3', '4'],
            description: 'Number of worker nodes'
        )
        choice(
            name: 'INSTANCE_TYPE',
            choices: ['m5.xlarge', 'm5.2xlarge', 'm5.4xlarge'],
            description: 'EC2 instance type for worker nodes'
        )
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'pmmha-v3',
            description: 'Branch of percona-helm-charts repo for PMM HA chart'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '',
            description: 'PMM Server image tag (leave empty for chart default)'
        )
        booleanParam(
            name: 'DEBUG_MODE',
            defaultValue: true,
            description: 'Skip cleanup on failure for debugging'
        )
        booleanParam(
            name: 'SKIP_PMM_INSTALL',
            defaultValue: false,
            description: 'Skip PMM HA installation (cluster only)'
        )
    }

    environment {
        REGION = 'us-east-2'
        AWS_ACCOUNT_ID = '119175775298'
        OPERATOR_ROLE_PREFIX = 'pmm-rosa-ha'
        CLUSTER_PREFIX = 'pmm-ha-rosa-'
        MAX_CLUSTERS = 5
    }

    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 90, unit: 'MINUTES')
        timestamps()
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    deleteDir()

                    // Set cluster name
                    env.CLUSTER_NAME = params.CLUSTER_NAME ?: "pmm-ha-rosa-${BUILD_NUMBER}"

                    currentBuild.displayName = "#${BUILD_NUMBER} - ${env.CLUSTER_NAME}"
                    currentBuild.description = "ROSA ${params.OPENSHIFT_VERSION} | ${params.INSTANCE_TYPE} x ${params.REPLICAS}"
                }
            }
        }

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Install CLI Tools') {
            steps {
                sh '''
                    set -o errexit

                    mkdir -p $HOME/.local/bin
                    export PATH="$HOME/.local/bin:$PATH"

                    echo "Installing CLI tools with SHA256 checksum validation..."

                    # ============================================================
                    # ROSA CLI
                    # ============================================================
                    if ! command -v rosa &>/dev/null; then
                        echo "[1/3] Installing ROSA CLI..."
                        ROSA_URL="https://mirror.openshift.com/pub/openshift-v4/clients/rosa/latest"

                        curl -sSL "${ROSA_URL}/rosa-linux.tar.gz" -o /tmp/rosa.tar.gz
                        curl -sSL "${ROSA_URL}/sha256sum.txt" -o /tmp/rosa-sha256sum.txt

                        # Validate checksum
                        EXPECTED_SHA=$(grep 'rosa-linux.tar.gz$' /tmp/rosa-sha256sum.txt | awk '{print $1}')
                        ACTUAL_SHA=$(sha256sum /tmp/rosa.tar.gz | awk '{print $1}')

                        if [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
                            echo "ERROR: ROSA checksum mismatch!"
                            echo "  Expected: $EXPECTED_SHA"
                            echo "  Actual:   $ACTUAL_SHA"
                            exit 1
                        fi
                        echo "  Checksum OK: $ACTUAL_SHA"

                        tar -xzf /tmp/rosa.tar.gz -C $HOME/.local/bin rosa
                        chmod +x $HOME/.local/bin/rosa
                        rm -f /tmp/rosa.tar.gz /tmp/rosa-sha256sum.txt
                    else
                        echo "[1/3] ROSA CLI already installed: $(rosa version | head -1)"
                    fi

                    # ============================================================
                    # OpenShift CLI (oc)
                    # ============================================================
                    if ! command -v oc &>/dev/null; then
                        echo "[2/3] Installing OpenShift CLI (oc)..."
                        OC_VERSION="stable-${OPENSHIFT_VERSION}"
                        OC_URL="https://mirror.openshift.com/pub/openshift-v4/clients/ocp/${OC_VERSION}"

                        curl -sSL "${OC_URL}/openshift-client-linux.tar.gz" -o /tmp/oc.tar.gz
                        curl -sSL "${OC_URL}/sha256sum.txt" -o /tmp/oc-sha256sum.txt

                        # Validate checksum
                        EXPECTED_SHA=$(grep 'openshift-client-linux.tar.gz$' /tmp/oc-sha256sum.txt | awk '{print $1}')
                        ACTUAL_SHA=$(sha256sum /tmp/oc.tar.gz | awk '{print $1}')

                        if [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
                            echo "ERROR: OpenShift CLI checksum mismatch!"
                            echo "  Expected: $EXPECTED_SHA"
                            echo "  Actual:   $ACTUAL_SHA"
                            exit 1
                        fi
                        echo "  Checksum OK: $ACTUAL_SHA"

                        tar -xzf /tmp/oc.tar.gz -C $HOME/.local/bin oc kubectl
                        chmod +x $HOME/.local/bin/oc $HOME/.local/bin/kubectl
                        rm -f /tmp/oc.tar.gz /tmp/oc-sha256sum.txt
                    else
                        echo "[2/3] OpenShift CLI already installed: $(oc version --client 2>/dev/null | head -1)"
                    fi

                    # ============================================================
                    # Helm (latest stable from GitHub releases)
                    # ============================================================
                    if ! command -v helm &>/dev/null; then
                        echo "[3/3] Installing Helm..."

                        # Get latest stable version from GitHub API
                        HELM_VERSION=$(curl -sSL https://api.github.com/repos/helm/helm/releases/latest | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')
                        echo "  Latest version: ${HELM_VERSION}"

                        HELM_URL="https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz"

                        curl -sSL "${HELM_URL}" -o /tmp/helm.tar.gz
                        curl -sSL "${HELM_URL}.sha256sum" -o /tmp/helm-sha256sum.txt

                        # Validate checksum (helm format: "hash  filename")
                        EXPECTED_SHA=$(cat /tmp/helm-sha256sum.txt | awk '{print $1}')
                        ACTUAL_SHA=$(sha256sum /tmp/helm.tar.gz | awk '{print $1}')

                        if [ "$EXPECTED_SHA" != "$ACTUAL_SHA" ]; then
                            echo "ERROR: Helm checksum mismatch!"
                            echo "  Expected: $EXPECTED_SHA"
                            echo "  Actual:   $ACTUAL_SHA"
                            exit 1
                        fi
                        echo "  Checksum OK: $ACTUAL_SHA"

                        tar -xzf /tmp/helm.tar.gz -C /tmp linux-amd64/helm
                        mv /tmp/linux-amd64/helm $HOME/.local/bin/helm
                        chmod +x $HOME/.local/bin/helm
                        rm -rf /tmp/helm.tar.gz /tmp/helm-sha256sum.txt /tmp/linux-amd64
                    else
                        echo "[3/3] Helm already installed: $(helm version --short)"
                    fi

                    # Verify all tools
                    echo ""
                    echo "CLI Tools Summary:"
                    echo "  ROSA: $(rosa version | head -1)"
                    echo "  OC:   $(oc version --client 2>/dev/null | grep 'Client Version' || oc version --client | head -1)"
                    echo "  Helm: $(helm version --short)"
                '''
            }
        }

        stage('Verify Prerequisites') {
            steps {
                withCredentials([
                    aws(credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        set -o errexit
                        export PATH="$HOME/.local/bin:$PATH"
                        export AWS_DEFAULT_REGION="${REGION}"

                        echo "[1/7] Verifying prerequisites..."

                        # Check CLIs
                        command -v rosa &>/dev/null || { echo "ERROR: rosa CLI not found"; exit 1; }
                        command -v oc &>/dev/null || { echo "ERROR: oc CLI not found"; exit 1; }
                        command -v helm &>/dev/null || { echo "ERROR: helm CLI not found"; exit 1; }

                        # Check AWS auth
                        aws sts get-caller-identity || { echo "ERROR: Not authenticated to AWS"; exit 1; }

                        # Login to ROSA
                        rosa login --token="${ROSA_TOKEN}"
                        rosa whoami

                        echo "  Prerequisites: OK"
                    '''
                }
            }
        }

        stage('Check Cluster Limit') {
            steps {
                withCredentials([
                    aws(credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        set -o errexit
                        export PATH="$HOME/.local/bin:$PATH"
                        export AWS_DEFAULT_REGION="${REGION}"

                        echo "Checking existing PMM HA ROSA clusters..."

                        CLUSTER_COUNT=$(rosa list clusters -o json 2>/dev/null | \
                            jq -r --arg prefix "${CLUSTER_PREFIX}" \
                            '[.[] | select(.name | startswith($prefix))] | length')

                        echo "Existing clusters: ${CLUSTER_COUNT} / ${MAX_CLUSTERS}"

                        if [ "${CLUSTER_COUNT}" -ge "${MAX_CLUSTERS}" ]; then
                            echo "ERROR: Maximum cluster limit (${MAX_CLUSTERS}) reached."
                            echo "Delete old clusters with: rosa delete cluster --cluster=<name> --yes"
                            exit 1
                        fi
                    '''
                }
            }
        }

        stage('Create ROSA HCP Cluster') {
            steps {
                withCredentials([
                    aws(credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    sh '''
                        set -o errexit
                        export PATH="$HOME/.local/bin:$PATH"
                        export AWS_DEFAULT_REGION="${REGION}"

                        echo "============================================"
                        echo "Creating ROSA HCP Cluster: ${CLUSTER_NAME}"
                        echo "============================================"

                        # [2/7] Determine OIDC config from existing operator roles
                        echo "[2/7] Determining OIDC configuration..."

                        OIDC_CONFIG_ID=""
                        if aws iam get-role --role-name "${OPERATOR_ROLE_PREFIX}-kube-system-kms-provider" &>/dev/null; then
                            OIDC_CONFIG_ID=$(aws iam get-role --role-name "${OPERATOR_ROLE_PREFIX}-kube-system-kms-provider" | \
                                jq -r '.Role.AssumeRolePolicyDocument.Statement[0].Principal.Federated // empty' | \
                                grep -oE '[a-z0-9]+$' || echo "")

                            if [ -n "${OIDC_CONFIG_ID}" ]; then
                                echo "  Found existing operator roles trusting OIDC: ${OIDC_CONFIG_ID}"
                            fi
                        fi

                        if [ -z "${OIDC_CONFIG_ID}" ]; then
                            OIDC_CONFIG_ID=$(rosa list oidc-config -o json 2>/dev/null | jq -r '.[0].id // empty')

                            if [ -z "${OIDC_CONFIG_ID}" ]; then
                                echo "  Creating new OIDC configuration..."
                                rosa create oidc-config --mode=auto --yes
                                sleep 5
                                OIDC_CONFIG_ID=$(rosa list oidc-config -o json | jq -r '.[0].id')
                            fi
                        fi
                        echo "  OIDC Config ID: ${OIDC_CONFIG_ID}"

                        # [3/7] Check account roles
                        echo "[3/7] Checking account roles..."
                        if ! rosa list account-roles 2>/dev/null | grep -q "HCP-ROSA-Installer"; then
                            echo "  Creating account roles..."
                            rosa create account-roles --hosted-cp --mode=auto --yes 2>&1 | grep -v "^ERR: Insufficient AWS quotas" || true
                        fi
                        echo "  Account roles: OK"

                        # [4/7] Resolve OpenShift version
                        echo "[4/7] Resolving OpenShift version..."
                        RESOLVED_VERSION=$(rosa list versions --hosted-cp -o json 2>/dev/null | \
                            jq -r --arg v "${OPENSHIFT_VERSION}" '.[] | select(.raw_id | startswith($v + ".")) | .raw_id' | \
                            head -1)

                        if [ -z "${RESOLVED_VERSION}" ]; then
                            echo "ERROR: No version found for ${OPENSHIFT_VERSION}"
                            exit 1
                        fi
                        echo "  Resolved: ${RESOLVED_VERSION}"

                        # [5/7] Setup VPC
                        echo "[5/7] Setting up VPC..."
                        VPC_STACK_NAME="${CLUSTER_NAME}-vpc"

                        STACK_STATUS=$(aws cloudformation describe-stacks --stack-name "${VPC_STACK_NAME}" --region "${REGION}" \
                            --query "Stacks[0].StackStatus" --output text 2>/dev/null || echo "NOT_FOUND")

                        if [ "${STACK_STATUS}" = "ROLLBACK_COMPLETE" ] || [ "${STACK_STATUS}" = "DELETE_COMPLETE" ]; then
                            echo "  Deleting failed stack..."
                            aws cloudformation delete-stack --stack-name "${VPC_STACK_NAME}" --region "${REGION}"
                            aws cloudformation wait stack-delete-complete --stack-name "${VPC_STACK_NAME}" --region "${REGION}" 2>/dev/null || true
                            STACK_STATUS="NOT_FOUND"
                        fi

                        if [ "${STACK_STATUS}" = "NOT_FOUND" ]; then
                            VPC_COUNT=$(aws ec2 describe-vpcs --region "${REGION}" --query "length(Vpcs)" --output text)
                            if [ "${VPC_COUNT}" -ge 20 ]; then
                                echo "ERROR: VPC limit reached (${VPC_COUNT}/20)"
                                exit 1
                            fi

                            cat > /tmp/vpc-${BUILD_NUMBER}.yaml << 'VPCTEMPLATE'
AWSTemplateFormatVersion: '2010-09-09'
Description: VPC for ROSA HCP cluster
Parameters:
  Region:
    Type: String
Resources:
  VPC:
    Type: AWS::EC2::VPC
    Properties:
      CidrBlock: '10.0.0.0/16'
      EnableDnsSupport: true
      EnableDnsHostnames: true
      Tags:
        - Key: Name
          Value: !Ref AWS::StackName
  InternetGateway:
    Type: AWS::EC2::InternetGateway
  AttachGateway:
    Type: AWS::EC2::VPCGatewayAttachment
    Properties:
      VpcId: !Ref VPC
      InternetGatewayId: !Ref InternetGateway
  PublicSubnet1:
    Type: AWS::EC2::Subnet
    Properties:
      VpcId: !Ref VPC
      CidrBlock: '10.0.0.0/24'
      AvailabilityZone: !Sub '${Region}a'
      MapPublicIpOnLaunch: true
  PublicSubnet2:
    Type: AWS::EC2::Subnet
    Properties:
      VpcId: !Ref VPC
      CidrBlock: '10.0.1.0/24'
      AvailabilityZone: !Sub '${Region}b'
      MapPublicIpOnLaunch: true
  PrivateSubnet1:
    Type: AWS::EC2::Subnet
    Properties:
      VpcId: !Ref VPC
      CidrBlock: '10.0.128.0/24'
      AvailabilityZone: !Sub '${Region}a'
  PrivateSubnet2:
    Type: AWS::EC2::Subnet
    Properties:
      VpcId: !Ref VPC
      CidrBlock: '10.0.129.0/24'
      AvailabilityZone: !Sub '${Region}b'
  PublicRouteTable:
    Type: AWS::EC2::RouteTable
    Properties:
      VpcId: !Ref VPC
  PublicRoute:
    Type: AWS::EC2::Route
    DependsOn: AttachGateway
    Properties:
      RouteTableId: !Ref PublicRouteTable
      DestinationCidrBlock: '0.0.0.0/0'
      GatewayId: !Ref InternetGateway
  PublicSubnet1RouteAssoc:
    Type: AWS::EC2::SubnetRouteTableAssociation
    Properties:
      SubnetId: !Ref PublicSubnet1
      RouteTableId: !Ref PublicRouteTable
  PublicSubnet2RouteAssoc:
    Type: AWS::EC2::SubnetRouteTableAssociation
    Properties:
      SubnetId: !Ref PublicSubnet2
      RouteTableId: !Ref PublicRouteTable
  EIP1:
    Type: AWS::EC2::EIP
    Properties:
      Domain: vpc
  EIP2:
    Type: AWS::EC2::EIP
    Properties:
      Domain: vpc
  NatGateway1:
    Type: AWS::EC2::NatGateway
    Properties:
      AllocationId: !GetAtt EIP1.AllocationId
      SubnetId: !Ref PublicSubnet1
  NatGateway2:
    Type: AWS::EC2::NatGateway
    Properties:
      AllocationId: !GetAtt EIP2.AllocationId
      SubnetId: !Ref PublicSubnet2
  PrivateRouteTable1:
    Type: AWS::EC2::RouteTable
    Properties:
      VpcId: !Ref VPC
  PrivateRouteTable2:
    Type: AWS::EC2::RouteTable
    Properties:
      VpcId: !Ref VPC
  PrivateRoute1:
    Type: AWS::EC2::Route
    Properties:
      RouteTableId: !Ref PrivateRouteTable1
      DestinationCidrBlock: '0.0.0.0/0'
      NatGatewayId: !Ref NatGateway1
  PrivateRoute2:
    Type: AWS::EC2::Route
    Properties:
      RouteTableId: !Ref PrivateRouteTable2
      DestinationCidrBlock: '0.0.0.0/0'
      NatGatewayId: !Ref NatGateway2
  PrivateSubnet1RouteAssoc:
    Type: AWS::EC2::SubnetRouteTableAssociation
    Properties:
      SubnetId: !Ref PrivateSubnet1
      RouteTableId: !Ref PrivateRouteTable1
  PrivateSubnet2RouteAssoc:
    Type: AWS::EC2::SubnetRouteTableAssociation
    Properties:
      SubnetId: !Ref PrivateSubnet2
      RouteTableId: !Ref PrivateRouteTable2
VPCTEMPLATE

                            echo "  Creating VPC stack: ${VPC_STACK_NAME}"
                            aws cloudformation create-stack \
                                --stack-name "${VPC_STACK_NAME}" \
                                --template-body "file:///tmp/vpc-${BUILD_NUMBER}.yaml" \
                                --parameters ParameterKey=Region,ParameterValue="${REGION}" \
                                --region "${REGION}"

                            echo "  Waiting for VPC stack (3-5 minutes)..."
                            aws cloudformation wait stack-create-complete --stack-name "${VPC_STACK_NAME}" --region "${REGION}"
                            rm -f "/tmp/vpc-${BUILD_NUMBER}.yaml"

                            echo "true" > vpc_created.flag
                        else
                            echo "  VPC stack already exists: ${VPC_STACK_NAME}"
                        fi

                        # Get subnets from VPC
                        VPC_ID=$(aws ec2 describe-vpcs --region "${REGION}" \
                            --filters "Name=tag:Name,Values=${VPC_STACK_NAME}" \
                            --query "Vpcs[0].VpcId" --output text)

                        PUBLIC_SUBNETS=$(aws ec2 describe-subnets \
                            --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=true" \
                            --region "${REGION}" --query "Subnets[*].SubnetId" --output text | tr '\t' ',')

                        PRIVATE_SUBNETS=$(aws ec2 describe-subnets \
                            --filters "Name=vpc-id,Values=${VPC_ID}" "Name=map-public-ip-on-launch,Values=false" \
                            --region "${REGION}" --query "Subnets[*].SubnetId" --output text | tr '\t' ',')

                        ALL_SUBNETS="${PUBLIC_SUBNETS},${PRIVATE_SUBNETS}"
                        echo "  Subnets: ${ALL_SUBNETS}"

                        # [6/7] Check operator roles
                        echo "[6/7] Checking operator roles..."
                        INSTALLER_ROLE_ARN="arn:aws:iam::${AWS_ACCOUNT_ID}:role/ManagedOpenShift-HCP-ROSA-Installer-Role"

                        if ! aws iam get-role --role-name "${OPERATOR_ROLE_PREFIX}-kube-system-kms-provider" &>/dev/null; then
                            echo "  Creating operator roles..."
                            rosa create operator-roles \
                                --hosted-cp \
                                --prefix="${OPERATOR_ROLE_PREFIX}" \
                                --oidc-config-id="${OIDC_CONFIG_ID}" \
                                --installer-role-arn="${INSTALLER_ROLE_ARN}" \
                                --mode=auto --yes
                        fi
                        echo "  Operator roles: OK"

                        # [7/7] Create cluster
                        echo "[7/7] Creating ROSA HCP cluster..."
                        rosa create cluster \
                            --cluster-name="${CLUSTER_NAME}" \
                            --sts --hosted-cp \
                            --region="${REGION}" \
                            --version="${RESOLVED_VERSION}" \
                            --compute-machine-type="${INSTANCE_TYPE}" \
                            --replicas="${REPLICAS}" \
                            --subnet-ids="${ALL_SUBNETS}" \
                            --oidc-config-id="${OIDC_CONFIG_ID}" \
                            --operator-roles-prefix="${OPERATOR_ROLE_PREFIX}" \
                            --mode=auto --yes

                        # Wait for ready
                        echo "Waiting for cluster to be ready (~30-40 minutes)..."
                        MAX_ATTEMPTS=80
                        ATTEMPT=0

                        while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
                            ATTEMPT=$((ATTEMPT + 1))
                            STATUS=$(rosa describe cluster -c "${CLUSTER_NAME}" -o json 2>/dev/null | jq -r '.state // "unknown"')
                            echo "[$(date '+%H:%M:%S')] Status: ${STATUS} (${ATTEMPT}/${MAX_ATTEMPTS})"

                            case "${STATUS}" in
                                ready) echo "Cluster is ready!"; break ;;
                                error|uninstalling) echo "ERROR: Cluster failed"; exit 1 ;;
                            esac
                            sleep 30
                        done

                        [ "${STATUS}" = "ready" ] || { echo "ERROR: Timeout"; exit 1; }

                        # Create admin
                        echo "Creating cluster admin..."
                        rosa create admin --cluster="${CLUSTER_NAME}"
                    '''
                }
            }
        }

        stage('Configure Access') {
            steps {
                withCredentials([
                    aws(credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        // Get cluster info
                        env.CLUSTER_API_URL = sh(
                            script: "rosa describe cluster --cluster=${env.CLUSTER_NAME} -o json | jq -r '.api.url'",
                            returnStdout: true
                        ).trim()

                        env.CLUSTER_CONSOLE_URL = sh(
                            script: "rosa describe cluster --cluster=${env.CLUSTER_NAME} -o json | jq -r '.console.url'",
                            returnStdout: true
                        ).trim()

                        // Get admin password from rosa output
                        def adminOutput = sh(
                            script: "rosa describe cluster --cluster=${env.CLUSTER_NAME} 2>&1 || true",
                            returnStdout: true
                        )

                        // Wait for admin credentials
                        echo 'Waiting 90s for admin credentials to propagate...'
                        sleep(90)

                        // Login
                        sh """
                            export PATH="\$HOME/.local/bin:\$PATH"

                            # Get fresh admin credentials
                            ADMIN_PASS=\$(rosa create admin --cluster=${env.CLUSTER_NAME} 2>&1 | grep -oE '[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}' | head -1 || echo "")

                            if [ -n "\${ADMIN_PASS}" ]; then
                                echo "Logging in to cluster..."
                                oc login ${env.CLUSTER_API_URL} --username=cluster-admin --password="\${ADMIN_PASS}" --insecure-skip-tls-verify=true
                                oc whoami
                                oc get nodes
                            fi
                        """
                    }
                }
            }
        }

        stage('Install Kyverno') {
            when { expression { !params.SKIP_PMM_INSTALL } }
            steps {
                sh '''
                    set -o errexit
                    export PATH="$HOME/.local/bin:$PATH"

                    echo "Installing Kyverno..."
                    helm repo add kyverno https://kyverno.github.io/kyverno/ || true
                    helm repo update

                    helm upgrade --install kyverno kyverno/kyverno \
                        --namespace kyverno --create-namespace \
                        --version 3.3.0 \
                        --set admissionController.replicas=1 \
                        --set backgroundController.replicas=1 \
                        --set cleanupController.replicas=1 \
                        --set reportsController.replicas=1 \
                        --wait --timeout 5m || true

                    oc wait --for=condition=ready pod -l app.kubernetes.io/component=admission-controller -n kyverno --timeout=120s || true

                    echo "Applying Docker Hub pull-through cache policy..."
                    oc apply -f pmm/v3/resources/kyverno-dockerhub-policy.yaml || true
                '''
            }
        }

        stage('Install PMM HA') {
            when { expression { !params.SKIP_PMM_INSTALL } }
            steps {
                withCredentials([
                    aws(credentialsId: 'jenkins-openshift-aws',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
                    string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
                ]) {
                    script {
                        // Generate password
                        env.PMM_ADMIN_PASSWORD = sh(
                            script: "openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16",
                            returnStdout: true
                        ).trim()

                        // Re-login to cluster (kubeconfig doesn't persist between stages)
                        sh """
                            export PATH="\$HOME/.local/bin:\$PATH"
                            rosa login --token="\${ROSA_TOKEN}"

                            # Get admin password and login
                            ADMIN_PASS=\$(rosa create admin --cluster=${env.CLUSTER_NAME} 2>&1 | grep -oE '[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}' | head -1 || echo "")
                            if [ -n "\${ADMIN_PASS}" ]; then
                                oc login ${env.CLUSTER_API_URL} --username=cluster-admin --password="\${ADMIN_PASS}" --insecure-skip-tls-verify=true
                            fi
                        """

                        sh """
                            set -o errexit
                            export PATH="\$HOME/.local/bin:\$PATH"

                            echo "Installing PMM HA..."

                            # Clone helm charts
                            rm -rf percona-helm-charts
                        git clone --depth 1 -b ${params.HELM_CHART_BRANCH} https://github.com/percona/percona-helm-charts.git percona-helm-charts || \
                        git clone --depth 1 -b ${params.HELM_CHART_BRANCH} https://github.com/theTibi/percona-helm-charts.git percona-helm-charts

                        # Setup namespace and SCC
                        oc create namespace pmm 2>/dev/null || true
                        oc apply -f pmm/v3/resources/pmm-anyuid-scc.yaml

                        # Create secrets
                        oc delete secret pmm-secret -n pmm 2>/dev/null || true
                        oc create secret generic pmm-secret -n pmm \
                            --from-literal=PMM_ADMIN_PASSWORD=${env.PMM_ADMIN_PASSWORD} \
                            --from-literal=PMM_CLICKHOUSE_PASSWORD=${env.PMM_ADMIN_PASSWORD} \
                            --from-literal=PG_PASSWORD=${env.PMM_ADMIN_PASSWORD} \
                            --from-literal=GF_PASSWORD=${env.PMM_ADMIN_PASSWORD}

                        # Add helm repos
                        helm repo add percona https://percona.github.io/percona-helm-charts/ || true
                        helm repo add victoriametrics https://victoriametrics.github.io/helm-charts/ || true
                        helm repo add altinity https://helm.altinity.com || true
                        helm repo add haproxy https://haproxytech.github.io/helm-charts/ || true
                        helm repo update

                        # Install dependencies
                        helm dependency build percona-helm-charts/charts/pmm-ha-dependencies
                        helm upgrade --install pmm-ha-deps percona-helm-charts/charts/pmm-ha-dependencies \
                            --namespace pmm --wait --timeout 10m

                        # Wait for operators
                        oc wait --for=condition=ready pod -l app.kubernetes.io/name=victoria-metrics-operator -n pmm --timeout=300s || true
                        oc wait --for=condition=ready pod -l app.kubernetes.io/name=altinity-clickhouse-operator -n pmm --timeout=300s || true

                        # Install PMM HA
                        helm dependency build percona-helm-charts/charts/pmm-ha

                        HELM_ARGS="--namespace pmm --set service.type=LoadBalancer"
                        [ -n "${PMM_IMAGE_TAG}" ] && HELM_ARGS="\${HELM_ARGS} --set image.tag=${params.PMM_IMAGE_TAG}"

                        helm upgrade --install pmm-ha percona-helm-charts/charts/pmm-ha \${HELM_ARGS}

                        # Patch HAProxy for OpenShift DNS
                        for i in {1..30}; do
                            if oc get cm pmm-ha-haproxy -n pmm &>/dev/null; then
                                oc get cm pmm-ha-haproxy -n pmm -o json | \
                                    sed 's/kube-dns.kube-system.svc.cluster.local/dns-default.openshift-dns.svc.cluster.local/g' | \
                                    oc apply -f -
                                oc delete pods -n pmm -l app.kubernetes.io/name=haproxy --wait=false || true
                                break
                            fi
                            sleep 5
                        done

                        # Wait for pods
                        oc wait --for=condition=Ready pods -n pmm -l app.kubernetes.io/name=haproxy --timeout=300s || true
                    """

                    // Get PMM URL
                    env.PMM_URL = sh(
                        script: '''
                            for i in {1..30}; do
                                URL=$(oc get svc monitoring-service -n pmm -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)
                                [ -n "$URL" ] && { echo "https://$URL"; exit 0; }
                                sleep 10
                            done
                            echo "pending"
                        ''',
                        returnStdout: true
                    ).trim()
                    }
                }
            }
        }

        stage('Summary') {
            steps {
                script {
                    def summary = """
============================================
PMM HA ON ROSA - DEPLOYMENT COMPLETE
============================================
Cluster:    ${env.CLUSTER_NAME}
Console:    ${env.CLUSTER_CONSOLE_URL}
API:        ${env.CLUSTER_API_URL}
"""
                    if (!params.SKIP_PMM_INSTALL) {
                        summary += """
PMM URL:    ${env.PMM_URL}
PMM User:   admin
PMM Pass:   ${env.PMM_ADMIN_PASSWORD}
"""
                    }
                    summary += """
============================================
Delete: rosa delete cluster --cluster=${env.CLUSTER_NAME} --yes
        aws cloudformation delete-stack --stack-name ${env.CLUSTER_NAME}-vpc
============================================
"""
                    echo summary
                    currentBuild.description = "${env.CLUSTER_NAME} | ${env.PMM_URL ?: 'no PMM'}"
                }
            }
        }
    }

    post {
        failure {
            script {
                if (!params.DEBUG_MODE) {
                    echo 'Cleaning up failed cluster...'
                    cleanupCluster()
                } else {
                    echo 'DEBUG_MODE: Skipping cleanup'
                }
            }
        }
        aborted {
            script {
                if (!params.DEBUG_MODE) {
                    echo 'Job aborted - cleaning up cluster...'
                    cleanupCluster()
                } else {
                    echo 'DEBUG_MODE: Skipping cleanup'
                }
            }
        }
        always {
            deleteDir()
        }
    }
}

def cleanupCluster() {
    try {
        timeout(time: 10, unit: 'MINUTES') {
            withCredentials([
                aws(credentialsId: 'jenkins-openshift-aws',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
                string(credentialsId: 'REDHAT_OFFLINE_TOKEN', variable: 'ROSA_TOKEN')
            ]) {
                sh """
                    export PATH="\$HOME/.local/bin:\$PATH"

                    rosa login --token="\${ROSA_TOKEN}"
                    rosa delete cluster --cluster=${env.CLUSTER_NAME} --yes || true

                    if [ -f vpc_created.flag ]; then
                        aws cloudformation delete-stack --stack-name ${env.CLUSTER_NAME}-vpc --region ${env.REGION} || true
                    fi
                """
            }
        }
    } catch (Exception e) {
        echo "Cleanup failed: ${e.message}"
    }
}
