// A shell function that stops the GitOps controllers on cluster $1 and deletes its LoadBalancer
// Services, whose NLBs would otherwise fail eksctl's stack delete: Argo CD self-heal or Flux
// helm-controller recreates a Service eksctl removes. Best effort and time-bounded, since the
// cluster may be broken or unreachable; it never fails the delete.
String releaseLoadBalancersFunction() {
    return '''
        release_load_balancers() {
            command -v kubectl >/dev/null 2>&1 || { echo "kubectl not found, not releasing load balancers of $1"; return 0; }
            kc="${WORKSPACE}/kubeconfig-$1"
            if ! timeout 120 aws eks update-kubeconfig --name "$1" --region "${REGION}" --kubeconfig "${kc}"; then
                echo "No kubeconfig for $1, deleting it without releasing its load balancers."
                rm -f "${kc}"
                return 0
            fi
            if ! timeout 30 kubectl --kubeconfig "${kc}" get --raw /readyz --request-timeout=20s >/dev/null 2>&1; then
                echo "The API of $1 does not answer, deleting it without releasing its load balancers."
                rm -f "${kc}"
                return 0
            fi
            timeout 60 kubectl --kubeconfig "${kc}" scale statefulset argocd-application-controller \
                --namespace argocd --replicas=0 || true
            timeout 60 kubectl --kubeconfig "${kc}" scale deployment helm-controller \
                --namespace flux-system --replicas=0 || true
            timeout 60 kubectl --kubeconfig "${kc}" scale deployment kustomize-controller \
                --namespace flux-system --replicas=0 || true
            timeout 120 kubectl --kubeconfig "${kc}" wait --for=delete pod --namespace argocd \
                --selector app.kubernetes.io/name=argocd-application-controller --timeout=90s || true
            timeout 120 kubectl --kubeconfig "${kc}" wait --for=delete pod --namespace flux-system \
                --selector 'app in (helm-controller,kustomize-controller)' --timeout=90s || true
            services=$(timeout 60 kubectl --kubeconfig "${kc}" get services --all-namespaces \
                -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.metadata.namespace}{"/"}{.metadata.name}{" "}{end}') \
                || services=""
            for svc in ${services}; do
                timeout 300 kubectl --kubeconfig "${kc}" delete service --namespace "${svc%%/*}" "${svc#*/}" \
                    --wait=true --timeout=240s || true
            done
            rm -f "${kc}"
        }
    '''
}

