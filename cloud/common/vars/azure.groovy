void auth() {
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
        sh '''
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID" --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
        '''
    }
}

def getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

def getLatestPlatformVersion(Map testVariables) {
    return sh(
        script: "az aks get-versions --location ${testVariables.region} --output json | jq -r '(.values // [] | .[].patchVersions | keys[]), (.orchestrators // [] | .[].orchestratorVersion)' | sort --version-sort | tail -1",
        returnStdout: true
    ).trim()
}

def getMachineType(String arch) {
    return arch
}

void createCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        sh """
            export KUBECONFIG=/tmp/${clusterFullName}
            az aks create -n ${clusterFullName} \
                -g percona-operators \
                --subscription eng-cloud-dev \
                --load-balancer-sku standard \
                --enable-managed-identity \
                --node-count 3 \
                --node-vm-size Standard_B4ms \
                --node-osdisk-size 30 \
                --generate-ssh-keys \
                --outbound-type loadbalancer \
                --kubernetes-version ${clusterCfg.platformVersion} \
                --tags team=cloud delete-cluster-after-hours=6 creation-time=\$(date -u +%s) \
                -l ${clusterCfg.region}
            az aks get-credentials --subscription eng-cloud-dev --resource-group percona-operators --name ${clusterFullName} --overwrite-existing
        """
    }
}

void shutdownCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
            sh """
                az aks delete --name ${clusterFullName} --resource-group percona-operators --subscription eng-cloud-dev --yes || true
            """
        }
    }
}

return this
