void auth() {
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
        sh '''
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID" --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
        '''
    }
}

String getLocation(String jobName, String primaryLocation = 'eastus', String secondaryLocation = 'norwayeast') {
    return jobName.endsWith('-1') ? primaryLocation : secondaryLocation
}

String getPlatformVersion(String version, Map testVariables = [:]) {
    if (version != 'latest') {
        return version
    }

    def location = testVariables.region ?: 'eastus'

    return sh(
        script: "az aks get-versions --location ${location} --output json | jq -r '.values | max_by(.patchVersions) | .patchVersions | keys[]' | sort --version-sort | tail -1",
        returnStdout: true
    ).trim()
}

void createCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        sh """
            export KUBECONFIG=${clusterCfg.kubeconfig}
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
        sh """
            az aks delete --name ${clusterFullName} --resource-group percona-operators --subscription eng-cloud-dev --yes || true
        """
    }
}

return this
