// Common functions for GKE Jenkins pipelines

String getParam(String release_versions, String paramName, String keyName = null) {
    keyName = keyName ?: paramName

    def param = sh(script: "grep -iE '^\\s*$keyName=' $release_versions | cut -d = -f 2 | tr -d \'\"\'| tail -1", returnStdout: true).trim()
    if ("$param") {
        echo "$paramName=$param (from params file)"
    } else {
        error("$keyName not found in params file $release_versions")
    }
    return param
}

void downloadKubectl() {
    sh """
        KUBECTL_VERSION="\$(curl -L -s https://api.github.com/repos/kubernetes/kubernetes/releases/latest | jq -r .tag_name)"
        for i in {1..5}; do
          if [ -f /usr/local/bin/kubectl ]; then
              break
          fi
          echo "Attempt \$i: downloading kubectl..."
          sudo curl -s -L -o /usr/local/bin/kubectl "https://dl.k8s.io/release/\${KUBECTL_VERSION}/bin/linux/amd64/kubectl"
          sudo curl -s -L -o /tmp/kubectl.sha256 "https://dl.k8s.io/release/\${KUBECTL_VERSION}/bin/linux/amd64/kubectl.sha256"
          if echo "\$(cat /tmp/kubectl.sha256) /usr/local/bin/kubectl" | sha256sum --check --status; then
            echo 'Download passed checksum'
            sudo chmod +x /usr/local/bin/kubectl
            kubectl version --client --output=yaml
            break
          else
            echo 'Checksum failed, retrying...'
            sudo rm -f /usr/local/bin/kubectl /tmp/kubectl.sha256
            sleep 5
          fi
        done
    """
}

void installCommonTools(String yqVersion = 'v4.44.1', String jqVersion = 'jq-1.7.1') {
    sh """
        sudo curl -fsSL https://github.com/mikefarah/yq/releases/download/${yqVersion}/yq_linux_amd64 -o /usr/local/bin/yq && sudo chmod +x /usr/local/bin/yq
        sudo curl -fsSL https://github.com/jqlang/jq/releases/download/${jqVersion}/jq-linux64 -o /usr/local/bin/jq && sudo chmod +x /usr/local/bin/jq
    """
}

void installHelm(String helmVersion = 'v3.18.3') {
    sh """
        curl -fsSL https://get.helm.sh/helm-${helmVersion}-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm
    """
}

void installKrewAndKuttl() {
    sh """
        curl -fsSL https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_amd64.tar.gz | tar -xzf -
        ./krew-linux_amd64 install krew
        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

        kubectl krew install assert

        # v0.22.0 kuttl version
        kubectl krew install --manifest-url https://raw.githubusercontent.com/kubernetes-sigs/krew-index/02d5befb2bc9554fdcd8386b8bfbed2732d6802e/plugins/kuttl.yaml
        echo \$(kubectl kuttl --version) is installed
    """
}

void installGcloudCLI() {
    sh """
        sudo tee /etc/yum.repos.d/google-cloud-sdk.repo << EOF
[google-cloud-cli]
name=Google Cloud CLI
baseurl=https://packages.cloud.google.com/yum/repos/cloud-sdk-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo yum install -y google-cloud-cli google-cloud-cli-gke-gcloud-auth-plugin
    """
}

void gcloudAuth() {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            gcloud auth activate-service-account --key-file $CLIENT_SECRET_FILE
            gcloud config set project $GCP_PROJECT
        """
    }
}

void pushArtifactFile(String FILE_NAME, String GIT_SHORT_COMMIT) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            touch $FILE_NAME
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/$GIT_SHORT_COMMIT
            aws s3 ls \$S3_PATH/$FILE_NAME || :
            aws s3 cp --quiet $FILE_NAME \$S3_PATH/$FILE_NAME || :
        """
    }
}

void shutdownCluster(String CLUSTER_NAME, String CLUSTER_SUFFIX, String GKE_REGION, boolean cleanupResources = true) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        if (cleanupResources) {
            sh """
                export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
                
                for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                    kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete services --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
                done
                kubectl get svc --all-namespaces || true
            """
        }
        
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            gcloud container clusters delete --zone ${GKE_REGION} $CLUSTER_NAME-$CLUSTER_SUFFIX --quiet || true
        """
    }
}

void dockerBuildPush(String operatorName, String gitBranch, String imageOperator, String buildCommand = 'e2e-tests/build', boolean useBuildx = true) {
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        def buildxSetup = useBuildx ? "docker buildx create --use" : ""
        sh """
            if [[ "$imageOperator" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c "
                    ${buildxSetup}
                    docker login -u '\$USER' -p '\$PASS'
                    export IMAGE=perconalab/${operatorName}:${gitBranch}
                    ${buildCommand}
                    docker logout
                "
                sudo rm -rf build
            fi
        """
    }
}

void createGKECluster(String CLUSTER_NAME, String CLUSTER_SUFFIX, String GKE_REGION, String GKE_RELEASE_CHANNEL, String PLATFORM_VER, String MACHINE_TYPE = 'n1-standard-4') {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            maxRetries=5
            exitCode=1

            while [[ \$exitCode != 0 && \$maxRetries > 0 ]]; do
                gcloud container clusters create $CLUSTER_NAME-$CLUSTER_SUFFIX \
                    --release-channel $GKE_RELEASE_CHANNEL \
                    --zone $GKE_REGION \
                    --cluster-version $PLATFORM_VER \
                    --preemptible \
                    --disk-size 30 \
                    --machine-type $MACHINE_TYPE \
                    --num-nodes=4 \
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
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=\$(gcloud config get-value core/account)
                exitCode=\$?
                if [[ \$exitCode == 0 ]]; then break; fi
                (( maxRetries -- ))
                sleep 1
            done
            if [[ \$exitCode != 0 ]]; then exit \$exitCode; fi

            CURRENT_TIME=\$(date --rfc-3339=seconds)
            FUTURE_TIME=\$(date -d '6 hours' --rfc-3339=seconds)

            gcloud container clusters update $CLUSTER_NAME-$CLUSTER_SUFFIX \
                --zone ${GKE_REGION} \
                --add-maintenance-exclusion-start "\$CURRENT_TIME" \
                --add-maintenance-exclusion-end "\$FUTURE_TIME"

            kubectl get nodes -o custom-columns="NAME:.metadata.name,TAINTS:.spec.taints,AGE:.metadata.creationTimestamp"
        """
    }
}

return this
