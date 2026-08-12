def getPlatformVersion(String prefixVersion) {
    return prefixVersion
}

def getLatestPlatformVersion(Map testVariables) {
    return withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh(
            script: "aws eks describe-addon-versions --query 'addons[].addonVersions[].compatibilities[].clusterVersion' --output json | jq -r 'flatten | unique | sort | reverse | .[0]'",
            returnStdout: true
        ).trim()
    }
}

def getMachineType(String arch) {
    return arch
}

void createCluster(Map clusterCfg) {
    def clusterSuffix = clusterCfg.clusterSuffix
    def clusterFullName = "${clusterCfg.clusterName}-${clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        sh """
            timestamp="\$(date +%s)"
tee cluster-${clusterSuffix}.yaml << EOF
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig
metadata:
  name: ${clusterFullName}
  region: ${clusterCfg.region}
  version: "${clusterCfg.platformVersion}"
  tags:
    'delete-cluster-after-hours': '6'
    'creation-time': '\$timestamp'
    'team': 'cloud'
iam:
  withOIDC: true
addons:
- name: aws-ebs-csi-driver
  wellKnownPolicies:
    ebsCSIController: true
- name: snapshot-controller
nodeGroups:
- name: ng-1
  minSize: 3
  maxSize: 4
  iam:
    attachPolicyARNs:
    - arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy
    - arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy
    - arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
    - arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
  instancesDistribution:
    instanceTypes: ["m5.xlarge", "m5.2xlarge"] # At least two instance types should be specified
  tags:
    'iit-billing-tag': 'jenkins-eks'
    'delete-cluster-after-hours': '6'
    'team': 'cloud'
    'product': '${clusterCfg.product}'
EOF
        """

        withCredentials([aws(credentialsId: 'eks-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
                export KUBECONFIG=/tmp/${clusterFullName}

                eksctl create cluster -f cluster-${clusterSuffix}.yaml
                
                # Use GP3 storage class as default, recommended by the provider
                kubectl apply -f cloud/common/files/eks-storage-gp3.yaml

                # Remove GP2 storage class default label, for old clusters
                kubectl annotate storageclass gp2 \
                    storageclass.kubernetes.io/is-default-class- \
                    --overwrite 2>/dev/null || true

                kubectl create clusterrolebinding cluster-admin-binding1 \
                    --clusterrole=cluster-admin \
                    --user="\$(aws sts get-caller-identity|jq -r '.Arn')"

                kubectl get storageclass
            """
        }
    }

    verifyVolumeSnapshotResources(clusterFullName)
}

void verifyVolumeSnapshotResources(String clusterFullName) {
    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            export KUBECONFIG=/tmp/${clusterFullName}
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

void shutdownCluster(Map clusterCfg) {
    def clusterSuffix = clusterCfg.clusterSuffix
    def clusterFullName = "${clusterCfg.clusterName}-${clusterSuffix}"
    def region = clusterCfg.region

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([aws(credentialsId: 'eks-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
                VPC_ID=\$(eksctl get cluster --name ${clusterFullName} --region ${region} -ojson | jq --raw-output '.[0].ResourcesVpcConfig.VpcId' || true)
                if [ -n "\$VPC_ID" ]; then
                    LOADBALS=\$(aws elb describe-load-balancers --region ${region} --output json | jq --raw-output '.LoadBalancerDescriptions[] | select(.VPCId == "'\$VPC_ID'").LoadBalancerName')
                    for loadbal in \$LOADBALS; do
                        aws elb delete-load-balancer --load-balancer-name \$loadbal --region ${region}
                    done
                    eksctl delete cluster -f cluster-${clusterSuffix}.yaml --wait --force --disable-nodegroup-eviction || true

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
