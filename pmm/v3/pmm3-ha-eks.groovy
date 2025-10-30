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
                    cat > cluster-config.yaml <<EOF
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
                        set +x

                        EXISTING_CLUSTERS=$(aws eks list-clusters --region "${REGION}" \
                            --query "clusters[?starts_with(@, 'pmm-ha-test-')]" --output text)

                        if [ -z "$EXISTING_CLUSTERS" ]; then
                            EXISTING_COUNT=0
                        else
                            EXISTING_COUNT=$(echo "$EXISTING_CLUSTERS" | wc -w)
                            echo "$EXISTING_CLUSTERS" | tr '\\t' '\\n'
                        fi

                        if [ "$EXISTING_COUNT" -ge 5 ]; then
                            echo "ERROR: Maximum limit of 5 test clusters reached."
                            exit 1
                        fi

                        echo "Existing clusters: $EXISTING_COUNT / 5"
                    '''
                }
            }
        }

        stage('Create EKS Cluster') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        eksctl create cluster -f cluster-config.yaml --timeout=40m --verbose=4

                        eksctl create iamidentitymapping \
                            --cluster "${CLUSTER_NAME}" \
                            --region "${REGION}" \
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
                        mkdir -p kubeconfig

                        aws eks update-kubeconfig \
                            --name "${CLUSTER_NAME}" \
                            --region "${REGION}" \
                            --kubeconfig "${KUBECONFIG}"

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

                        kubectl get storageclass
                    '''
                }
            }
        }

        stage('Install Node Termination Handler') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        helm repo add eks https://aws.github.io/eks-charts
                        helm repo update

                        helm upgrade --install aws-node-termination-handler \
                            eks/aws-node-termination-handler \
                            --namespace kube-system \
                            --set enableSpotInterruptionDraining=true \
                            --set enableScheduledEventDraining=true \
                            --wait

                        kubectl get pods -n kube-system -l app.kubernetes.io/name=aws-node-termination-handler
                    '''
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x

                        echo "EKS Cluster Summary"
                        echo "-------------------"
                        echo "Name:    ${CLUSTER_NAME}"
                        echo "Version: ${K8S_VERSION}"
                        echo "Region:  ${REGION}"
                        echo "Build:   ${BUILD_NUMBER}"
                        echo ""

                        kubectl get nodes -o wide
                        echo ""
                        kubectl get storageclass
                        echo ""

                        echo "To access this cluster, run:"
                        echo "aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${REGION}"
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
            echo "Cluster ${CLUSTER_NAME} created successfully."
            echo "Download the kubeconfig artifact to access the cluster."
        }
        failure {
            withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                sh '''
                    if eksctl get cluster \
                        --region "${REGION}" \
                        --name "${CLUSTER_NAME}" >/dev/null 2>&1
                    then
                        eksctl delete cluster \
                            --region "${REGION}" \
                            --name "${CLUSTER_NAME}" \
                            --disable-nodegroup-eviction \
                            --wait
                    fi
                '''
            }
        }
    }
}
