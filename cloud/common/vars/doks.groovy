def getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

def getLatestPlatformVersion(Map testVariables) {
    return withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
        sh(
            script: "doctl kubernetes options versions | awk 'NR==2 { print \$2 }'",
            returnStdout: true
        ).trim()
    }
}

def getMachineType(String arch) {
    return arch
}

void createCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'DOKS_PROJECT_ID', variable: 'PROJECT'), string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
            sh """
                set -euo pipefail

                export KUBECONFIG=/tmp/${clusterFullName}
                cluster="${clusterFullName}"
                cluster_version=\$(doctl kubernetes options versions --output json | jq -r --arg v "${clusterCfg.platformVersion}" '.[] | select(.kubernetes_version==\$v) | .slug')

                create_cluster() {
                    doctl kubernetes cluster create "\$cluster" \
                        --region "${clusterCfg.region}" \
                        --version "\$cluster_version" \
                        --node-pool "name=default-pool;size=s-4vcpu-16gb-amd;tag=worker;auto-scale=true;count=4;min-nodes=4;max-nodes=6"

                    doctl kubernetes cluster kubeconfig save "\$cluster"
                }

                assign_cluster_to_project() {
                    cluster_id=\$(doctl kubernetes cluster get "\$cluster" --format ID --no-header)
                    urn="do:kubernetes:\$cluster_id"

                    doctl projects resources assign "\$PROJECT" --resource "\$urn"
                }

                max_retries=15
                for ((i=1;i<=max_retries;i++)); do
                    if create_cluster && assign_cluster_to_project; then
                        break
                    fi

                    echo "Retry \$i/\$max_retries"
                    sleep 2
                done
            """
        }
    }
}

void shutdownCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'DOKS_PROJECT_ID', variable: 'PROJECT'), string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
            sh """
                doctl kubernetes cluster delete ${clusterFullName} --force || true
            """
        }
    }
}

return this
