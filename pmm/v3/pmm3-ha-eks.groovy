pipeline {
    agent {
        label 'agent-amd64-ol9'
    }

    parameters {
        choice(
            name: 'K8S_VERSION',
            choices: ['1.34', '1.33', '1.32', '1.31', '1.30'],
            description: 'Select Kubernetes cluster version'
        )
        string(
            name: 'HELM_CHART_BRANCH',
            defaultValue: 'pmmha-v3',
            description: 'Branch of percona-helm-charts repo'
        )
        string(
            name: 'PMM_IMAGE_TAG',
            defaultValue: '',
            description: 'PMM Server image tag'
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
            description: 'Enable external access for PMM HA (creates LoadBalancer)'
        )
    }

     environment {
        CLUSTER_NAME = "pmm-ha-test-${BUILD_NUMBER}"
        REGION = "us-east-2"
        KUBECONFIG = "${WORKSPACE}/kubeconfig"
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
    retention-days: "${RETENTION_DAYS}"
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
      - c5a.xlarge
      - c8a.xlarge
      - c8i.xlarge
      - c7i-flex.xlarge
      - c8i-flex.xlarge
    volumeSize: 80
    spot: true
    minSize: 4
    maxSize: 6
    desiredCapacity: 4
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
                    '''
                }
            }
        }

        stage('Configure Cluster Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        grant_admin() {
                            local arn="$1"

                            aws eks create-access-entry \
                                --cluster-name "${CLUSTER_NAME}" \
                                --region "${REGION}" \
                                --principal-arn "${arn}"

                            aws eks associate-access-policy \
                                --cluster-name "${CLUSTER_NAME}" \
                                --region "${REGION}" \
                                --principal-arn "${arn}" \
                                --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \
                                --access-scope type=cluster
                        }

                        # Granting access to IAM group members
                        for arn in $(aws iam get-group --group-name pmm-eks-admins --query 'Users[].Arn' --output text); do
                            grant_admin "${arn}"
                        done

                        # Granting access to SSO admin role
                        grant_admin $(aws iam list-roles --query "Roles[?contains(RoleName,'AWSReservedSSO_AdministratorAccess')].Arn|[0]" --output text | head -1)
                    '''
                }
            }
        }

        stage('Export kubeconfig') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
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

        stage('Install PMM HA') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    git poll: false, branch: HELM_CHART_BRANCH, url: 'https://github.com/percona/percona-helm-charts.git'

                    sh '''
                        helm repo add percona https://percona.github.io/percona-helm-charts/
                        helm repo add vm https://victoriametrics.github.io/helm-charts/
                        helm repo add altinity https://docs.altinity.com/helm-charts/
                        helm repo update

                        helm dependency update charts/pmm-ha-dependencies
                        helm upgrade --install pmm-operators charts/pmm-ha-dependencies -n pmm --create-namespace --wait --timeout 10m

                        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=victoria-metrics-operator -n pmm --timeout=300s
                        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=altinity-clickhouse-operator -n pmm --timeout=300s
                        kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=pg-operator -n pmm --timeout=300s

                        if [ -n "${PMM_ADMIN_PASSWORD}" ]; then
                            PMM_PW="${PMM_ADMIN_PASSWORD}"
                        else
                            PMM_PW="$(openssl rand -base64 16 | tr -dc 'a-zA-Z0-9' | head -c 16)"
                        fi
                        PG_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
                        GF_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
                        CH_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)
                        VM_PW=$(openssl rand -base64 24 | tr -dc 'a-zA-Z0-9' | head -c 24)

                        kubectl create secret generic pmm-secret -n pmm \
                            --from-literal=PMM_ADMIN_PASSWORD="${PMM_PW}" \
                            --from-literal=GF_SECURITY_ADMIN_PASSWORD="${PMM_PW}" \
                            --from-literal=PG_PASSWORD="${PG_PW}" \
                            --from-literal=GF_PASSWORD="${GF_PW}" \
                            --from-literal=PMM_CLICKHOUSE_USER="clickhouse_pmm" \
                            --from-literal=PMM_CLICKHOUSE_PASSWORD="${CH_PW}" \
                            --from-literal=VMAGENT_remoteWrite_basicAuth_username="victoriametrics_pmm" \
                            --from-literal=VMAGENT_remoteWrite_basicAuth_password="${VM_PW}" \
                            --dry-run=client -o yaml | kubectl apply -f -

                        helm dependency update charts/pmm-ha
                        helm upgrade --install pmm-ha charts/pmm-ha -n pmm \
                            --set secret.create=false \
                            --set secret.name=pmm-secret \
                            --wait --timeout 15m \
                            ${PMM_IMAGE_TAG:+--set image.tag=${PMM_IMAGE_TAG}}  # Only add if PMM_IMAGE_TAG is non-empty

                        kubectl rollout status statefulset/pmm-ha -n pmm --timeout=600s
                        kubectl wait --for=condition=ready pod -l clickhouse.altinity.com/chi=pmm-ha -n pmm --timeout=600s
                        kubectl get pods -n pmm
                    '''
                }
            }
        }

        stage('Configure External Access') {
            when {
                expression { params.ENABLE_EXTERNAL_ACCESS }
            }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    script {
                        sh '''
                            kubectl patch svc pmm-ha-haproxy -n pmm --type='merge' -p '{
                                "spec": {
                                    "type": "LoadBalancer"
                                },
                                "metadata": {
                                    "annotations": {
                                    "service.beta.kubernetes.io/aws-load-balancer-type": "nlb",
                                    "service.beta.kubernetes.io/aws-load-balancer-scheme": "internet-facing"
                                    }
                                }
                            }'

                            echo "Waiting for LoadBalancer hostname..."
                            sleep 120
                        '''

                        def lbHost = sh(
                            returnStdout: true,
                            script: '''
                                kubectl get svc pmm-ha-haproxy -n pmm \
                                -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
                            '''
                        ).trim()

                        env.PMM_URL = "https://${lbHost}"
                    }
                }
            }
        }

        stage('Cluster Summary') {
            steps {
                script {
                    env.PMM_URL = env.PMM_URL ?: "https://localhost:8443"
                }
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x

                        echo "EKS Cluster Summary"
                        echo "------------------------------"

                        echo "Name:    ${CLUSTER_NAME}"
                        echo "Version: ${K8S_VERSION}"
                        echo "Region:  ${REGION}"
                        echo "Build:   ${BUILD_NUMBER}"
                        echo ""

                        kubectl get nodes -L node.kubernetes.io/instance-type -o wide
                        echo ""
                        kubectl get storageclass
                        echo ""

                        echo "Internal Component Credentials"
                        echo "------------------------------"

                        get_secret() {
                            kubectl get secret pmm-secret -n pmm \
                                -o "jsonpath={.data.$1}" 2>/dev/null | base64 --decode
                        }
                        echo "PMM/Grafana:     admin / $(get_secret PMM_ADMIN_PASSWORD)"
                        echo "PostgreSQL:      $(get_secret PG_PASSWORD)"
                        echo "ClickHouse:      $(get_secret PMM_CLICKHOUSE_USER) / $(get_secret PMM_CLICKHOUSE_PASSWORD)"
                        echo "VictoriaMetrics: $(get_secret VMAGENT_remoteWrite_basicAuth_username) / $(get_secret VMAGENT_remoteWrite_basicAuth_password)"
                        echo ""

                        echo "Access Information"
                        echo "------------------------------"

                        echo "kubectl access (local):"
                        echo "  aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${REGION}"
                        echo "  kubectl port-forward svc/pmm-ha-haproxy 8443:443 -n pmm"
                        echo "  # Then access https://localhost:8443"
                        echo ""

                        if [ "${ENABLE_EXTERNAL_ACCESS}" = "true" ]; then
                            echo "External Access (LoadBalancer)"
                            echo "------------------------------"

                            echo "  ${PMM_URL}"
                        fi
                    '''
                }
            }
        }

        stage('Archive kubeconfig') {
            steps {
                archiveArtifacts artifacts: 'kubeconfig', fingerprint: true
                archiveArtifacts artifacts: 'cluster-config.yaml', fingerprint: true
            }
        }
    }

    post {
        success {
            script {
                currentBuild.description = "PMM: ${env.PMM_URL}"
            }
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
