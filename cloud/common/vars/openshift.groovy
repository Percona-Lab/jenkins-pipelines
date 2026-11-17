String getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

String getLatestPlatformVersion(Map testVariables) {
    return sh(
        script: "curl -s https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/latest/release.txt | sed -n 's/^\\s*Version:\\s\\+\\(\\S\\+\\)\\s*\$/\\1/p'",
        returnStdout: true
    ).trim()
}

String getMachineType(String arch) {
    error("Architecture selection is not supported for OpenShift: ${arch}")
}

void createCluster(Map clusterCfg, Map libraries) {
    def clusterSuffix = clusterCfg.clusterSuffix
    def workerType = clusterCfg.machineType ?: 'm5.xlarge'
    def creationTime = System.currentTimeMillis() / 1000 as long

    libraries.credentials.withOpenShiftCredentials {
        withEnv([
            "CLUSTER_SUFFIX=${clusterSuffix}",
            "CLUSTER_NAME=${clusterCfg.clusterName}",
            "KUBECONFIG=openshift/${clusterSuffix}/auth/kubeconfig",
            "WORKER_TYPE=${workerType}",
            "CREATION_TIME=${creationTime}"
        ]) {
            try {
                libraries.helpers.retryCommand(
                    command: '''
                        timeout 60m bash -c '
                            # Start from an empty state dir: a retry must not reuse the installer state of a
                            # previous attempt (already destroyed by the cleanup that runs before each retry).
                            # NOTE: no single quotes in this block, it is wrapped in bash -c.
                            rm -rf openshift/$CLUSTER_SUFFIX
                            mkdir -p openshift/$CLUSTER_SUFFIX
                        
                            # Generate install-config from template
                            sed \
                                -e "s|<worker-type>|$WORKER_TYPE|g" \
                                -e "s|<cluster-name>|$CLUSTER_NAME-$CLUSTER_SUFFIX|g" \
                                -e "s|<region>|''' + clusterCfg.region + '''|g" \
                                -e "s|<product>|''' + clusterCfg.product + '''|g" \
                                -e "s|<creation-time>|$CREATION_TIME|g" \
                                cloud/common/files/openshift-cluster-template.yaml > openshift/$CLUSTER_SUFFIX/install-config.yaml
                        
                            cat $OPENSHIFT_CONF_FILE >> openshift/$CLUSTER_SUFFIX/install-config.yaml
                        
                            /usr/local/bin/openshift-install create cluster --dir=openshift/$CLUSTER_SUFFIX --log-level=debug || {
                                /usr/local/bin/openshift-install gather bootstrap --dir=openshift/$CLUSTER_SUFFIX || true
                                exit 1
                            }
                        '
                    ''',
                    afterCommand: """
                        export KUBECONFIG=openshift/${clusterSuffix}/auth/kubeconfig
                    
                        # Wait for API server to be fully ready
                        for i in \$(seq 1 60); do
                            if kubectl cluster-info >/dev/null 2>&1 && kubectl get nodes >/dev/null 2>&1; then
                                echo "Cluster API server is ready"
                                break
                            fi
                            echo "Waiting for cluster API server... (\$i/60)"
                            sleep 10
                        done
                    
                        # Configure Docker registry credentials
                        TMP=\$(mktemp)
                        oc get secret/pull-secret -n openshift-config --template='{{index .data ".dockerconfigjson" | base64decode}}' > \$TMP
                        oc registry login --registry='docker.io' --auth-basic="\$DOCKER_READ_USER:\$DOCKER_READ_PASS" --to=\$TMP
                        oc set data secret/pull-secret -n openshift-config --from-file=.dockerconfigjson=\$TMP
                        rm -rf \$TMP
                        kubectl get nodes
                    """,
                    afterFunction: {
                        enableVolumeSnapshotResources(clusterSuffix)
                        verifyVolumeSnapshotResources(clusterSuffix)
                    },
                    cleanupFunction: {
                        shutdownCluster(clusterCfg, libraries)
                    },
                    maxRetries: 3,
                    delaySeconds: 5
                )
            } finally {
                // Also after a failed creation: the final cleanup may run on another agent.
                stashClusterMetadata(clusterSuffix)
            }
        }
    }

    if (clusterCfg.kubeconfig) {
        sh "cp openshift/${clusterSuffix}/auth/kubeconfig ${clusterCfg.kubeconfig}"
    }
}

String clusterMetadataFile(String clusterSuffix) {
    return "openshift/${clusterSuffix}/metadata.json"
}

String clusterMetadataStash(String clusterSuffix) {
    return "openshift-metadata-${clusterSuffix}"
}

// metadata.json is all openshift-install needs to destroy a cluster. Stashing it lets any agent,
// including the main one running the final cleanup, delete a cluster created on a runner agent.
void stashClusterMetadata(String clusterSuffix) {
    if (fileExists(clusterMetadataFile(clusterSuffix))) {
        stash name: clusterMetadataStash(clusterSuffix), includes: clusterMetadataFile(clusterSuffix)
    }
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

        kubectl apply -f cloud/common/files/openshift-csi-snapshot-template.yaml
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

void shutdownCluster(Map clusterCfg, Map libraries) {
    def clusterSuffix = clusterCfg.clusterSuffix

    if (!fileExists(clusterMetadataFile(clusterSuffix))) {
        try {
            unstash clusterMetadataStash(clusterSuffix)
        } catch (err) {
            echo "No OpenShift metadata for ${clusterSuffix} in this build; nothing to destroy"
            return
        }
    }

    timeout(time: 30, unit: 'MINUTES') {
        libraries.credentials.withOpenShiftCredentials {
            sh """
                /usr/local/bin/openshift-install destroy cluster --dir=openshift/${clusterSuffix} || true
            """
        }
    }
}

return this
