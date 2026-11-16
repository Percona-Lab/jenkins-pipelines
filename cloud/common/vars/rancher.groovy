String cluster(Map clusterCfg) {
    def clusterName = clusterCfg.clusterName
    def clusterSuffix = clusterCfg.clusterSuffix
    if (!clusterName || !clusterSuffix) {
        error('Set correct cluster name and suffix')
    }

    return "${clusterName}-${clusterSuffix}"
}

void createCluster(Map clusterCfg, Map libraries) {
    def prefix = cluster(clusterCfg)
    def envVars = [
        "PREFIX=${prefix}",
        "ZONE=${clusterCfg.zone ?: 'us-central1-a'}",
        "WORKER_COUNT=${clusterCfg.workerCount}",
        "MACHINE_TYPE=${clusterCfg.machineType}",
        'BOOT_DISK_SIZE=100GB',
        "IMAGE_FAMILY=${clusterCfg.platformArch == 'arm64' ? 'rocky-linux-9-optimized-gcp-arm64' : 'rocky-linux-9-optimized-gcp'}",
        'IMAGE_PROJECT=rocky-linux-cloud',
        'SOURCE_RANGES=0.0.0.0/0',
        'OWNER=jenkins',
        "PRODUCT=${clusterCfg.product}",
        "DELETE_AFTER_HOURS=${clusterCfg.deleteAfterHours ?: '6'}",
        "RANCHER_VERSION=${clusterCfg.rancherVersion ?: 'latest'}",
        "CERT_MANAGER_VERSION=${clusterCfg.certManagerVersion ?: 'latest'}",
        "INSTALL_RKE2_CHANNEL=${clusterCfg.platformChannel ?: 'stable'}",
        "INSTALL_RKE2_VERSION=${clusterCfg.platformVersion ?: 'latest'}",
        "KUBECONFIG=${clusterCfg.kubeconfig}"
    ]

    withEnv(envVars) {
        timeout(time: 60, unit: 'MINUTES') {
            libraries.helpers.retryCommand(
                command: '''
                    set -euo pipefail
                    python3 cloud/scripts/create_rancher.py "$PREFIX" --save-kubeconfig true
                ''',
                afterCommand: '''
                    # Wait for API server to be fully ready
                    for i in $(seq 1 60); do
                        if kubectl cluster-info >/dev/null 2>&1 && kubectl get nodes >/dev/null 2>&1; then
                            echo "Cluster API server is ready"
                            break
                        fi
                        echo "Waiting for cluster API server... ($i/60)"
                        sleep 10
                    done
                    
                    kubectl get nodes
                ''',
                cleanupFunction: {
                    shutdownCluster(clusterCfg, libraries)
                },
                maxRetries: 3,
                delaySeconds: 5
            )
        }
    }
}

// libraries is unused here; optional so older callers with a single argument keep working.
void shutdownCluster(Map clusterCfg, Map libraries = [:]) {
    withEnv([
        "PREFIX=${cluster(clusterCfg)}",
        "ZONE=${clusterCfg.zone ?: clusterCfg.region ?: 'us-central1-a'}",
        "KUBECONFIG=${ clusterCfg.kubeconfig}"
    ]) {
        sh '''
            set -euo pipefail

            python3 cloud/scripts/destroy_rancher.py "$PREFIX"
        '''
    }
}

String getLatestPlatformVersion(Map platformCfg) {
    def channel = platformCfg.platform_channel

    return sh(
        script: """
            curl -fsSL https://update.rke2.io/v1-release/channels \
            | jq -r --arg channel "${channel}" '.data[] | select(.id == \$channel) | .latest'
        """,
        returnStdout: true
    ).trim()
}

String getPlatformVersion(String prefixVersion) {
    def parts = prefixVersion.tokenize('.')
    def channel = "v${parts[0]}.${parts[1]}"

    return sh(
        script: """
            curl -fsSL https://update.rke2.io/v1-release/channels \
            | jq -r --arg channel "${channel}" '
                .data[]
                | select(.id == \$channel)
                | .latest
            '
        """,
        returnStdout: true
    ).trim()
}

String getMachineType(String arch) {
    switch (arch) {
        case 'amd64':
            return 'n1-standard-4'
        case 'arm64':
            return 't2a-standard-4'
        default:
            error("Architecture ${arch} is not supported for Rancher")
    }
}

return this
