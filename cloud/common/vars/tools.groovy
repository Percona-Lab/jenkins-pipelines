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

void kubernetesCleanupFailedTestNamespaces(String kubeconfig, String testName, String operatorName) {
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
                        "$FAILED_TEST_NAME"-*|kuttl*|"${operatorName}"*)
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

void kubernetesArchiveClusterLogs(String kubeconfig, String testName, String operatorName) {
    def timestamp = sh(
        script: 'date +%Y%m%d-%H%M%S',
        returnStdout: true
    ).trim()

    def archiveName = "${testName}-${timestamp}.tgz"

    sh """
        export KUBECONFIG='${kubeconfig}'

        DIR=\$(mktemp -d)
        mkdir -p "\$DIR"/{logs,describe,templates,nodes}

        if [ ! -s "\$KUBECONFIG" ] || \
           ! kubectl get --raw=/healthz --request-timeout=5s >/dev/null 2>&1; then
            echo "Kubernetes API is not reachable"
            rm -rf "\$DIR"
            exit 0
        fi

        set +x

        for node in \$(kubectl get nodes -o name | cut -d/ -f2); do
            kubectl get node "\$node" -o yaml \
                > "\$DIR/nodes/\$node.yaml" 2>&1 || true

            kubectl describe node "\$node" \
                > "\$DIR/nodes/\$node.txt" 2>&1 || true
        done

        for namespace in \$(kubectl get namespaces -o name \
            | cut -d/ -f2 \
            | grep -E '^(${testName}|kuttl-|${operatorName})' || true); do

            echo "Collecting resources from namespace: \$namespace"

            mkdir -p \
                "\$DIR/logs/\$namespace" \
                "\$DIR/describe/\$namespace" \
                "\$DIR/templates/\$namespace"

            for resource in \$(kubectl api-resources \
                --namespaced=true \
                --verbs=list \
                -o name); do

                resourceDir=\$(echo "\$resource" | tr '/.' '__')

                mkdir -p \
                    "\$DIR/templates/\$namespace/\$resourceDir" \
                    "\$DIR/describe/\$namespace/\$resourceDir"

                for object in \$(kubectl get "\$resource" \
                    -n "\$namespace" \
                    -o name \
                    --ignore-not-found 2>/dev/null); do

                    objectName=\${object#*/}

                    kubectl get "\$object" \
                        -n "\$namespace" \
                        -o yaml \
                        > "\$DIR/templates/\$namespace/\$resourceDir/\$objectName.yaml" 2>&1 || true

                    kubectl describe "\$object" \
                        -n "\$namespace" \
                        > "\$DIR/describe/\$namespace/\$resourceDir/\$objectName.txt" 2>&1 || true
                done
            done

            for pod in \$(kubectl get pods \
                -n "\$namespace" \
                -o name \
                | cut -d/ -f2); do

                mkdir -p "\$DIR/logs/\$namespace/\$pod"

                for container in \$(kubectl get pod "\$pod" \
                    -n "\$namespace" \
                    -o jsonpath='{range .spec.initContainers[*]}{.name}{"\\n"}{end}{range .spec.containers[*]}{.name}{"\\n"}{end}'); do

                    kubectl logs "\$pod" \
                        -n "\$namespace" \
                        -c "\$container" \
                        --timestamps \
                        > "\$DIR/logs/\$namespace/\$pod/\$container.log" 2>&1 || true

                    kubectl logs "\$pod" \
                        -n "\$namespace" \
                        -c "\$container" \
                        --previous \
                        --timestamps \
                        > "\$DIR/logs/\$namespace/\$pod/\$container-previous.log" 2>&1 || true
                done
            done
        done

        set -x

        tar -czf '${archiveName}' \
            -C "\$DIR" \
            logs describe templates nodes

        rm -rf "\$DIR"
    """

    archiveArtifacts(
        artifacts: archiveName,
        allowEmptyArchive: true
    )
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
