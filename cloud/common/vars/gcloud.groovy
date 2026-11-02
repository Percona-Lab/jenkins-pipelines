void auth() {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh '''
            gcloud auth activate-service-account --key-file "$CLIENT_SECRET_FILE"
            gcloud config set project "$GCP_PROJECT"
        '''
    }
}

def getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

def getLatestPlatformVersion(Map testVariables) {
    return sh(
        script: "gcloud container get-server-config --region=${testVariables.zone} --flatten=channels --filter='channels.channel=${testVariables.platform_channel}' --format='value(channels.validVersions)' | cut -d- -f1",
        returnStdout: true
    ).trim()
}

def getMachineType(String arch) {
    switch (arch) {
        case 'amd64':
            return 'n1-standard-4'
        case 'arm64':
            return 't2a-standard-4'
        default:
            error("Unknown architecture: ${arch}")
    }
}

boolean clusterExists(Map clusterCfg) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT')]) {
        withEnv([
            "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
            "CLUSTER_NAME=${clusterCfg.clusterName}",
            "GKE_REGION=${clusterCfg.zone}"
        ]) {
            return !sh(
                script: '''
                    gcloud container clusters list \
                        --project "$GCP_PROJECT" \
                        --zone "$GKE_REGION" \
                        --filter "name=$CLUSTER_NAME-$CLUSTER_SUFFIX" \
                        --format='value(name)'
                ''',
                returnStdout: true
            ).trim().isEmpty()
        }
    }
}

void createCluster(Map clusterCfg) {
    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
            withEnv([
                "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
                "CLUSTER_NAME=${clusterCfg.clusterName}",
                "GKE_RELEASE_CHANNEL=${clusterCfg.platformChannel}",
                "GKE_REGION=${clusterCfg.zone}",
                "KUBECONFIG=${clusterCfg.kubeconfig}",
                "PLATFORM_VER=${clusterCfg.platformVersion}",
                "MACHINE_TYPE=${clusterCfg.machineType}"
            ]) {
                int exitCode = 1
                for (int attempt = 1; attempt <= 15; attempt++) {
                    if (clusterExists(clusterCfg)) {
                        shutdownCluster(clusterCfg, true)
                    }

                    exitCode = sh(
                        returnStatus: true,
                        script: '''
                        gcloud container clusters create $CLUSTER_NAME-$CLUSTER_SUFFIX \
                            --project "$GCP_PROJECT" \
                            --release-channel $GKE_RELEASE_CHANNEL \
                            --zone $GKE_REGION \
                            --cluster-version $PLATFORM_VER \
                            --preemptible \
                            --disk-size 30 \
                            --machine-type $MACHINE_TYPE \
                            --num-nodes=3 \
                            --network=jenkins-vpc \
                            --subnetwork=jenkins-$CLUSTER_SUFFIX \
                            --cluster-ipv4-cidr=/21 \
                            --labels delete-cluster-after-hours=6 \
                            --enable-ip-alias \
                            --monitoring=NONE \
                            --logging=NONE \
                            --no-enable-managed-prometheus \
                            --workload-pool=cloud-dev-112233.svc.id.goog \
                            --quiet
                        '''
                    )
                    if (exitCode == 0) {
                        break
                    }
                    if (attempt < 15) {
                        echo "Cluster creation failed (exit ${exitCode}); cleaning up before retry (${15 - attempt} attempts remaining)"
                        sleep(time: 10, unit: 'SECONDS')
                    }
                }
                if (exitCode != 0) {
                    error("Cluster creation failed after 15 attempts (exit ${exitCode})")
                }

                sh '''
                    kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=$(gcloud config get-value core/account)

                    CURRENT_TIME=$(date --rfc-3339=seconds)
                    FUTURE_TIME=$(date -d '6 hours' --rfc-3339=seconds)

                    gcloud container clusters update $CLUSTER_NAME-$CLUSTER_SUFFIX \
                        --zone $GKE_REGION \
                        --add-maintenance-exclusion-start "$CURRENT_TIME" \
                        --add-maintenance-exclusion-end "$FUTURE_TIME"

                    kubectl get nodes -o custom-columns="NAME:.metadata.name,TAINTS:.spec.taints,AGE:.metadata.creationTimestamp"
                '''
            }
        }
    }
}

void shutdownCluster(Map clusterCfg, boolean waitForDeletion = false) {
    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
            withEnv([
                "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
                "CLUSTER_NAME=${clusterCfg.clusterName}",
                "GKE_REGION=${clusterCfg.zone}"
            ]) {
                if (waitForDeletion) {
                    sh '''
                        gcloud container clusters delete "$CLUSTER_NAME-$CLUSTER_SUFFIX" \
                            --project "$GCP_PROJECT" --zone "$GKE_REGION" --quiet
                    '''
                } else {
                    sh '''
                        gcloud container clusters delete --async --zone $GKE_REGION $CLUSTER_NAME-$CLUSTER_SUFFIX --quiet || true
                    '''
                }
            }
        }
    }
}

return this
