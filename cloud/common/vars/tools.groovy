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

    stash name: 'sourceFILES', includes: 'source/**'
}

void stashClonedGitFiles() {
    stash includes: 'source/**', name: 'sourceFILES', useDefaultExcludes: false
}

void unstashClonedGitFiles() {
    deleteDir()
    checkout scm
    unstash 'sourceFILES'
}

void gitResetWorkspace() {
    sh '''
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
    '''
}

void kubernetesCleanupCluster(String kubeconfig) {
    sh """
        export KUBECONFIG=${kubeconfig}

        if [ -s "\$KUBECONFIG" ] && kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
            for namespace in \$(kubectl get namespaces --request-timeout=5s --no-headers \
                | awk '{print \$1}' \
                | grep -vE "^kube-|^gke-|^cattle-|^openshift" \
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

void kubernetesCleanupFailedTestNamespaces(Map testVariables, String testName, String clusterSuffix) {
    def clusterName = "${testVariables.cluster_name}-${clusterSuffix}"
    def kubeconfig = "${testVariables.kubeconfigPath}/${clusterName}"

    echo "Cleaning failed test namespaces for ${testName} on ${clusterName}"

    sh """
        set +e
        export FAILED_TEST_NAME='${testName}'
        export KUBECONFIG='${kubeconfig}'
        if [ ! -s "\$KUBECONFIG" ] || ! kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
            echo "Skipping failed test namespace cleanup: Kubernetes API is not reachable for \$KUBECONFIG"
            exit 0
        fi
        kubectl get namespaces --request-timeout=10s --no-headers \
            | awk '{print \$1}' \
            | while read -r namespace; do
                case "\$namespace" in
                    "\$FAILED_TEST_NAME"-*|kuttl*)
                        echo "Removing finalizers from resources in namespace: \$namespace"
                        kubectl api-resources --verbs=list --namespaced -o name --request-timeout=10s \
                            | while read -r resource; do
                                kubectl get "\$resource" -n "\$namespace" -o name --ignore-not-found --request-timeout=10s 2>/dev/null \
                                    | while read -r object; do
                                        kubectl patch "\$object" -n "\$namespace" --type=merge -p '{"metadata":{"finalizers":[]}}' --request-timeout=10s || true
                                    done
                            done
                        echo "Deleting namespace: \$namespace"
                        kubectl delete namespace "\$namespace" --force --grace-period=0 --wait=false --request-timeout=10s || true
                        ;;
                esac
            done
    """
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
                    docker buildx use multiarch 2>/dev/null || docker buildx create --name multiarch --use
                    echo "\$PASS" | docker login -u "\$USER" --password-stdin
                    export IMAGE=${cfg.operatorImage}:${cfg.branch}
                    if [[ "$cfg.operator" == "pg-operator" ]]; then
                        DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 make build
                    else
                        DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 e2e-tests/build
                    fi
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
