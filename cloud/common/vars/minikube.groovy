String getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

String getLatestPlatformVersion(Map testVariables) {
    return testVariables.platform_version
}

String getMachineType(String arch) {
    error("Architecture selection is not supported for Minikube: ${arch}")
}

void createCluster(Map clusterCfg, Map libraries) {
    libraries.helpers.retryCommand(
        command: '''
            timeout 30m bash -c '
                export CHANGE_MINIKUBE_NONE_USER=true
                minikube start --kubernetes-version ''' + clusterCfg.platformVersion + ''' --cpus=6 --memory=28G --force
            '
        ''',
        afterCommand: '''
            kubectl get nodes
        ''',
        cleanupFunction: {
            shutdownCluster(clusterCfg, libraries)
        },
        maxRetries: 3,
        delaySeconds: 5
    )
}

void shutdownCluster(Map clusterCfg, Map libraries) {
    sh '''
        minikube delete || true
    '''
}

return this
