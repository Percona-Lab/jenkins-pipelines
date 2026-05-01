def tests = []
def clusters = []
def release_versions = "source/e2e-tests/release_versions"
def pgoCommon

void loadPgoCommon() {
    pgoCommon = load "cloud/common/pgoPipelineCommon.groovy"
}

Map providerConfig() {
    return [
        platformPrefix: 'EKS',
        kubeconfig: "/tmp/$CLUSTER_NAME-\$clusterSuffix"
    ]
}

void initParams() {
    PLATFORM_VER = pgoCommon.initReleaseParams(
        releaseVersions: release_versions,
        pillarVersion: PILLAR_VERSION,
        platformVer: PLATFORM_VER,
        platformPrefix: providerConfig().platformPrefix
    )

    if ("$PLATFORM_VER" == "latest") {
        withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            PLATFORM_VER = sh(script: "aws eks describe-addon-versions --query 'addons[].addonVersions[].compatibilities[].clusterVersion' --output json | jq -r 'flatten | unique | sort | reverse | .[0]'", returnStdout: true).trim()
        }
    }

    pgoCommon.setBuildDescription(PLATFORM_VER, IMAGE_POSTGRESQL, CLUSTER_WIDE, GIT_BRANCH)
    pgoCommon.setDbTag(IMAGE_POSTGRESQL)
}

void createHash() {
    def hash = pgoCommon.createHash(
        gitBranch: GIT_BRANCH,
        platformVer: PLATFORM_VER,
        clusterWide: CLUSTER_WIDE,
        pgVer: PG_VER,
        imageOperator: IMAGE_OPERATOR,
        imagePostgresql: IMAGE_POSTGRESQL,
        imagePgbouncer: IMAGE_PGBOUNCER,
        imageBackrest: IMAGE_BACKREST,
        imagePmmClient: IMAGE_PMM_CLIENT,
        imagePmmServer: IMAGE_PMM_SERVER,
        imagePmm3Client: IMAGE_PMM3_CLIENT,
        imagePmm3Server: IMAGE_PMM3_SERVER,
        imageUpgrade: IMAGE_UPGRADE,
        jobName: JOB_NAME
    )
    GIT_SHORT_COMMIT = hash.gitShortCommit
    PARAMS_HASH = hash.paramsHash
    CLUSTER_NAME = hash.clusterName
}

void prepareProviderAgent() {
    sh """
        curl -fsSL https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_\$(uname -s)_amd64.tar.gz | sudo tar -C /usr/local/bin -xzf - && sudo chmod +x /usr/local/bin/eksctl
    """
}

void clusterRunner(String cluster) {
    pgoCommon.clusterRunner(tests, cluster, this.&createCluster, this.&runTest, this.&shutdownCluster)
}

Map commonTestConfig() {
    return pgoCommon.testConfig(this, providerConfig())
}

void verifyVolumeSnapshotResources(String CLUSTER_SUFFIX) {
    def clusterName = "$CLUSTER_NAME-$CLUSTER_SUFFIX"

    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            export KUBECONFIG=/tmp/${clusterName}
            export PATH=/home/ec2-user/.local/bin:$PATH

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

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    sh """
        timestamp="\$(date +%s)"
tee cluster-${CLUSTER_SUFFIX}.yaml << EOF
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig
metadata:
  name: $CLUSTER_NAME-$CLUSTER_SUFFIX
  region: ${EKS_REGION}
  version: "$PLATFORM_VER"
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
  instanceType: 'm5.xlarge'
  iam:
    attachPolicyARNs:
    - arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy
    - arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy
    - arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
    - arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
    - arn:aws:iam::aws:policy/AmazonS3FullAccess
  preBootstrapCommands:
    - "echo 'vm.nr_hugepages=1024' >> /etc/sysctl.conf"
    - "echo 'vm.hugetlb_shm_group=26' >> /etc/sysctl.conf"
    - "sysctl -p"
  tags:
    'iit-billing-tag': 'jenkins-eks'
    'delete-cluster-after-hours': '6'
    'team': 'cloud'
    'product': 'pg-operator'
EOF
    """

    // this is needed for always post action because pipeline runs earch parallel step on another instance
    stash includes: "cluster-${CLUSTER_SUFFIX}.yaml", name: "cluster-$CLUSTER_SUFFIX-config"

    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        sh """
            export KUBECONFIG=/tmp/${CLUSTER_NAME}-${CLUSTER_SUFFIX}
            export PATH=/home/ec2-user/.local/bin:$PATH
            eksctl create cluster -f cluster-${CLUSTER_SUFFIX}.yaml
            kubectl annotate storageclass gp2 storageclass.kubernetes.io/is-default-class=true
            kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user="\$(aws sts get-caller-identity|jq -r '.Arn')"
        """
    }

    verifyVolumeSnapshotResources(CLUSTER_SUFFIX)
}

