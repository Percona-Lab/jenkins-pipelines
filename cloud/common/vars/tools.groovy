void gitClone(Map cfg) {
    def branch = cfg.branch
    def repo = cfg.repo

    echo "=========================[ Cloning sources ]========================="
    echo "Using branch: ${branch}"

    sh """
        set -e
        sudo git config --global --add safe.directory '*'
        sudo rm -rf source
        git clone -b "${branch}" "${repo}" source
    """
}

void gitResetWorkspace() {
    sh '''
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
    '''
}

def kubernetesCleanupCluster(String kubeconfig) {
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
