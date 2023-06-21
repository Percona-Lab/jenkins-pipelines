AWSRegion='eu-west-3'
tests=[]
clusters=[]

void createCluster(String CLUSTER_SUFFIX){
    clusters.add("${CLUSTER_SUFFIX}")

    sh """
cat <<-EOF > cluster-${CLUSTER_SUFFIX}.yaml
# An example of ClusterConfig showing nodegroups with mixed instances (spot and on demand):
---
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
    name: ${CLUSTER_NAME}-${CLUSTER_SUFFIX}
    region: $AWSRegion
    version: "$PLATFORM_VER"
    tags:
        'delete-cluster-after-hours': '10'
iam:
  withOIDC: true

addons:
- name: aws-ebs-csi-driver
  wellKnownPolicies:
    ebsCSIController: true

nodeGroups:
    - name: ng-1
      minSize: 3
      maxSize: 5
      iam:
        attachPolicyARNs:
        - arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy
        - arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy
        - arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
        - arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
        - arn:aws:iam::aws:policy/AmazonS3FullAccess
      instancesDistribution:
        maxPrice: 0.15
        instanceTypes: ["m5.xlarge", "m5.2xlarge"] # At least two instance types should be specified
        onDemandBaseCapacity: 0
        onDemandPercentageAboveBaseCapacity: 50
        spotInstancePools: 2
      tags:
        'iit-billing-tag': 'jenkins-eks'
        'delete-cluster-after-hours': '10'
        'team': 'cloud'
        'product': 'pxc-operator'
EOF
    """

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            export KUBECONFIG=/tmp/${CLUSTER_NAME}-${CLUSTER_SUFFIX}
            export PATH=/home/ec2-user/.local/bin:$PATH
            source $HOME/google-cloud-sdk/path.bash.inc
            eksctl create cluster -f cluster-${CLUSTER_SUFFIX}.yaml
        """
    }
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            source $HOME/google-cloud-sdk/path.bash.inc
            eksctl delete addon --name aws-ebs-csi-driver --cluster $CLUSTER_NAME-$CLUSTER_SUFFIX --region $AWSRegion || true
            for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
                kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
                kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
                kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
                kubectl delete services --all -n \$namespace --force --grace-period=0 || true
                kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
            done
            kubectl get svc --all-namespaces || true

            VPC_ID=\$(eksctl get cluster --name $CLUSTER_NAME-$CLUSTER_SUFFIX --region $AWSRegion -ojson | jq --raw-output '.[0].ResourcesVpcConfig.VpcId' || true)
            if [ -n "\$VPC_ID" ]; then
                LOADBALS=\$(aws elb describe-load-balancers --region $AWSRegion --output json | jq --raw-output '.LoadBalancerDescriptions[] | select(.VPCId == "'\$VPC_ID'").LoadBalancerName')
                for loadbal in \$LOADBALS; do
                    aws elb delete-load-balancer --load-balancer-name \$loadbal --region $AWSRegion
                done
                eksctl delete cluster -f cluster-${CLUSTER_SUFFIX}.yaml --wait --force --disable-nodegroup-eviction || true

                VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region $AWSRegion || true)
                if [ -n "\$VPC_DESC" ]; then
                    aws ec2 delete-vpc --vpc-id \$VPC_ID --region $AWSRegion || true
                fi
                VPC_DESC=\$(aws ec2 describe-vpcs --vpc-id \$VPC_ID --region $AWSRegion || true)
                if [ -n "\$VPC_DESC" ]; then
                    for secgroup in \$(aws ec2 describe-security-groups --filters Name=vpc-id,Values=\$VPC_ID --query 'SecurityGroups[*].GroupId' --output text --region $AWSRegion); do
                        aws ec2 delete-security-group --group-id \$secgroup --region $AWSRegion || true
                    done

                    aws ec2 delete-vpc --vpc-id \$VPC_ID --region $AWSRegion || true
                fi
            fi
            aws cloudformation delete-stack --stack-name eksctl-$CLUSTER_NAME-$CLUSTER_SUFFIX-cluster --region $AWSRegion || true
            aws cloudformation wait stack-delete-complete --stack-name eksctl-$CLUSTER_NAME-$CLUSTER_SUFFIX-cluster --region $AWSRegion || true

            eksctl get cluster --name $CLUSTER_NAME-$CLUSTER_SUFFIX --region $AWSRegion || true
            aws cloudformation list-stacks --region $AWSRegion | jq '.StackSummaries[] | select(.StackName | startswith("'eksctl-$CLUSTER_NAME-$CLUSTER_SUFFIX-cluster'"))' || true
        """
    }
}

