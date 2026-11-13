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
    error("Architecture selection is not supported for DOKS: ${arch}")
}

void createCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
            sh """
                set -euo pipefail

                export KUBECONFIG=${clusterCfg.kubeconfig}
                cluster="${clusterFullName}"
                cluster_version=\$(doctl kubernetes options versions --output json | jq -r --arg v "${clusterCfg.platformVersion}" '.[] | select(.kubernetes_version==\$v) | .slug')
                project_id=\$(doctl projects list --output json | jq -er 'first(.[] | select(.name == "cloud-team") | .id)')

                if [ -z "\$cluster_version" ]; then
                    echo "DOKS does not offer Kubernetes version ${clusterCfg.platformVersion}" >&2
                    exit 1
                fi

                create_cluster() {
                    doctl kubernetes cluster create "\$cluster" \
                        --region "${clusterCfg.region}" \
                        --version "\$cluster_version" \
                        --tag "delete-cluster-after-hours:6" \
                        --node-pool "name=default-pool;size=s-4vcpu-16gb-amd;tag=worker;auto-scale=true;count=4;min-nodes=4;max-nodes=6" &&
                    doctl kubernetes cluster kubeconfig save "\$cluster"
                }

                assign_cluster_to_project() {
                    cluster_id=\$(doctl kubernetes cluster get "\$cluster" --format ID --no-header) &&
                    doctl projects resources assign "\$project_id" --resource "do:kubernetes:\$cluster_id"
                }

                max_retries=3
                created=0
                for ((i=1;i<=max_retries;i++)); do
                    if create_cluster; then
                        created=1
                        break
                    fi

                    echo "Cluster creation failed (attempt \$i/\$max_retries); deleting any partial cluster"
                    doctl kubernetes cluster delete "\$cluster" --force --dangerous || true
                    sleep 30
                done

                if [ "\$created" != 1 ]; then
                    echo "Failed to create DOKS cluster \$cluster after \$max_retries attempts" >&2
                    exit 1
                fi

                # Project assignment is bookkeeping only: retry it on its own and never recreate the cluster for it.
                for ((i=1;i<=5;i++)); do
                    assign_cluster_to_project && break
                    echo "Project assignment failed (attempt \$i/5)"
                    sleep 10
                done || true
            """
        }
    }
}

void shutdownCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
            sh """
                doctl kubernetes cluster delete ${clusterFullName} --force --dangerous || true
            """
        }
    }
}

return this
