String getPlatformVersion(String version, Map libraries) {
    return version
}

String getLatestPlatformVersion(String location, String channel, Map libraries) {
    return sh(
        script: "az aks get-versions --location ${location} --output json | jq -r '(.values // [] | .[].patchVersions | keys[]), (.orchestrators // [] | .[].orchestratorVersion)' | sort --version-sort | tail -1",
        returnStdout: true
    ).trim()
}

String getMachineType(String arch) {
    error("Architecture selection is not supported for AKS: ${arch}")
}

void createCluster(Map clusterCfg, Map libraries) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    libraries.credentials.withAzureCredentials {
        libraries.helpers.retryCommand(
            command: """
                timeout 30m bash -c '
                    az aks create -n ${clusterFullName} \\
                        -g percona-operators \\
                        --subscription eng-cloud-dev \\
                        --load-balancer-sku standard \\
                        --enable-managed-identity \\
                        --node-count 3 \\
                        --node-vm-size Standard_B4ms \\
                        --node-osdisk-size 30 \\
                        --generate-ssh-keys \\
                        --outbound-type loadbalancer \\
                        --kubernetes-version ${clusterCfg.platformVersion} \\
                        --tags team=cloud delete-cluster-after-hours=6 creation-time=\$(date -u +%s) \\
                        -l ${clusterCfg.region}
                '
            """,
            afterCommand: """
                export KUBECONFIG=${clusterCfg.kubeconfig}
                az aks get-credentials --subscription eng-cloud-dev --resource-group percona-operators --name ${clusterFullName} --overwrite-existing
                
                # Wait for API server to be fully ready
                for i in \$(seq 1 60); do
                    if kubectl cluster-info >/dev/null 2>&1 && kubectl get nodes >/dev/null 2>&1; then
                        echo "Cluster API server is ready"
                        break
                    fi
                    echo "Waiting for cluster API server... (\$i/60)"
                    sleep 10
                done
                
                kubectl get nodes
            """,
            cleanupFunction: {
                shutdownCluster(clusterCfg, libraries)
            },
            maxRetries: 3,
            delaySeconds: 5
        )
    }
}

void shutdownCluster(Map clusterCfg, Map libraries) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        libraries.credentials.withAzureCredentials {
            sh """
                az aks delete --name ${clusterFullName} --resource-group percona-operators --subscription eng-cloud-dev --yes || true
            """
        }
    }
}

return this
