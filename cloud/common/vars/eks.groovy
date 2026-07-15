String getPlatformVersion(String version, Map testVariables = [:]) {
    if (version != 'latest') {
        return version
    }

    def latestVersion
    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        latestVersion = sh(
            script: "aws eks describe-addon-versions --query 'addons[].addonVersions[].compatibilities[].clusterVersion' --output json | jq -r 'flatten | unique | sort | reverse | .[0]'",
            returnStdout: true
        ).trim()
    }

    return latestVersion
}

void createCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        sh """
            timestamp="\$(date +%s)"
tee cluster-${clusterCfg.clusterSuffix}.yaml << EOF
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
    instanceTypes: ["m5.xlarge", "m5.2xlarge"]
  tags:
    'iit-billing-tag': 'jenkins-eks'
    'delete-cluster-after-hours': '6'
    'team': 'cloud'
    'product': 'psmdb-operator'
EOF
        """

        withCredentials([aws(credentialsId: 'eks-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
                export KUBECONFIG=${clusterCfg.kubeconfig}
                eksctl create cluster -f cluster-${clusterCfg.clusterSuffix}.yaml
                kubectl annotate storageclass gp2 storageclass.kubernetes.io/is-default-class=true
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user="\$(aws sts get-caller-identity|jq -r '.Arn')"
            """
        }
    }
}

void shutdownCluster(Map clusterCfg) {
    def clusterFullName = "${clusterCfg.clusterName}-${clusterCfg.clusterSuffix}"

    timeout(time: 30, unit: 'MINUTES') {
        withCredentials([aws(credentialsId: 'eks-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            sh """
                VPC_ID=\$(eksctl get cluster --name ${clusterFullName} --region ${clusterCfg.region} -ojson | jq --raw-output '.[0].ResourcesVpcConfig.VpcId' || true)
                if [ -n "\$VPC_ID" ]; then
                    LOADBALS=\$(aws elb describe-load-balancers --region ${clusterCfg.region} --output json | jq --raw-output '.LoadBalancerDescriptions[] | select(.VPCId == "'\$VPC_ID'").LoadBalancerName')
                    for loadbal in \$LOADBALS; do
                        aws elb delete-load-balancer --load-balancer-name \$loadbal --region ${clusterCfg.region}
                    done
                    eksctl delete cluster -f cluster-${clusterCfg.clusterSuffix}.yaml --wait --force --disable-nodegroup-eviction || true

                    VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region ${clusterCfg.region} || true)
                    if [ -n "\$VPC_DESC" ]; then
                        aws ec2 delete-vpc --vpc-id \$VPC_ID --region ${clusterCfg.region} || true
                    fi
                    VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region ${clusterCfg.region} || true)
                    if [ -n "\$VPC_DESC" ]; then
                        for secgroup in \$(aws ec2 describe-security-groups --filters Name=vpc-id,Values=\$VPC_ID --query 'SecurityGroups[*].GroupId' --output text --region ${clusterCfg.region}); do
                            aws ec2 delete-security-group --group-id \$secgroup --region ${clusterCfg.region} || true
                        done
                        aws ec2 delete-vpc --vpc-id \$VPC_ID --region ${clusterCfg.region} || true
                    fi
                fi
                aws cloudformation delete-stack --stack-name eksctl-${clusterFullName}-cluster --region ${clusterCfg.region} || true
                aws cloudformation wait stack-delete-complete --stack-name eksctl-${clusterFullName}-cluster --region ${clusterCfg.region} || true

                eksctl get cluster --name ${clusterFullName} --region ${clusterCfg.region} || true
                aws cloudformation list-stacks --region ${clusterCfg.region} | jq '.StackSummaries[] | select(.StackName | startswith("'eksctl-${clusterFullName}-cluster'"))' || true
            """
        }
    }
}

return this