pipeline {
    agent {
        label params.USE_ONDEMAND ? 'agent-amd64-ondemand' : 'agent-amd64'
    }

    triggers {
        cron('H 0,12 * * *') // Runs twice daily at 00:00 & 12:00
    }

    parameters {
        booleanParam(
            defaultValue: false,
            description: 'Use on-demand instances instead of spot (for RC/Release testing)',
            name: 'USE_ONDEMAND')
        choice(
            name: 'ACTION',
            choices: ['LIST_ONLY', 'DELETE_CLUSTER', 'DELETE_ALL'],
            description: '''
                LIST_ONLY - list all test clusters<br/>
                DELETE_CLUSTER - delete a specific cluster (requires CLUSTER_NAME)<br/>
                DELETE_ALL - delete all test clusters<br/><br/>
                Note: Daily cron automatically deletes clusters older than 1 day.
            '''
        )
        string(name: 'CLUSTER_NAME', defaultValue: '', description: 'Cluster name(s) for DELETE_CLUSTER. Supports comma-separated values (e.g., pmm-ha-test-1,pmm-ha-test-2)')
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    environment {
        REGION = "us-east-2"
        CLUSTER_PREFIX = "pmm-ha-test-"
    }

    stages {
        stage('Detect Run Type') {
            steps {
                script {
                    if (currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')) {
                        env.ACTION = 'DELETE_OLD'
                        echo "Triggered by cron - will delete clusters older than 1 day."
                    } else {
                        env.ACTION = params.ACTION
                        echo "Manual run with ACTION=${params.ACTION}"
                    }

                    if (env.ACTION == 'DELETE_CLUSTER' && !params.CLUSTER_NAME) {
                        error("CLUSTER_NAME is required for DELETE_CLUSTER.")
                    }
                    if (params.CLUSTER_NAME) {
                        params.CLUSTER_NAME.split(',').each { name ->
                            def trimmed = name.trim()
                            if (trimmed && !trimmed.startsWith(env.CLUSTER_PREFIX)) {
                                error("Cluster name '${trimmed}' must start with ${env.CLUSTER_PREFIX}")
                            }
                        }
                    }
                }
            }
        }

        stage('List Clusters') {
            when { expression { env.ACTION == 'LIST_ONLY' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh '''
                        set +x

                        CLUSTERS=$(aws eks list-clusters --region "$REGION" \
                            --query "clusters[?starts_with(@, '${CLUSTER_PREFIX}')]" \
                            --output text)

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        for c in $CLUSTERS; do
                            CREATED=$(aws eks describe-cluster \
                                --name "$c" --region "$REGION" \
                                --query "cluster.createdAt" --output text)

                            CREATED_EPOCH=$(date -d "$CREATED" +%s)
                            AGE_HOURS=$(( ( $(date +%s) - CREATED_EPOCH ) / 3600 ))

                            echo "• $c | Created: $CREATED | Age: ${AGE_HOURS}h"
                        done
                    '''
                }
            }
        }

        stage('Delete Cluster') {
            when { expression { env.ACTION == 'DELETE_CLUSTER' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh releaseLoadBalancersFunction() + '''
                        set -f
                        for c in $(echo "${CLUSTER_NAME}" | tr ',' ' '); do
                            if ! aws eks describe-cluster --region "${REGION}" --name "$c" >/dev/null 2>&1; then
                                echo "Cluster '$c' not found in region '${REGION}' - skipping."
                                continue
                            fi

                            echo "Deleting cluster: $c"
                            release_load_balancers "$c"
                            eksctl delete cluster --region "${REGION}" --name "$c" \
                                --disable-nodegroup-eviction --wait
                        done
                    '''
                }
            }
        }

        stage('Delete All Clusters') {
            when { expression { env.ACTION == 'DELETE_ALL' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh releaseLoadBalancersFunction() + '''
                        CLUSTERS=$(aws eks list-clusters --region "$REGION" \
                            --query "clusters[?starts_with(@, '${CLUSTER_PREFIX}')]" --output text)

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        for c in $CLUSTERS; do
                            release_load_balancers "$c"
                            eksctl delete cluster --region "$REGION" --name "$c" \
                                --disable-nodegroup-eviction --wait
                        done
                    '''
                }
            }
        }

        stage('Delete Old Clusters (cron only)') {
            when { expression { env.ACTION == 'DELETE_OLD' } }
            steps {
                withCredentials([aws(credentialsId: 'pmm-staging-slave')]) {
                    sh releaseLoadBalancersFunction() + '''
                        CLUSTERS=$(aws eks list-clusters --region "$REGION" \
                            --query "clusters[?starts_with(@, '${CLUSTER_PREFIX}')]" \
                            --output text)

                        if [ -z "$CLUSTERS" ]; then
                            echo "No clusters found with prefix '${CLUSTER_PREFIX}'."
                            exit 0
                        fi

                        NOW_EPOCH=$(date +%s)

                        for c in $CLUSTERS; do
                            DESC=$(aws eks describe-cluster \
                                --name "$c" \
                                --region "$REGION" \
                                --output json)

                            CREATED=$(echo "$DESC" | jq -r '.cluster.createdAt')
                            RETENTION=$(echo "$DESC" | jq -r '.cluster.tags["retention-days"]')

                            CREATED_EPOCH=$(date -d "$CREATED" +%s)
                            MAX_AGE_SECONDS=$(( RETENTION * 86400 ))
                            AGE_SECONDS=$(( NOW_EPOCH - CREATED_EPOCH ))

                            if [ "$AGE_SECONDS" -gt "$MAX_AGE_SECONDS" ]; then
                                release_load_balancers "$c"
                                eksctl delete cluster \
                                    --region "$REGION" \
                                    --name "$c" \
                                    --disable-nodegroup-eviction \
                                    --wait
                            else
                                echo "Keeping cluster $c (age < ${RETENTION} days)"
                            fi
                        done
                    '''
                }
            }
        }
    }
}
