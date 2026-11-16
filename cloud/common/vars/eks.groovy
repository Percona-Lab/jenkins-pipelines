String getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

String getLatestPlatformVersion(Map testVariables) {
    def result = ""
    testVariables.libraries.credentials.withAWSLatestVersionCredentials {
        result = sh(
            script: "aws eks describe-addon-versions --query 'addons[].addonVersions[].compatibilities[].clusterVersion' --output json | jq -r 'flatten | unique | sort | reverse | .[0]'",
            returnStdout: true
        ).trim()
    }
    return result
}

String getMachineType(String arch) {
    error("Architecture selection is not supported for EKS: ${arch}")
}

void createCluster(Map clusterCfg, Map libraries) {
    def clusterSuffix = clusterCfg.clusterSuffix
    def clusterFullName = "${clusterCfg.clusterName}-${clusterSuffix}"
    def creationTime = System.currentTimeMillis() / 1000 as long

    libraries.credentials.withAWSCredentials {
        libraries.helpers.retryCommand(
            command: """
                set -euo pipefail

                # Generate cluster config from template
                sed \\
                    -e 's|<cluster-name>|${clusterFullName}|g' \\
                    -e 's|<region>|${clusterCfg.region}|g' \\
                    -e 's|<platform-version>|${clusterCfg.platformVersion}|g' \\
                    -e 's|<creation-time>|${creationTime}|g' \\
                    -e 's|<product>|${clusterCfg.product}|g' \\
                    cloud/common/files/eks-cluster-template.yaml > cluster-${clusterSuffix}.yaml

                export KUBECONFIG=${clusterCfg.kubeconfig}
                timeout 30m eksctl create cluster -f cluster-${clusterSuffix}.yaml
            """,
            afterCommand: """
                set -euo pipefail
                export KUBECONFIG=${clusterCfg.kubeconfig}

                # Wait for API server to be fully ready
                for i in \$(seq 1 60); do
                    if kubectl cluster-info >/dev/null 2>&1 && kubectl get nodes >/dev/null 2>&1; then
                        echo "Cluster API server is ready"
                        break
                    fi
                    echo "Waiting for cluster API server... (\$i/60)"
                    sleep 10
                done

                # Use GP3 storage class as default, recommended by the provider
                kubectl apply -f cloud/common/files/eks-storage-gp3.yaml
                # Remove GP2 storage class default label, for old clusters
                kubectl annotate storageclass gp2 storageclass.kubernetes.io/is-default-class- --overwrite 2>/dev/null || true
                kubectl create clusterrolebinding cluster-admin-binding1 \\
                    --clusterrole=cluster-admin \\
                    --user="\$(aws sts get-caller-identity | jq -r '.Arn')" \\
                    --dry-run=client -o yaml | kubectl apply -f -
                kubectl get storageclass
                kubectl get nodes
            """,
            cleanupFunction: {
                shutdownCluster(clusterCfg, libraries)
            },
            maxRetries: 3,
            delaySeconds: 5
        )

        verifyVolumeSnapshotResources(clusterCfg.kubeconfig, libraries)
    }
}

void verifyVolumeSnapshotResources(String kubeconfig, Map libraries) {
    libraries.credentials.withAWSCredentials {
        sh """
            export KUBECONFIG=${kubeconfig}
            export PATH=/home/ec2-user/.local/bin:\$PATH

            wait_for_deployment() {
                local deployment_name="\$1"

                for i in \$(seq 1 60); do
                    if kubectl get deployment "\$deployment_name" -n kube-system >/dev/null 2>&1; then
                        kubectl wait --for=condition=Available deployment/"\$deployment_name" -n kube-system --timeout=10m
                        return 0
                    fi
                    sleep 10
                done

                kubectl get deployment -n kube-system
                return 1
            }

            wait_for_deployment ebs-csi-controller
            wait_for_deployment snapshot-controller

            kubectl get crd volumesnapshots.snapshot.storage.k8s.io volumesnapshotcontents.snapshot.storage.k8s.io volumesnapshotclasses.snapshot.storage.k8s.io
            kubectl api-resources --api-group=snapshot.storage.k8s.io
        """
    }
}

void shutdownCluster(Map clusterCfg, Map libraries) {
    def clusterSuffix = clusterCfg.clusterSuffix
    def clusterFullName = "${clusterCfg.clusterName}-${clusterSuffix}"
    def region = clusterCfg.region

    timeout(time: 30, unit: 'MINUTES') {
        libraries.credentials.withAWSCredentials {
            sh """
                VPC_ID=\$(eksctl get cluster --name ${clusterFullName} --region ${region} -ojson | jq --raw-output '.[0].ResourcesVpcConfig.VpcId' || true)
                if [ -n "\$VPC_ID" ]; then
                    LOADBALS=\$(aws elb describe-load-balancers --region ${region} --output json | jq --raw-output '.LoadBalancerDescriptions[] | select(.VPCId == "'\$VPC_ID'").LoadBalancerName')
                    for loadbal in \$LOADBALS; do
                        aws elb delete-load-balancer --load-balancer-name \$loadbal --region ${region}
                    done
                    if [ -f cluster-${clusterSuffix}.yaml ]; then
                        eksctl delete cluster -f cluster-${clusterSuffix}.yaml --wait --force --disable-nodegroup-eviction || true
                    else
                        eksctl delete cluster --name ${clusterFullName} --region ${region} --wait --force --disable-nodegroup-eviction || true
                    fi

                    VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region ${region} || true)
                    if [ -n "\$VPC_DESC" ]; then
                        aws ec2 delete-vpc --vpc-id \$VPC_ID --region ${region} || true
                    fi
                    VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region ${region} || true)
                    if [ -n "\$VPC_DESC" ]; then
                        for secgroup in \$(aws ec2 describe-security-groups --filters Name=vpc-id,Values=\$VPC_ID --query 'SecurityGroups[*].GroupId' --output text --region ${region}); do
                            aws ec2 delete-security-group --group-id \$secgroup --region ${region} || true
                        done

                        aws ec2 delete-vpc --vpc-id \$VPC_ID --region ${region} || true
                    fi
                fi
                aws cloudformation delete-stack --stack-name eksctl-${clusterFullName}-cluster --region ${region} || true
                aws cloudformation wait stack-delete-complete --stack-name eksctl-${clusterFullName}-cluster --region ${region} || true

                eksctl get cluster --name ${clusterFullName} --region ${region} || true
                aws cloudformation list-stacks --region ${region} | jq '.StackSummaries[] | select(.StackName | startswith("'eksctl-${clusterFullName}-cluster'"))' || true
            """
        }
    }
}

return this
