// Checked once while preparing the job, so an unavailable version fails fast instead of on every create attempt.
def getPlatformVersion(String prefixVersion) {
    withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
        def offered = sh(
            script: """doctl kubernetes options versions --output json | jq -e --arg v "${prefixVersion}" 'any(.[]; .kubernetes_version == \$v)' >/dev/null""",
            returnStatus: true
        ) == 0
        if (!offered) {
            error("DOKS does not offer Kubernetes version ${prefixVersion}")
        }
    }
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

                # Retries and cleanup of a failed create are done by the caller (tests.clusterRunner).
                doctl kubernetes cluster create "\$cluster" \
                    --region "${clusterCfg.region}" \
                    --version "\$cluster_version" \
                    --tag "delete-cluster-after-hours:6" \
                    --node-pool "name=default-pool;size=s-4vcpu-16gb-amd;tag=worker;auto-scale=true;count=4;min-nodes=4;max-nodes=6"
                doctl kubernetes cluster kubeconfig save "\$cluster"

                # A cluster that is "running" in DO but unreachable or without Ready nodes counts as a failed create.
                for ((n=1;n<=30;n++)); do
                    [ -n "\$(kubectl get nodes --no-headers 2>/dev/null)" ] && break
                    sleep 10
                done
                kubectl wait --for=condition=Ready nodes --all --timeout=300s

                assign_cluster_to_project() {
                    cluster_id=\$(doctl kubernetes cluster get "\$cluster" --format ID --no-header) &&
                    doctl projects resources assign "\$project_id" --resource "do:kubernetes:\$cluster_id"
                }

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
