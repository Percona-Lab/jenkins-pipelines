String cluster(Map clusterCfg) {
    def clusterName = clusterCfg.clusterName
    def clusterSuffix = clusterCfg.clusterSuffix
    if (!clusterName || !clusterSuffix) {
        error("Set correct cluster name and suffix")
    }

    return "${clusterName}-${clusterSuffix}"
}

void createCluster(Map clusterCfg) {
    def prefix = cluster(clusterCfg)
    def envVars = [
        "PREFIX=${prefix}",
        "ZONE=${clusterCfg.zone ?: 'us-central1-a'}",
        "WORKER_COUNT=${clusterCfg.workerCountMin ?: '4'}",
        "MACHINE_TYPE=${clusterCfg.machineType ?: 'e2-standard-4'}",
        "BOOT_DISK_SIZE=${clusterCfg.bootDiskSize ?: '100GB'}",
        "IMAGE_FAMILY=${clusterCfg.imageFamily ?: 'rocky-linux-9-optimized-gcp'}",
        "IMAGE_PROJECT=${clusterCfg.imageProject ?: 'rocky-linux-cloud'}",
        "SOURCE_RANGES=${clusterCfg.sourceRanges ?: '0.0.0.0/0'}",
        "OWNER=${clusterCfg.owner ?: 'jenkins'}",
        "PRODUCT=${clusterCfg.product ?: 'psmdb'}",
        "DELETE_AFTER_HOURS=${clusterCfg.deleteAfterHours ?: '6'}",
        "RANCHER_VERSION=${clusterCfg.rancherVersion ?: 'latest'}",
        "CERT_MANAGER_VERSION=${clusterCfg.certManagerVersion ?: 'latest'}",
        "INSTALL_RKE2_CHANNEL=${clusterCfg.platformChannel ?: 'stable'}",
        "INSTALL_RKE2_VERSION=${clusterCfg.platformVersion ?: ''}",
        "KUBECONFIG=${clusterCfg.kubeconfig ?: '/tmp/kubeconfig'}"
    ]

    // Default timeout for cluster creation is 60 minutes
    timeout(time: 60, unit: 'MINUTES') {
        withEnv(envVars) {
            sh '''
                set -euo pipefail

                for attempt in {1..3}; do
                    echo "Creating Rancher (attempt ${attempt}/3)..."

                    if python3 cloud/scripts/create_rancher.py "$PREFIX" --save-kubeconfig true; then
                        break
                    fi

                    if [ "$attempt" -eq 3 ]; then
                        echo "Failed to create Rancher after 3 attempts"
                        exit 1
                    fi

                    echo "Retrying in 10 seconds..."
                    sleep 10
                done
            '''
        }
    }
}

void shutdownCluster(Map clusterCfg) {
    withEnv([
        "PREFIX=${cluster(clusterCfg)}",
        "ZONE=${clusterCfg.zone ?: clusterCfg.region ?: env.GOOGLE_REGION ?: env.ZONE ?: 'us-central1-a'}",
        "KUBECONFIG=${ clusterCfg.kubeconfig ?: env.KUBECONFIG ?: '/tmp/kubeconfig'}"
    ]) {
        sh '''
            set -euo pipefail

            python3 cloud/scripts/destroy_rancher.py "$PREFIX"
        '''
    }
}

def getLatestPlatformVersion(String channel) {
    sh(
        script: """
            curl -fsSL https://update.rke2.io/v1-release/channels \
            | jq -r --arg channel "${channel}" '.data[] | select(.id == \$channel) | .latest'
        """,
        returnStdout: true
    ).trim()
}

def getPlatformVersion(String prefixVersion) {
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

def getMachineType(String arch) {
    switch (arch) {
        case 'amd64':
            return 'e2-standard-4'
        case 'arm64':
            return 't2a-standard-4'
        default:
            error("Unsupported architecture: ${arch}")
    }
}

return this