void runTest(Integer TEST_ID) {
    def cfg = commonTestConfig()
    cfg.credentials = [aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]
    pgoCommon.runTest(tests, TEST_ID, cfg)
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    unstash "cluster-$CLUSTER_SUFFIX-config"
    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        pgoCommon.cleanupKubernetesNamespaces("/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX")
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            eksctl delete addon --name aws-ebs-csi-driver --cluster $CLUSTER_NAME-$CLUSTER_SUFFIX --region ${EKS_REGION} || true

            VPC_ID=\$(eksctl get cluster --name $CLUSTER_NAME-$CLUSTER_SUFFIX --region ${EKS_REGION} -ojson | jq --raw-output '.[0].ResourcesVpcConfig.VpcId' || true)
            if [ -n "\$VPC_ID" ]; then
                LOADBALS=\$(aws elb describe-load-balancers --region ${EKS_REGION} --output json | jq --raw-output '.LoadBalancerDescriptions[] | select(.VPCId == "'\$VPC_ID'").LoadBalancerName')
                for loadbal in \$LOADBALS; do
                    aws elb delete-load-balancer --load-balancer-name \$loadbal --region ${EKS_REGION}
                done
                eksctl delete cluster -f cluster-${CLUSTER_SUFFIX}.yaml --wait --force --disable-nodegroup-eviction || true

                VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region ${EKS_REGION} || true)
                if [ -n "\$VPC_DESC" ]; then
                    aws ec2 delete-vpc --vpc-id \$VPC_ID --region ${EKS_REGION} || true
                fi
                VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region ${EKS_REGION} || true)
                if [ -n "\$VPC_DESC" ]; then
                    for secgroup in \$(aws ec2 describe-security-groups --filters Name=vpc-id,Values=\$VPC_ID --query 'SecurityGroups[*].GroupId' --output text --region ${EKS_REGION}); do
                        aws ec2 delete-security-group --group-id \$secgroup --region ${EKS_REGION} || true
                    done

                    aws ec2 delete-vpc --vpc-id \$VPC_ID --region ${EKS_REGION} || true
                fi
            fi
            aws cloudformation delete-stack --stack-name eksctl-$CLUSTER_NAME-$CLUSTER_SUFFIX-cluster --region ${EKS_REGION} || true
            aws cloudformation wait stack-delete-complete --stack-name eksctl-$CLUSTER_NAME-$CLUSTER_SUFFIX-cluster --region ${EKS_REGION} || true

            eksctl get cluster --name $CLUSTER_NAME-$CLUSTER_SUFFIX --region ${EKS_REGION} || true
            aws cloudformation list-stacks --region ${EKS_REGION} | jq '.StackSummaries[] | select(.StackName | startswith("'eksctl-$CLUSTER_NAME-$CLUSTER_SUFFIX-cluster'"))' || true
        """
    }
}

pipeline {
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis'], description: 'For release runs. PG version to test. Use -postgis to take PostGIS images from release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'EKS kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'PG_VER', defaultValue: '', description: 'PG version')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg18-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbouncer18')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbackrest18')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'EKS_REGION', defaultValue: 'eu-west-3', description: 'EKS region to use for cluster')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-upgrade')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that requires release documentation')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('weekly-pgo');
    }
    stages {
        stage('Prepare Node') {
            steps {
                script {
                    deleteDir()
                    checkout(scm)
                    loadPgoCommon()
                }
                script { pgoCommon.prepareSources(GIT_BRANCH, false) }
                script { pgoCommon.prepareAgentBase() }
                prepareProviderAgent()
                initParams()
                createHash()
            }
        }
        stage('Docker Build and Push') {
            steps {
                script { pgoCommon.dockerBuildPush(GIT_BRANCH) }
            }
        }
        stage('Init Tests') {
            steps {
                script { pgoCommon.initTests(tests, commonTestConfig()) }
            }
        }
        stage('Run Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            parallel {
                stage('cluster1') {
                    agent {
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    agent {
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    agent {
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    agent {
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster4')
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                def cfg = commonTestConfig()
                cfg.clusters = clusters
                cfg.shutdownClusterFn = this.&shutdownCluster
                pgoCommon.postCleanup(tests, cfg)
            }
        }
    }
}
