String getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

String getLatestPlatformVersion(Map testVariables) {
    return sh(
        script: "gcloud container get-server-config --region=${testVariables.zone} --flatten=channels --filter='channels.channel=${testVariables.platform_channel}' --format='value(channels.validVersions)' | cut -d- -f1",
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
            error("Unknown architecture: ${arch}")
    }
}

boolean clusterExists(Map clusterCfg, Map libraries) {
    def clusterName = ''
    libraries.credentials.withGCPCredentials {
        withEnv([
            "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
            "CLUSTER_NAME=${clusterCfg.clusterName}",
            "GKE_REGION=${clusterCfg.zone}"
        ]) {
            clusterName = sh(
                script: '''
                    gcloud container clusters list \
                        --project "$GCP_PROJECT" \
                        --zone "$GKE_REGION" \
                        --filter "name=$CLUSTER_NAME-$CLUSTER_SUFFIX" \
                        --format='value(name)'
                ''',
                returnStdout: true
            ).trim()
        }
    }
    return !clusterName.isEmpty()
}

void createCluster(Map clusterCfg, Map libraries) {
    // A deletion started asynchronously (e.g. by the runner's pre-cleanup) must finish before a
    // cluster with the same name can be created again.
    shutdownCluster(clusterCfg, libraries, false)

    libraries.credentials.withGCPCredentials {
        withEnv([
            "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
            "CLUSTER_NAME=${clusterCfg.clusterName}",
            "GKE_RELEASE_CHANNEL=${clusterCfg.platformChannel}",
            "GKE_REGION=${clusterCfg.zone}",
            "KUBECONFIG=${clusterCfg.kubeconfig}",
            "PLATFORM_VER=${clusterCfg.platformVersion}",
            "MACHINE_TYPE=${clusterCfg.machineType}"
        ]) {
            timeout(time: 30, unit: 'MINUTES') {
                libraries.helpers.retryCommand(
                    command: '''
                        gcloud container clusters create $CLUSTER_NAME-$CLUSTER_SUFFIX \\
                            --project "$GCP_PROJECT" \\
                            --release-channel $GKE_RELEASE_CHANNEL \\
                            --zone $GKE_REGION \\
                            --cluster-version $PLATFORM_VER \\
                            --preemptible \\
                            --disk-size 30 \\
                            --machine-type $MACHINE_TYPE \\
                            --num-nodes=3 \\
                            --network=jenkins-vpc \\
                            --subnetwork=jenkins-$CLUSTER_SUFFIX \\
                            --cluster-ipv4-cidr=/21 \\
                            --labels delete-cluster-after-hours=6 \\
                            --enable-ip-alias \\
                            --monitoring=NONE \\
                            --logging=NONE \\
                            --no-enable-managed-prometheus \\
                            --workload-pool=cloud-dev-112233.svc.id.goog \\
                            --quiet
                    ''',
                    afterCommand: '''
                        gcloud container clusters get-credentials "$CLUSTER_NAME-$CLUSTER_SUFFIX" \\
                            --project "$GCP_PROJECT" \\
                            --zone "$GKE_REGION"
                            
                            # Idempotent, so a retried afterCommand does not fail on AlreadyExists.
                            kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=$(gcloud config get-value core/account) \\
                                --dry-run=client -o yaml | kubectl apply -f -
                            CURRENT_TIME=$(date --rfc-3339=seconds)
                            FUTURE_TIME=$(date -d "6 hours" --rfc-3339=seconds)
                            gcloud container clusters update $CLUSTER_NAME-$CLUSTER_SUFFIX \\
                                --zone $GKE_REGION \\
                                --add-maintenance-exclusion-start "$CURRENT_TIME" \\
                                --add-maintenance-exclusion-end "$FUTURE_TIME"
                            
                            # Wait for API server to be fully ready
                            for i in $(seq 1 60); do
                                if kubectl cluster-info >/dev/null 2>&1 && kubectl get nodes >/dev/null 2>&1; then
                                    echo "Cluster API server is ready"
                                    break
                                fi
                                echo "Waiting for cluster API server... ($i/60)"
                                sleep 10
                            done
                            
                            kubectl get nodes -o custom-columns="NAME:.metadata.name,TAINTS:.spec.taints,AGE:.metadata.creationTimestamp"
                        ''',
                    cleanupFunction: {
                        // Synchronous, so the next attempt does not collide with the cluster being deleted.
                        shutdownCluster(clusterCfg, libraries, false)
                    },
                    maxRetries: 3,
                    delaySeconds: 5
                )
            }
        }
    }
}

void shutdownCluster(Map clusterCfg, Map libraries, boolean async = true) {
    timeout(time: 30, unit: 'MINUTES') {
        if (!clusterExists(clusterCfg, libraries)) {
            return
        }
        libraries.credentials.withGCPCredentials {
            withEnv([
                "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
                "CLUSTER_NAME=${clusterCfg.clusterName}",
                "GKE_REGION=${clusterCfg.zone}",
                "DELETE_ASYNC_FLAG=${async ? '--async' : ''}"
            ]) {
                sh '''
                    gcloud container clusters delete "$CLUSTER_NAME-$CLUSTER_SUFFIX" \
                        --project "$GCP_PROJECT" \
                        --zone "$GKE_REGION" \
                        ${DELETE_ASYNC_FLAG} \
                        --quiet ||
                        echo "Warning: unable to delete GKE cluster " \
                            "${CLUSTER_NAME}-${CLUSTER_SUFFIX}; continuing"
                '''
            }
        }
    }
}

return this
