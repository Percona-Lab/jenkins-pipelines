void auth() {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh '''
            gcloud auth activate-service-account --key-file "$CLIENT_SECRET_FILE"
            gcloud config set project "$GCP_PROJECT"
        '''
    }
}

String getPlatformVersion(String version, Map testVariables = [:]) {
    return version
}

String getLatestPlatformVersion(String channel, Map testVariables = [:]) {
    def region = testVariables.region ?: 'us-central1-a'

    return sh(
        script: "gcloud container get-server-config --region=${region} --flatten=channels --filter='channels.channel=${channel}' --format='value(channels.validVersions)' | cut -d- -f1",
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
            error("Unsupported architecture: ${arch}")
    }
}

void createCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        withEnv([
            "CLUSTER_FULL_NAME=${clusterFullName}",
            "CLUSTER_SUFFIX=${clusterCfg.clusterSuffix}",
            "GKE_RELEASE_CHANNEL=${clusterCfg.platformChannel}",
            "GKE_REGION=${clusterCfg.region}",
            "PLATFORM_VERSION=${clusterCfg.platformVersion}",
            "MACHINE_TYPE=${clusterCfg.machineType ?: 'n1-standard-4'}",
            "KUBECONFIG=${clusterCfg.kubeconfig}"
        ]) {
            sh '''
                maxRetries=15
                exitCode=1

                while [[ $exitCode != 0 && $maxRetries > 0 ]]; do
                    gcloud container clusters create $CLUSTER_FULL_NAME \
                        --release-channel $GKE_RELEASE_CHANNEL \
                        --zone $GKE_REGION \
                        --cluster-version $PLATFORM_VERSION \
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

                # When using the STABLE release channel, auto-upgrade must be enabled for node pools, which means you cannot manually disable it,
                # so we can't just use --no-enable-autoupgrade in the command above, so we need the following workaround.
                gcloud container clusters update $CLUSTER_FULL_NAME \
                    --zone $GKE_REGION \
                    --add-maintenance-exclusion-start "$CURRENT_TIME" \
                    --add-maintenance-exclusion-end "$FUTURE_TIME"

                kubectl get nodes -o custom-columns="NAME:.metadata.name,TAINTS:.spec.taints,AGE:.metadata.creationTimestamp"
            '''
        }
    }
}

void shutdownCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        sh """
            gcloud container clusters delete --async --zone ${clusterCfg.region} ${clusterFullName} --quiet || true
        """
    }
}

return this
