String getPlatformVersion(String version, Map testVariables = [:]) {
    if (version?.toLowerCase() != 'rel') {
        return version
    }

    return sh(
        script: "grep -iE '^\\s*MINIKUBE_REL=' ${testVariables.release_versions} | cut -d = -f 2 | tr -d '\"' | tail -1",
        returnStdout: true
    ).trim()
}

void createCluster(Map clusterCfg) {
    sh """
        echo "Creating cluster ${clusterCfg.clusterSuffix}"
        export CHANGE_MINIKUBE_NONE_USER=true
        export KUBECONFIG=${clusterCfg.kubeconfig}
        minikube start --kubernetes-version ${clusterCfg.platformVersion} --cpus=6 --memory=28G --force
    """
}

void shutdownCluster(Map clusterCfg) {
    sh 'minikube delete || true'
}

return this
