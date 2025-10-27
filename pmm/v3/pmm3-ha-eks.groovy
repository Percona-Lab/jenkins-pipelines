pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    parameters {
        choice(
            name: 'K8S_VERSION',
            choices: ['1.32', '1.31', '1.30', '1.29', '1.28'],
            description: 'Select Kubernetes cluster version'
        )
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'pmmha-v3',
            description: 'Branch name for percona-helm-charts repository'
        )
    }

     environment {
        CLUSTER_NAME = "pmm-ha-test-${BUILD_NUMBER}"
        REGION = "us-east-2"
        KUBECONFIG = "${WORKSPACE}/kubeconfig/config"
    }

    stages {
        stage('Write Cluster Config') {
            steps {
                sh '''
                    cat > ClusterConfig.yaml <<EOF
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
  name: "${CLUSTER_NAME}"
  region: "${REGION}"
  version: "${K8S_VERSION}"
  tags:
    iit-billing-tag: "pmm"
    created-by: "jenkins"
    build-number: "${BUILD_NUMBER}"
    purpose: "pmm-ha-testing"

iam:
  withOIDC: true

addons:
  - name: aws-ebs-csi-driver
    wellKnownPolicies:
      ebsCSIController: true

managedNodeGroups:
  - name: ng-spot
    amiFamily: AmazonLinux2023
    instanceTypes:
      - m5a.large
      - m5n.large
      - m7a.large
      - m7i-flex.large
    volumeSize: 80
    spot: true
    minSize: 2
    maxSize: 5
    desiredCapacity: 3
    tags:
        iit-billing-tag: "pmm"
        nodegroup: "spot"
    labels:
        workload: "pmm-ha-test"
EOF
                '''
            }
        }

        stage('Check Existing Clusters') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        echo "Checking existing clusters with prefix 'pmm-ha-test-'"
                        EXISTING_CLUSTERS=$(aws eks list-clusters --region "${REGION}" --query "clusters[?starts_with(@, 'pmm-ha-test-')]" --output text)
                        EXISTING_COUNT=$(echo "$EXISTING_CLUSTERS" | wc -w)

                        echo "Found $EXISTING_COUNT existing clusters:"
                        echo "$EXISTING_CLUSTERS" | tr '\t' '\n'

                        if [ "$EXISTING_COUNT" -ge 5 ]; then
                            echo ""
                            echo "ERROR: Maximum limit of 5 test clusters reached!"
                            echo "Please delete one of the existing clusters before creating a new one."
                            exit 1
                        fi

                        echo ""
                        echo "Proceeding with cluster creation (${EXISTING_COUNT}/5 slots used)"
                    '''
                }
            }
        }

        stage('Create EKS Cluster') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        echo "Creating EKS cluster: ${CLUSTER_NAME}"
                        echo "Kubernetes version: ${K8S_VERSION}"
                        echo "Region: ${REGION}"

                        eksctl create cluster -f ClusterConfig.yaml --timeout=40m --verbose=4

                        echo "Creating IAM identity mapping for SSO admin access..."
                        eksctl create iamidentitymapping \
                            --cluster ${CLUSTER_NAME} \
                            --region ${REGION} \
                            --arn arn:aws:iam::119175775298:role/AWSReservedSSO_AdministratorAccess_5922b1e9e802dfa5 \
                            --username admin \
                            --group system:masters
                    '''
                }
            }
        }

        stage('Export kubeconfig') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        echo "Exporting kubeconfig for cluster: ${CLUSTER_NAME}"
                        mkdir -p kubeconfig
                        aws eks update-kubeconfig \
                            --name "${CLUSTER_NAME}" \
                            --region "${REGION}" \
                            --kubeconfig "${KUBECONFIG}"

                        echo "Verifying cluster access..."
                        kubectl cluster-info
                        kubectl get nodes
                    '''
                }
            }
        }

        stage('Configure GP3 Storage Class') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        echo "Waiting for all nodes to be ready..."
                        kubectl wait --for=condition=Ready nodes --all --timeout=300s

                        echo "Checking EBS CSI driver status..."
                        kubectl get pods -n kube-system -l app.kubernetes.io/name=aws-ebs-csi-driver
                        kubectl wait --for=condition=Ready pods -n kube-system -l app.kubernetes.io/name=aws-ebs-csi-driver --timeout=300s

                        echo "Configuring storage classes..."
                        kubectl patch storageclass gp2 -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}'

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

                        echo "Verifying storage classes..."
                        kubectl get storageclass
                    '''
                }
            }
        }

        stage('Install Node Termination Handler') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        echo "Installing AWS Node Termination Handler..."
                        helm repo add eks https://aws.github.io/eks-charts
                        helm repo update
                        
                        helm upgrade --install aws-node-termination-handler \
                            eks/aws-node-termination-handler \
                            --namespace kube-system \
                            --set enableSpotInterruptionDraining=true \
                            --set enableScheduledEventDraining=true \
                            --wait

                        echo "Verifying Node Termination Handler deployment..."
                        kubectl get pods -n kube-system -l app.kubernetes.io/name=aws-node-termination-handler
                        kubectl wait --for=condition=Ready pods -n kube-system -l app.kubernetes.io/name=aws-node-termination-handler --timeout=180s
                    '''
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        echo "=========================================="
                        echo "EKS CLUSTER CREATED SUCCESSFULLY"
                        echo "=========================================="
                        echo "Cluster Name: ${CLUSTER_NAME}"
                        echo "K8s Version: ${K8S_VERSION}"
                        echo "Region: ${REGION}"
                        echo "Build Number: ${BUILD_NUMBER}"
                        echo ""
                        echo "Nodes:"
                        kubectl get nodes -o wide
                        echo ""
                        echo "Storage Classes:"
                        kubectl get storageclass
                        echo ""
                        echo "To use this cluster, download the kubeconfig artifact"
                        echo "and set: export KUBECONFIG=/path/to/kubeconfig/config"
                        echo "=========================================="
                    '''
                }
            }
        }

        stage('Archive kubeconfig') {
            steps {
                archiveArtifacts artifacts: 'kubeconfig/config', fingerprint: true
                archiveArtifacts artifacts: 'ClusterConfig.yaml', fingerprint: true
            }
        }
    }

    post {
        success {
            echo "Cluster ${CLUSTER_NAME} created successfully!"
            echo "Download the kubeconfig artifact to access the cluster"
        }
        failure {
            echo "Pipeline failed! Attempting cleanup..."
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                                  credentialsId: 'pmm-staging-slave',
                                  accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                  secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                sh '''
                    if eksctl get cluster --region "${REGION}" --name "${CLUSTER_NAME}" >/dev/null 2>&1; then
                        echo "Cluster exists, initiating deletion..."
                        eksctl delete cluster --region "${REGION}" --name "${CLUSTER_NAME}" --disable-nodegroup-eviction --wait
                    else
                        echo "Cluster does not exist or already deleted"
                    fi
                '''
            }
        }
    }
}
