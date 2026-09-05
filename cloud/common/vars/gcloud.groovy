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

void createCluster(Map clusterCfg) {
    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
            withEnv([
                "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
                "CLUSTER_NAME=${clusterCfg.clusterName}",
                "GKE_RELEASE_CHANNEL=${clusterCfg.platformChannel}",
                "GKE_REGION=${clusterCfg.zone}",
                "PLATFORM_VER=${clusterCfg.platformVersion}",
                "MACHINE_TYPE=${clusterCfg.machineType}"
            ]) {
                sh '''
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
                    maxRetries=15
                    exitCode=1

                    while [[ $exitCode != 0 && $maxRetries > 0 ]]; do
                        gcloud container clusters create $CLUSTER_NAME-$CLUSTER_SUFFIX \
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
                            --quiet &&\
                        kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=$(gcloud config get-value core/account)
                        exitCode=$?
                        if [[ $exitCode == 0 ]]; then break; fi
                        (( maxRetries -- ))
                        sleep 1
                    done
                    if [[ $exitCode != 0 ]]; then exit $exitCode; fi

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

void shutdownCluster(Map clusterCfg) {
    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
            withEnv([
                "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
                "CLUSTER_NAME=${clusterCfg.clusterName}",
                "GKE_REGION=${clusterCfg.zone}"
            ]) {
                sh '''
                    gcloud container clusters delete --async --zone $GKE_REGION $CLUSTER_NAME-$CLUSTER_SUFFIX --quiet || true
                '''
            }
        }
    }
}

return this
