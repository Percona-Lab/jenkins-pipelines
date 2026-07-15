String getPlatformVersion(String version, Map testVariables = [:]) {
    if (version != 'latest') {
        return version
    }

    return sh(
        script: "curl -s https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/${version}/release.txt | sed -n 's/^\\s*Version:\\s\\+\\(\\S\\+\\)\\s*\$/\\1/p'",
        returnStdout: true
    ).trim()
}

void createCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"
    def clusterDir = "openshift/${clusterCfg.clusterSuffix}"

    timeout(time: 60, unit: 'MINUTES') {
        withCredentials([
            aws(credentialsId: 'openshift-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
            file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'),
            file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE'),
            usernamePassword(credentialsId: 'docker.io', passwordVariable: 'DOCKER_READ_PASS', usernameVariable: 'DOCKER_READ_USER')
        ]) {
            sh """
                mkdir -p ${clusterDir}
                timestamp="\$(date +%s)"
tee ${clusterDir}/install-config.yaml << EOF
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
      type: m5.2xlarge
  replicas: 3
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform: {}
  replicas: 1
metadata:
  creationTimestamp: null
  name: ${clusterFullName}
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
      product: psmdb-operator
      creation-time: \$timestamp

publish: External
EOF
                cat \$OPENSHIFT_CONF_FILE >> ${clusterDir}/install-config.yaml
            """

            sshagent(['aws-openshift-41-key']) {
                sh """
                    /usr/local/bin/openshift-install create cluster --dir=${clusterDir} --log-level=debug || {
                        /usr/local/bin/openshift-install gather bootstrap --dir=${clusterDir} || true
                        exit 1
                    }

                    mkdir -p \$(dirname ${clusterCfg.kubeconfig})
                    cp ${clusterDir}/auth/kubeconfig ${clusterCfg.kubeconfig}

                    export KUBECONFIG=${clusterCfg.kubeconfig}
                    TMP=\$(mktemp)
                    oc get secret/pull-secret -n openshift-config --template='{{index .data ".dockerconfigjson" | base64decode}}' > \$TMP
                    oc registry login --registry='docker.io' --auth-basic="\$DOCKER_READ_USER:\$DOCKER_READ_PASS" --to=\$TMP
                    oc set data secret/pull-secret -n openshift-config --from-file=.dockerconfigjson=\$TMP
                    rm -rf \$TMP
                """
            }
        }
    }
}

void shutdownCluster(Map clusterCfg) {
    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([
            aws(credentialsId: 'openshift-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'),
            file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'),
            file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT_CONF_FILE')
        ]) {
            sshagent(['aws-openshift-41-key']) {
                sh """
                    /usr/local/bin/openshift-install destroy cluster --dir=openshift/${clusterCfg.clusterSuffix} || true
                """
            }
        }
    }
}

return this
