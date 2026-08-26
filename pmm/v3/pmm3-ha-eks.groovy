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

// Writes ${WORKSPACE}/kubeconfig with a ServiceAccount token inside it, so the artifact works with a
// bare kubectl. The token never expires, so it stays valid until the cluster is deleted.
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

        # Check the file works with the AWS credentials taken away. Nothing else in this build
        # touches it, so this is its only test.
        env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
            kubectl --kubeconfig "${MINT_OUT}" get nodes
    '''
}

def cleanupCluster() {
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

pipeline {
    agent {
        label 'agent-amd64'
    }

    options {
        timeout(time: 90, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    parameters {
        choice(
            name: 'K8S_VERSION',
            choices: ['1.35', '1.34', '1.33'],
            description: 'Select Kubernetes cluster version'
        )
        choice(
            name: 'WORKER_COUNT',
            choices: ['6', '7', '8', '9', '10', '11', '12'],
            description: 'Worker nodes in the spot nodegroup. Each PMM replica requests 2 CPU / 3Gi, so raise this when overriding replicas via HELM_VALUES.'
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
            defaultValue: '',
            description: 'Extra pmm-ha chart overrides passed to helm --set, they win over every other parameter. Example: replicas=2,storage.size=20Gi'
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
        booleanParam(
            name: 'NOTIFY',
            defaultValue: true,
            description: 'Post the build status to #pmm-notifications and DM the build owner'
        )
    }

     environment {
        CLUSTER_NAME = "pmm-ha-test-${BUILD_NUMBER}"
        REGION = "us-east-2"
        // The build's own config. Kept out of the workspace so ${WORKSPACE}/kubeconfig is only the artifact.
        KUBECONFIG = "${HOME}/.kube/eks-${BUILD_NUMBER}"
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    // sets env.OWNER, used by notifySlack
                    getPMMBuildParams('pmm-ha-')
                    notifySlack('#0000FF', 'build started')
                }
            }
        }

        stage('Write Cluster Config') {
            steps {
                script {
                    // environment{} block vars are applied via withEnv and are only visible to steps in
                    // this pipeline - re-assign through env.X= so it's persisted on the build and exposed
                    // via buildVariables to any caller using build job: 'pmm3-ha-eks'.
                    env.CLUSTER_NAME = env.CLUSTER_NAME
                }
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

vpc:
  nat:
    gateway: Disable

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
    minSize: ${WORKER_COUNT}
    maxSize: 12
    desiredCapacity: ${WORKER_COUNT}
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

        stage('Grant pmm-qa GHA Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        # Granting the pmm-qa GitHub Actions OIDC role edit access scoped
                        # to the pmm namespace. QA workflows test against the PMM HA this
                        # job deploys. Cluster-scoped objects (operator CRDs, storage
                        # classes, nodes) stay with this job's credentials. The role name
                        # is defined by the gha_pmm_qa_eks module in percona-cd-platform.
                        # No fallback on purpose: a missing role fails the stage here
                        # instead of surfacing later as an opaque GHA auth error.
                        GHA_ROLE_ARN=$(aws iam get-role \
                            --role-name percona-ci-platform-gha-pmm-qa-eks \
                            --query Role.Arn --output text)

                        aws eks create-access-entry \
                            --cluster-name "${CLUSTER_NAME}" \
                            --region "${REGION}" \
                            --principal-arn "${GHA_ROLE_ARN}"

                        aws eks associate-access-policy \
                            --cluster-name "${CLUSTER_NAME}" \
                            --region "${REGION}" \
                            --principal-arn "${GHA_ROLE_ARN}" \
                            --policy-arn arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy \
                            --access-scope type=namespace,namespaces=pmm
                    '''
                }
            }
        }

        stage('Configure kubectl Access') {
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        mkdir -p "${HOME}/.kube"

                        aws eks update-kubeconfig \
                            --name "${CLUSTER_NAME}" \
                            --region "${REGION}" \
                            --kubeconfig "${KUBECONFIG}"

                        kubectl cluster-info
                        kubectl get nodes
                    '''

                    // kubectl still authenticates through IAM here - the ServiceAccount is what replaces it.
                    mintAdminKubeconfig()
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
            when {
                expression { params.DEPLOY_PMM }
            }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    dir('helm-charts') {
                        git poll: false, branch: params.HELM_CHART_BRANCH, url: 'https://github.com/percona/percona-helm-charts.git'
                    }

                    sh '''
                        helm repo add percona https://percona.github.io/percona-helm-charts/
                        helm repo add vm https://victoriametrics.github.io/helm-charts/
                        helm repo add altinity https://docs.altinity.com/helm-charts/
                        helm repo update

                        helm dependency update helm-charts/charts/pmm-ha-dependencies
                        helm upgrade --install pmm-operators helm-charts/charts/pmm-ha-dependencies -n pmm --create-namespace --wait --timeout 10m

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

                        helm upgrade --install pmm-ha helm-charts/charts/pmm-ha -n pmm \
                            --set secret.create=false \
                            --set secret.name=pmm-secret \
                            --wait --timeout 15m \
                            ${PMM_IMAGE_REPOSITORY:+--set image.repository=${PMM_IMAGE_REPOSITORY}} \
                            ${PMM_IMAGE_TAG:+--set image.tag=${PMM_IMAGE_TAG}} \
                            ${PMM_ENV_ARGS} \
                            ${HELM_VALUES:+--set ${HELM_VALUES}}

                        HELM_EXIT_CODE=$?

                        set -e

                        if [ "$HELM_EXIT_CODE" -ne 0 ]; then
                          echo "Helm failed — collecting diagnostics"

                          mkdir -p helm-debug

                          kubectl get pods -n pmm -o wide > helm-debug/pods.txt || true
                          kubectl get events -n pmm --sort-by=.metadata.creationTimestamp > helm-debug/events.txt || true

                          for pod in $(kubectl get pods -n pmm --no-headers | awk '{print $1}'); do
                            kubectl describe pod "$pod" -n pmm >> helm-debug/describe-$pod.txt || true

                            for container in $(kubectl get pod "$pod" -n pmm -o jsonpath='{.spec.containers[*].name}'); do
                              kubectl logs "$pod" -n pmm -c "$container" \
                                --tail=200 > "helm-debug/${pod}-${container}.log" || true
                            done
                          done

                          kubectl get statefulset pmm-ha -n pmm -o yaml > helm-debug/statefulset.yaml || true

                          exit $HELM_EXIT_CODE
                        fi

                        kubectl rollout status statefulset/pmm-ha -n pmm --timeout=600s
                        kubectl wait --for=condition=ready pod -l clickhouse.altinity.com/chi=pmm-ha -n pmm --timeout=600s
                        kubectl get pods -n pmm
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

                        echo "kubectl access (local):"
                        echo "  # Download the kubeconfig artifact - no aws CLI or credentials needed."
                        echo "  export KUBECONFIG=./kubeconfig"
                        echo ""

                        # Everything below describes PMM, so a bare cluster stops here.
                        [ "${DEPLOY_PMM}" = "true" ] || exit 0

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

                        echo "PMM access:"
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
                // env.PMM_URL is only set when the LoadBalancer stage ran.
                def pmmStatus = params.DEPLOY_PMM ? (env.PMM_URL ?: 'port-forward required') : 'not deployed'

                currentBuild.description = "Cluster: ${env.CLUSTER_NAME} | PMM: ${pmmStatus}"
                notifySlack('#00FF00', "cluster is ready, it expires in ${params.RETENTION_DAYS} day(s)\nPMM: ${pmmStatus}")
            }
            echo "Cluster ${CLUSTER_NAME} created successfully."
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
