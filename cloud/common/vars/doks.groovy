// Checked once while preparing the job, so an unavailable version fails fast instead of on every create attempt.
String getPlatformVersion(String version, Map libraries) {
    libraries.credentials.withDOKSCredentials {
        def offered = sh(
            script: """doctl kubernetes options versions --output json | jq -e --arg v "${version}" 'any(.[]; .kubernetes_version == \$v)' >/dev/null""",
            returnStatus: true
        ) == 0
        if (!offered) {
            error("DOKS does not offer Kubernetes version ${version}")
        }
    }
    return version
}

String getLatestPlatformVersion(String location, String channel, Map libraries) {
    def result = ""
    libraries.credentials.withDOKSCredentials {
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
        timeout(time: 30, unit: 'MINUTES') {
            libraries.helpers.retryCommand(
                command: """
                    set -euo pipefail

                    # Set before creating: doctl updates the kubeconfig on create, and must not touch the
                    # agent's shared ~/.kube/config used by other runners.
                    export KUBECONFIG=${clusterCfg.kubeconfig}
                    cluster="${clusterFullName}"
                    cluster_version=\$(doctl kubernetes options versions --output json | jq -r --arg v "${clusterCfg.platformVersion}" '.[] | select(.kubernetes_version==\$v) | .slug')

                    if [ -z "\$cluster_version" ]; then
                        echo "DOKS does not offer Kubernetes version ${clusterCfg.platformVersion}" >&2
                        exit 1
                    fi

                    doctl kubernetes cluster create "\$cluster" \\
                        --region "${clusterCfg.region}" \\
                        --version "\$cluster_version" \\
                        --tag "delete-cluster-after-hours:6" \\
                        --node-pool "name=default-pool;size=s-4vcpu-16gb-amd;tag=worker;auto-scale=true;count=4;min-nodes=4;max-nodes=6"
                """,
                afterCommand: """
                    set -euo pipefail

                    export KUBECONFIG=${clusterCfg.kubeconfig}
                    doctl kubernetes cluster kubeconfig save "${clusterFullName}"

                    # A cluster that is "running" in DO but unreachable or without Ready nodes counts as a failed create.
                    for ((n=1;n<=30;n++)); do
                        [ -n "\$(kubectl get nodes --no-headers 2>/dev/null)" ] && break
                        sleep 10
                    done
                    kubectl wait --for=condition=Ready nodes --all --timeout=300s
                """,
                cleanupFunction: {
                    shutdownCluster(clusterCfg, libraries)
                },
                maxRetries: 3,
                delaySeconds: 5
            )
        }

        // Project assignment is bookkeeping only: retry it on its own and never recreate the cluster for it.
        sh """
            assign_cluster_to_project() {
                project_id=\$(doctl projects list --output json | jq -er 'first(.[] | select(.name == "cloud-team") | .id)') &&
                cluster_id=\$(doctl kubernetes cluster get "${clusterFullName}" --format ID --no-header) &&
                doctl projects resources assign "\$project_id" --resource "do:kubernetes:\$cluster_id"
            }

            for i in 1 2 3 4 5; do
                assign_cluster_to_project && break
                echo "Project assignment failed (attempt \$i/5)"
                sleep 10
            done || true
        """
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
