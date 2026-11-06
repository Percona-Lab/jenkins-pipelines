void createCluster(Map clusterCfg) {
    if (!clusterCfg.kubeconfig) {
        error('NKP kubeconfig destination is required')
    }
    def clusterName = clusterCfg.clusterSuffix == 'cluster1' ? 'percona-cluster-1' : 'percona-cluster-2'

    removeKubeconfig(clusterCfg)
    def credentials = load('cloud/common/vars/credentials.groovy')
    credentials.withNkpKubeconfigCredentials(clusterName) {
        withEnv([
            "KUBECONFIG=${clusterCfg.kubeconfig}",
            "NKP_EXPECTED_CLUSTER=${clusterName}"
        ]) {
            sh '''
            set -eu
            set +x
            umask 077
            test -s "$NKP_KUBECONFIG_FILE"
            mkdir -p "$(dirname "$KUBECONFIG")"
            cp "$NKP_KUBECONFIG_FILE" "$KUBECONFIG.pending"
            chmod 600 "$KUBECONFIG.pending"
            kubectl --kubeconfig="$KUBECONFIG.pending" config view --minify -o json |
                jq -e --arg expected "$NKP_EXPECTED_CLUSTER" \
                    '.contexts | length == 1 and .[0].context.cluster == $expected' >/dev/null
            mv "$KUBECONFIG.pending" "$KUBECONFIG"
            kubectl --request-timeout=15s get --raw=/readyz >/dev/null
            kubectl wait --for=condition=Ready nodes --all --timeout=120s
            touch "$KUBECONFIG.ready"
            '''
        }
    }
}

void shutdownCluster(Map clusterCfg) {
    if (!clusterCfg.kubeconfig) {
        error('NKP kubeconfig destination is required')
    }
    withEnv(["KUBECONFIG=${clusterCfg.kubeconfig}"]) {
        try {
            sh '''
            set -eu
            set +x
            if [ ! -s "$KUBECONFIG" ] || [ ! -f "$KUBECONFIG.ready" ]; then
                echo 'Skipping NKP cleanup: cluster preparation did not complete'
                exit 0
            fi
            kubectl --request-timeout=15s get namespaces -o json > "$KUBECONFIG.namespaces.current"
            jq -r '
                [
                    "cert-manager",
                    "cilium-secrets",
                    "container-object-storage-system",
                    "default",
                    "kommander-default-workspace",
                    "kommander-flux",
                    "kube-federation-system",
                    "kube-node-lease",
                    "kube-public",
                    "kube-system",
                    "metallb-system",
                    "node-feature-discovery",
                    "ntnx-system"
                ] as $protected |
                .items[] |
                .metadata.name |
                select(. as $name | $protected | index($name) | not)
            ' "$KUBECONFIG.namespaces.current" > "$KUBECONFIG.namespaces.delete"
            while IFS= read -r namespace; do
                echo "Cleaning NKP test namespace: $namespace"
                kubectl delete namespace "$namespace" --ignore-not-found --timeout=180s
            done < "$KUBECONFIG.namespaces.delete"
            '''
        } finally {
            removeKubeconfig(clusterCfg)
        }
    }
}

void removeKubeconfig(Map clusterCfg) {
    withEnv(["NKP_CONFIG=${clusterCfg.kubeconfig}"]) {
        sh '''
            set -eu
            set +x
            rm -f "$NKP_CONFIG" "$NKP_CONFIG.pending" "$NKP_CONFIG.ready" \
                "$NKP_CONFIG.namespaces.current" \
                "$NKP_CONFIG.namespaces.delete"
        '''
    }
}

String getPlatformVersion(String version) {
    if (!(version ==~ /v?\d+\.\d+\.\d+([+-].*)?/)) {
        error('NKP uses existing clusters; provide their explicit Kubernetes version')
    }
    return version
}

String getLatestPlatformVersion(Map config) {
    error('NKP cannot provision a latest version; provide the existing Kubernetes version')
}

return this