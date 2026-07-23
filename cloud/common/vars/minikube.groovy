String getLatestPlatformVersion(String channel = '') {
    return sh(
        script: "curl -fsSL https://dl.k8s.io/release/stable.txt",
        returnStdout: true
    ).trim()
}

String getPlatformVersion(String version) {
    if (!version || version == "latest") {
        return getLatestPlatformVersion()
    }

    if (version ==~ /^[0-9]+\\.[0-9]+(\\.[0-9]+)?$/) {
        return "v${version}"
    }

    return version
}

void createCluster(Map cfg) {
    sh """
        set -euo pipefail

        export CHANGE_MINIKUBE_NONE_USER=true
        export KUBECONFIG='${cfg.kubeconfig}'

        mkdir -p "\$(dirname '${cfg.kubeconfig}')"

        minikube start \
            -p '${cfg.clusterName}-${cfg.clusterSuffix}' \
            --kubernetes-version '${cfg.platformVersion}' \
            --cpus='${cfg.cpus ?: 6}' \
            --memory='${cfg.memory ?: "28G"}' \
            --force \
            --driver=docker

        minikube ssh -p '${cfg.clusterName}-${cfg.clusterSuffix}' -- 'cat /etc/hosts' \
            > minikube-etc-hosts.txt

        minikube logs -p '${cfg.clusterName}-${cfg.clusterSuffix}' \
            --file=minikube-debug.log
    """

    archiveArtifacts(
        artifacts: 'minikube-debug.log,minikube-etc-hosts.txt',
        allowEmptyArchive: true,
        fingerprint: true
    )
}

void shutdownCluster(Map cfg) {
    sh """
        minikube delete -p '${cfg.clusterName}-${cfg.clusterSuffix}' || true
        rm -f '${cfg.kubeconfig}' || true
    """
}

return this