void IsRunTestsInClusterWide() {
    if ("${params.CLUSTER_WIDE}" == "YES") {
        env.OPERATOR_NS = 'pxc-operator'
    }
}

void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${GIT_SHORT_COMMIT}
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void initTests() {
    echo "Populating tests into the tests array!"
    def testList = "${params.TEST_LIST}"
    def suiteFileName = "./source/e2e-tests/${params.TEST_SUITE}"

    if (testList.length() != 0) {
        suiteFileName = './source/e2e-tests/run-custom.csv'
        sh """
            echo -e "${testList}" > ${suiteFileName}
            echo "Custom test suite contains following tests:"
            cat ${suiteFileName}
        """
    }

    def records = readCSV file: suiteFileName

    for (int i=0; i<records.size(); i++) {
        tests.add(["name": records[i][0], "cluster": "NA", "result": "skipped", "time": "0"])
    }

    markPassedTests()
}

void markPassedTests() {
    echo "Marking passed tests in the tests map!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            aws s3 ls "s3://percona-jenkins-artifactory/${JOB_NAME}/${GIT_SHORT_COMMIT}/" || :
        """

        for (int i=0; i<tests.size(); i++) {
            def testName = tests[i]["name"]
            def file="${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-${testName}-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}"
            def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key ${JOB_NAME}/${GIT_SHORT_COMMIT}/${file} >/dev/null 2>&1", returnStatus: true)

            if (retFileExists == 0) {
                tests[i]["result"] = "passed"
            }
        }
    }
}

TestsReport = '<testsuite name=\\"PXC\\">\n'
void makeReport() {
    for (int i=0; i<tests.size(); i++) {
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"]
        def testName = tests[i]["name"]

        TestsReport = TestsReport + '<testcase name=\\"' + testName + '\\" time=\\"' + testTime + '\\"><'+ testResult +'/></testcase>\n'
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void clusterRunner(String cluster) {
    def clusterCreated=0

    for (int i=0; i<tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            if (clusterCreated == 0) {
                createCluster(cluster)
                clusterCreated++
            }
            runTest(i)
        }
    }

    if (clusterCreated >= 1) {
        shutdownCluster(cluster)
    }
}

void runTest(Integer TEST_ID) {
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]
    def clusterSuffix = tests[TEST_ID]["cluster"]

    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started on cluster $CLUSTER_NAME-$clusterSuffix !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 90, unit: 'MINUTES') {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd'], file(credentialsId: 'eks-conf-file', variable: 'EKS_CONF_FILE')]) {
                    sh """
                        cd ./source
                        if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                            export IMAGE=${PXC_OPERATOR_IMAGE}
                        else
                            export IMAGE=perconalab/percona-xtradb-cluster-operator:${env.GIT_BRANCH}
                        fi

                        if [ -n "${IMAGE_PXC}" ]; then
                            export IMAGE_PXC=${IMAGE_PXC}
                        fi

                        if [ -n "${IMAGE_PROXY}" ]; then
                            export IMAGE_PROXY=${IMAGE_PROXY}
                        fi

                        if [ -n "${IMAGE_HAPROXY}" ]; then
                            export IMAGE_HAPROXY=${IMAGE_HAPROXY}
                        fi

                        if [ -n "${IMAGE_BACKUP}" ]; then
                            export IMAGE_BACKUP=${IMAGE_BACKUP}
                        fi

                        if [ -n "${IMAGE_PMM}" ]; then
                            export IMAGE_PMM=${IMAGE_PMM}
                        fi

                        if [ -n "${IMAGE_LOGCOLLECTOR}" ]; then
                            export IMAGE_LOGCOLLECTOR=${IMAGE_LOGCOLLECTOR}
                        fi

                        if [ -n "${IMAGE_PMM_SERVER_REPO}" ]; then
                            export IMAGE_PMM_SERVER_REPO=${IMAGE_PMM_SERVER_REPO}
                        fi

                        if [ -n "${IMAGE_PMM_SERVER_TAG}" ]; then
                            export IMAGE_PMM_SERVER_TAG=${IMAGE_PMM_SERVER_TAG}
                        fi

                        export PATH=/home/ec2-user/.local/bin:$PATH
                        source $HOME/google-cloud-sdk/path.bash.inc
                        export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix
                        ./e2e-tests/$testName/run
                    """
                }
            }
            pushArtifactFile("${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-$testName-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}")
            tests[TEST_ID]["result"] = "passed"
            return true
        }
        catch (exc) {
            if (retryCount >= 1) {
                currentBuild.result = 'FAILURE'
                return true
            }
            retryCount++
            return false
        }
        finally {
            def timeStop = new Date().getTime()
            def durationSec = (timeStop - timeStart) / 1000
            tests[TEST_ID]["time"] = durationSec
            echo "The $testName test was finished!"
        }
    }
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        PXC_TAG = sh(script: "if [ -n \"\${IMAGE_PXC}\" ]; then echo ${IMAGE_PXC} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()
    }
    parameters {
        choice(
            choices: ['run-release.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE')
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '1.24',
            description: 'EKS kubernetes version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'NO\nYES',
            description: 'Run tests with cluster wide',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main',
            name: 'PXC_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'PXC image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0',
            name: 'IMAGE_PXC')
        string(
            defaultValue: '',
            description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:main-proxysql',
            name: 'IMAGE_PROXY')
        string(
            defaultValue: '',
            description: 'PXC haproxy image: perconalab/percona-xtradb-cluster-operator:main-haproxy',
            name: 'IMAGE_HAPROXY')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/percona-xtradb-cluster-operator:main-pmm',
            name: 'IMAGE_PMM')
        string(
            defaultValue: '',
            description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector',
            name: 'IMAGE_LOGCOLLECTOR')
        string(
            defaultValue: '',
            description: 'PMM server image repo: perconalab/pmm-server',
            name: 'IMAGE_PMM_SERVER_REPO')
        string(
            defaultValue: '',
            description: 'PMM server image tag: dev-latest',
            name: 'IMAGE_PMM_SERVER_TAG')
    }
    agent {
         label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES"
                script {
                    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                    CLUSTER_NAME = sh(script: "echo jenkins-par-pxc-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
                }
                initTests()

                sh '''
                    sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
                    sudo percona-release enable-only tools
                    sudo yum install -y percona-xtrabackup-80 jq | true

                    if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                        rm -rf $HOME/google-cloud-sdk
                        curl https://sdk.cloud.google.com | bash
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud components update kubectl
                    gcloud version

                    curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.27.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq

                    curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
                    sudo mv -v /tmp/eksctl /usr/local/bin
                '''
                withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
                    sh """
                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                    """
                }

            }
        }
        stage('Build docker image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
                    sh '''
                        if [ -n "${PXC_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PXC operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                                ./e2e-tests/build
                                docker logout
                            "
                            sudo rm -rf ./build
                        fi
                    '''
                }
            }
        }
        stage('Create EKS Infrastructure') {
            steps {
                IsRunTestsInClusterWide()
            }
        }
        stage('Run tests') {
            parallel {
                stage('cluster1') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster4')
                    }
                }
                stage('cluster5') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster5')
                    }
                }
                stage('cluster6') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        clusterRunner('cluster6')
                    }
                }
            }
        }
    }

    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            sh """
                echo "${TestsReport}" > TestsReport.xml
            """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml'

            script {
                clusters.each { shutdownCluster(it) }
            }

            sh """
                sudo docker system prune -fa
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            """
            deleteDir()
        }
    }
}
