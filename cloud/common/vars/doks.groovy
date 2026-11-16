String getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

String getLatestPlatformVersion(Map testVariables) {
    def result = ""
    testVariables.libraries.credentials.withDOKSCredentials {
        result = sh(
            script: "doctl kubernetes options versions | awk 'NR==2 { print \$2 }'",
            returnStdout: true
        ).trim()
    }
    return result
}

String getMachineType(String arch) {
    error("Architecture selection is not supported for DOKS: ${arch}")
}

void createCluster(Map clusterCfg, Map libraries) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    libraries.credentials.withDOKSCredentials {
        libraries.helpers.retryCommand(
            command: """
                set -euo pipefail
                
                cluster="${clusterFullName}"
                cluster_version=\$(doctl kubernetes options versions --output json | jq -r --arg v "${clusterCfg.platformVersion}" '.[] | select(.kubernetes_version==\$v) | .slug')

                doctl kubernetes cluster create "\$cluster" \\
                    --region "${clusterCfg.region}" \\
                    --version "\$cluster_version" \\
                    --tag "delete-cluster-after-hours:6" \\
                    --node-pool "name=default-pool;size=s-4vcpu-16gb-amd;tag=worker;auto-scale=true;count=4;min-nodes=4;max-nodes=6"
            """,
            afterCommand: """
                export KUBECONFIG=${clusterCfg.kubeconfig}
                doctl kubernetes cluster kubeconfig save "${clusterFullName}"
                
                cluster_id=\$(doctl kubernetes cluster get "${clusterFullName}" --format ID --no-header)
                project_id=\$(doctl projects list --output json | jq -er 'first(.[] | select(.name == "cloud-team") | .id)')
                urn="do:kubernetes:\$cluster_id"
                doctl projects resources assign "\$project_id" --resource "\$urn"
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
        libraries.credentials.withDOKSCredentials {
            sh """
                doctl kubernetes cluster delete ${clusterFullName} --force --dangerous || true
            """
        }
    }
}

return this
