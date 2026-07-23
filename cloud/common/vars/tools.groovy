void gitClone(Map cfg) {
    def branch = cfg.branch
    def repo = cfg.repo

    echo "=========================[ Cloning sources ]========================="
    echo "Using branch: ${branch}"

    withEnv([
        "GIT_BRANCH_NAME=${branch}",
        "GIT_REPO_URL=${repo}"
    ]) {
        sh '''
            set -e
            sudo git config --global --add safe.directory '*'
            sudo rm -rf source
            git clone -b "$GIT_BRANCH_NAME" "$GIT_REPO_URL" source
        '''
    }
}

void gitResetWorkspace() {
    sh '''
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
    '''
}

void kubernetesCleanupFailedTestNamespaces(String kubeconfig, String testName) {
    echo "Cleaning failed test namespaces for ${testName} on kubeconfig ${kubeconfig}"

    withEnv([
        "FAILED_TEST_NAME=${testName}",
        "KUBECONFIG=${kubeconfig}"
    ]) {
        sh(label: "Cleanup failed namespaces for ${testName}", script: '''
            #!/usr/bin/env bash
            set +x
            set +e

            if [ ! -s "$KUBECONFIG" ] || ! kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
                echo "Skipping failed test namespace cleanup: Kubernetes API is not reachable for $KUBECONFIG"
                exit 0
            fi

            kubectl get namespaces --request-timeout=10s --no-headers \
                | awk '{print $1}' \
                | while read -r namespace; do
                    case "$namespace" in
                        "$FAILED_TEST_NAME"-*|kuttl*)
                            echo "Removing finalizers from resources in namespace: $namespace"
                            kubectl api-resources --verbs=list --namespaced -o name --request-timeout=10s 2>/dev/null \
                                | grep -vE '^(events|events\\.events\\.k8s\\.io|endpoints)$' \
                                | while read -r resource; do
                                    kubectl get "$resource" -n "$namespace" -o name --ignore-not-found --request-timeout=10s 2>/dev/null \
                                        | while read -r object; do
                                            kubectl patch "$object" -n "$namespace" --type=merge -p '{"metadata":{"finalizers":[]}}' --request-timeout=10s >/dev/null 2>&1 || true
                                        done
                                done

                            echo "Deleting namespace: $namespace"
                            kubectl delete namespace "$namespace" --force --grace-period=0 --wait=false --request-timeout=10s >/dev/null 2>&1 || true
                            ;;
                    esac
                done
        ''')
    }
}

void kubernetesCleanupCluster(String kubeconfig) {
    sh """
        export KUBECONFIG=${kubeconfig}

        if [ -s "\$KUBECONFIG" ] && kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
            for namespace in \$(kubectl get namespaces --request-timeout=5s --no-headers \
                | awk '{print \$1}' \
                | grep -vE "^kube-|^gke-|^cattle-" \
                | sed '/-operator/ s/^/1-/' \
                | sort \
                | sed 's/^1-//'); do

                echo "Cleaning namespace: \$namespace"

                kubectl delete deployments --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete sts --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete replicasets --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete services --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                kubectl delete pods --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
            done
        else
            echo "Skipping namespace cleanup: Kubernetes API is not reachable for ${kubeconfig}"
        fi
    """
}

void kubernetesArchiveClusterLogs(String kubeconfig, String testName) {
    def archiveName = "${testName}-${sh(script: 'date +%Y%m%d-%H%M%S', returnStdout: true).trim()}.tgz"

    sh """
        export KUBECONFIG='${kubeconfig}'

        DIR=\$(mktemp -d)
        mkdir -p "\$DIR"/{logs,describe,templates,nodes}

        if [ ! -s "\$KUBECONFIG" ] || \
           ! kubectl get --raw=/healthz --request-timeout=5s >/dev/null 2>&1; then
            echo "Kubernetes API is not reachable"
            exit 0
        fi

        kubectl get nodes -o yaml > "\$DIR/nodes/nodes.yaml" 2>&1 || true
        kubectl describe nodes > "\$DIR/nodes/describe.txt" 2>&1 || true

        for namespace in \$(kubectl get ns -o name | cut -d/ -f2 | grep -E '^(${testName}|kuttl-)'); do
            mkdir -p "\$DIR"/{logs,describe,templates}/"\$namespace"

            kubectl get all -n "\$namespace" -o yaml \
                > "\$DIR/templates/\$namespace/all.yaml" 2>&1 || true

            kubectl get events -n "\$namespace" -o yaml \
                > "\$DIR/templates/\$namespace/events.yaml" 2>&1 || true

            kubectl describe pods -n "\$namespace" \
                > "\$DIR/describe/\$namespace/pods.txt" 2>&1 || true

            for pod in \$(kubectl get pods -n "\$namespace" -o name | cut -d/ -f2); do
                kubectl logs -n "\$namespace" "\$pod" --all-containers --timestamps \
                    > "\$DIR/logs/\$namespace/\$pod.log" 2>&1 || true

                kubectl logs -n "\$namespace" "\$pod" --all-containers --previous --timestamps \
                    > "\$DIR/logs/\$namespace/\$pod-previous.log" 2>&1 || true
            done
        done

        tar -czf '${archiveName}' -C "\$DIR" logs describe templates nodes
        rm -rf "\$DIR"
    """

    archiveArtifacts artifacts: archiveName, allowEmptyArchive: true
}

void dockerBuildAndPush(Map cfg) {
    echo "=========================[ Building and Pushing ${cfg.operatorImage} Docker image ]========================="

    withCredentials([usernamePassword(
        credentialsId: 'hub.docker.com',
        passwordVariable: 'PASS',
        usernameVariable: 'USER'
    )]) {
        sh """
            if [[ "\$IMAGE_OPERATOR" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source

                sg docker -c '
                    docker buildx create --use || true
                    echo "\$PASS" | docker login -u "\$USER" --password-stdin
                    export IMAGE=${cfg.operatorImage}:${cfg.branch}
                    e2e-tests/build
                    docker logout
                '

                sudo rm -rf build
            fi
        """
    }
}

void dockerCleanupVolumes() {
    sh """
        sudo docker system prune --volumes -af
    """
}

return this
