def getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

def getLatestPlatformVersion(Map testVariables) {
    return testVariables.platform_version
}

def getMachineType(String arch) {
    return arch
}

void createCluster(Map clusterCfg) {
    sh """
        export CHANGE_MINIKUBE_NONE_USER=true
        minikube start --kubernetes-version ${clusterCfg.platformVersion} --cpus=6 --memory=28G --force
    """
}

return this
