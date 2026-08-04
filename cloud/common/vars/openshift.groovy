def getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

def getLatestPlatformVersion(Map testVariables) {
    return sh(
        script: "curl -s https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/latest/release.txt | sed -n 's/^\\s*Version:\\s\\+\\(\\S\\+\\)\\s*\$/\\1/p'",
        returnStdout: true
    ).trim()
}

def getMachineType(String arch) {
    return arch
}

void createCluster(Map clusterCfg) {
    def clusterSuffix = clusterCfg.clusterSuffix

    timeout(time: 60, unit: 'MINUTES') {
        withCredentials([aws(credentialsId: 'openshift-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'), file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE'), usernamePassword(credentialsId: 'docker.io', passwordVariable: 'DOCKER_READ_PASS', usernameVariable: 'DOCKER_READ_USER')]) {
            withEnv(["CLUSTER_SUFFIX=${clusterSuffix}", "CLUSTER_NAME=${clusterCfg.clusterName}"]) {
                sh """
                    mkdir -p openshift/\$CLUSTER_SUFFIX
                    timestamp="\$(date +%s)"
tee openshift/\$CLUSTER_SUFFIX/install-config.yaml << EOF
additionalTrustBundlePolicy: Proxyonly
credentialsMode: Mint
apiVersion: v1
baseDomain: cd.percona.com
compute:
- architecture: amd64
  hyperthreading: Enabled
  name: worker
  platform:
    aws:
      type: m5.xlarge
  replicas: 3
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform: {}
  replicas: 1
metadata:
  creationTimestamp: null
  name: \$CLUSTER_NAME-\$CLUSTER_SUFFIX
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: OVNKubernetes
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: ${clusterCfg.region}
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: 7
      team: cloud
      product: ${clusterCfg.product}
      creation-time: \$timestamp

publish: External
EOF
                    cat \$OPENSHIFT_CONF_FILE >> openshift/\$CLUSTER_SUFFIX/install-config.yaml
                """
                sshagent(['aws-openshift-41-key']) {
                    sh '''
                        /usr/local/bin/openshift-install create cluster --dir=openshift/$CLUSTER_SUFFIX --log-level=debug || {
                            /usr/local/bin/openshift-install gather bootstrap --dir=openshift/$CLUSTER_SUFFIX || true
                            exit 1
                        }
                        export KUBECONFIG=openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                        TMP=$(mktemp)
                        oc get secret/pull-secret -n openshift-config --template='{{index .data ".dockerconfigjson" | base64decode}}' > $TMP
                        oc registry login --registry='docker.io' --auth-basic="$DOCKER_READ_USER:$DOCKER_READ_PASS" --to=$TMP
                        oc set data secret/pull-secret -n openshift-config --from-file=.dockerconfigjson=$TMP
                        rm -rf $TMP
                    '''
                }
            }
        }
    }

    enableVolumeSnapshotResources(clusterSuffix)
    verifyVolumeSnapshotResources(clusterSuffix)
}

void enableVolumeSnapshotResources(String clusterSuffix) {
    sh """
        export KUBECONFIG=\$WORKSPACE/openshift/${clusterSuffix}/auth/kubeconfig

        for i in \$(seq 1 60); do
            if kubectl get crd csisnapshotcontrollers.operator.openshift.io >/dev/null 2>&1; then
                break
            fi
            sleep 10
        done

        cat <<EOF | kubectl apply -f -
apiVersion: operator.openshift.io/v1
kind: CSISnapshotController
metadata:
  name: cluster
spec:
  managementState: Managed
EOF

        kubectl get csisnapshotcontroller cluster -o yaml
    """
}

void verifyVolumeSnapshotResources(String clusterSuffix) {
    sh """
        export KUBECONFIG=\$WORKSPACE/openshift/${clusterSuffix}/auth/kubeconfig

        wait_for_deployment() {
            local deployment_name="\$1"
            local namespace="\$2"

            for i in \$(seq 1 60); do
                if kubectl get deployment "\$deployment_name" -n "\$namespace" >/dev/null 2>&1; then
                    kubectl wait --for=condition=Available deployment/"\$deployment_name" -n "\$namespace" --timeout=10m
                    return 0
                fi
                sleep 10
            done

            kubectl get deployment -n "\$namespace" || true
            return 1
        }

        wait_for_deployment csi-snapshot-controller-operator openshift-cluster-storage-operator
        wait_for_deployment csi-snapshot-controller openshift-cluster-storage-operator

        kubectl get crd volumesnapshots.snapshot.storage.k8s.io volumesnapshotcontents.snapshot.storage.k8s.io volumesnapshotclasses.snapshot.storage.k8s.io
        kubectl api-resources --api-group=snapshot.storage.k8s.io
    """
}

void shutdownCluster(Map clusterCfg) {
    def clusterSuffix = clusterCfg.clusterSuffix

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([aws(credentialsId: 'openshift-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID'), file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
            sshagent(['aws-openshift-41-key']) {
                sh """
                    /usr/local/bin/openshift-install destroy cluster --dir=openshift/${clusterSuffix} || true
                """
            }
        }
    }
}

return this
