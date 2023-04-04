AWSRegion='eu-west-3'
void createCluster( String CLUSTER_SUFFIX ){

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
        'product': 'ps-operator'
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
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}
            eksctl delete addon --name aws-ebs-csi-driver --cluster $CLUSTER_NAME-${CLUSTER_SUFFIX} --region $AWSRegion
            eksctl delete cluster -f cluster-${CLUSTER_SUFFIX}.yaml --wait --force --disable-nodegroup-eviction
        """
    }
}

void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git -C source describe --always --dirty)
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    echo "Try to get $FILE_NAME file from S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git -C source describe --always --dirty)
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}

void prepareNode() {
    sh '''
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 jq | true  
    '''

    sh '''
        if [ ! -d $HOME/google-cloud-sdk/bin ]; then
            rm -rf $HOME/google-cloud-sdk
            curl https://sdk.cloud.google.com | bash
        fi

        source $HOME/google-cloud-sdk/path.bash.inc
        gcloud components update kubectl
        gcloud version

        curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
            | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
        
        sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.29.1/yq_linux_amd64 > /usr/local/bin/yq"
        sudo chmod +x /usr/local/bin/yq
        
        curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
        sudo mv -v /tmp/eksctl /usr/local/bin

        cd "$(mktemp -d)"
        OS="$(uname | tr '[:upper:]' '[:lower:]')"
        ARCH="$(uname -m | sed -e 's/x86_64/amd64/')"
        KREW="krew-${OS}_${ARCH}"
        curl -fsSLO "https://github.com/kubernetes-sigs/krew/releases/download/v0.4.3/${KREW}.tar.gz"
        tar zxvf "${KREW}.tar.gz"
        ./"${KREW}" install krew

        export PATH="${KREW_ROOT:-$HOME/.krew}/bin:$PATH"

        kubectl krew install kuttl
    '''
}

TestsReport = '<testsuite name=\\"PSMO\\">\n'
testsReportMap = [:]
void makeReport() {
    for ( test in testsReportMap ) {
        TestsReport = TestsReport + "<testcase name=\\\"${test.key}\\\"><${test.value}/></testcase>\n"
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void runTest(String TEST_NAME, String CLUSTER_SUFFIX) {
    def retryCount = 0
    waitUntil {
        try {
            echo "The $TEST_NAME test was started!"
            testsReportMap[TEST_NAME] = 'failure'

            def FILE_NAME = "${env.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-eks-${env.PLATFORM_VER}"
            popArtifactFile("$FILE_NAME")

            timeout(time: 90, unit: 'MINUTES') {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd'], file(credentialsId: 'eks-conf-file', variable: 'EKS_CONF_FILE')]) {
                    sh """
                        if [ -f "$FILE_NAME" ]; then
                            echo "Skipping $TEST_NAME test because it passed in previous run."
                        else
                            cd ./source
                            if [ -n "${OPERATOR_IMAGE}" ]; then
                                export IMAGE=${OPERATOR_IMAGE}
                            else
                                export IMAGE=perconalab/percona-server-mysql-operator:${env.GIT_BRANCH}
                            fi

                            if [ -n "${IMAGE_MYSQL}" ]; then
                                export IMAGE_MYSQL=${IMAGE_MYSQL}
                            fi

                            if [ -n "${IMAGE_ORCHESTRATOR}" ]; then
                                export IMAGE_ORCHESTRATOR=${IMAGE_ORCHESTRATOR}
                            fi

                            if [ -n "${IMAGE_ROUTER}" ]; then
                                export IMAGE_ROUTER=${IMAGE_ROUTER}
                            fi

                            if [ -n "${IMAGE_BACKUP}" ]; then
                                export IMAGE_BACKUP=${IMAGE_BACKUP}
                            fi

                            if [ -n "${IMAGE_TOOLKIT}" ]; then
                                export IMAGE_TOOLKIT=${IMAGE_TOOLKIT}
                            fi

                            if [ -n "${IMAGE_PMM}" ]; then
                                export IMAGE_PMM=${IMAGE_PMM}
                            fi

                            if [ -n "${IMAGE_PMM_SERVER_REPO}" ]; then
                                export IMAGE_PMM_SERVER_REPO=${IMAGE_PMM_SERVER_REPO}
                            fi

                            if [ -n "${IMAGE_PMM_SERVER_TAG}" ]; then
                                export IMAGE_PMM_SERVER_TAG=${IMAGE_PMM_SERVER_TAG}
                            fi

                            export PATH="/home/ec2-user/.local/bin:${HOME}/.krew/bin:$PATH"
                            source $HOME/google-cloud-sdk/path.bash.inc
                            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}

                            kubectl kuttl test --config ./e2e-tests/kuttl.yaml --test "^${TEST_NAME}\$"
                        fi
                    """
                }
            }
            pushArtifactFile("$FILE_NAME")
            testsReportMap[TEST_NAME] = 'passed'
            return true
        }
        catch (exc) {
            if (retryCount >= 2) {
                currentBuild.result = 'FAILURE'
                return true
            }
            retryCount++
            return false
        }
    }

    echo "The $TEST_NAME test was finished!"
}

void conditionalRunTest(String TEST_NAME, String CLUSTER_SUFFIX ) {
    if ( TEST_NAME == 'default-cr' ) {
        if ( params.GIT_BRANCH.contains('release-') ) {
            runTest(TEST_NAME, CLUSTER_SUFFIX)
        }
        return 0
    }
    runTest(TEST_NAME, CLUSTER_SUFFIX )
}

void installRpms() {
    sh """
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 jq | true
    """
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mysql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mysql-operator',
            description: 'percona-server-mysql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '1.22',
            description: 'EKS kubernetes version',
            name: 'PLATFORM_VER')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mysql-operator:main',
            name: 'OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'PS for MySQL image: perconalab/percona-server-mysql-operator:main-ps8.0',
            name: 'IMAGE_MYSQL')
        string(
            defaultValue: '',
            description: 'Orchestrator image: perconalab/percona-server-mysql-operator:main-orchestrator',
            name: 'IMAGE_ORCHESTRATOR')
        string(
            defaultValue: '',
            description: 'MySQL Router image: perconalab/percona-server-mysql-operator:main-router',
            name: 'IMAGE_ROUTER')
        string(
            defaultValue: '',
            description: 'XtraBackup image: perconalab/percona-server-mysql-operator:main-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'Toolkit image: perconalab/percona-server-mysql-operator:main-toolkit',
            name: 'IMAGE_TOOLKIT')
        string(
            defaultValue: '',
            description: 'HAProxy image: perconalab/percona-server-mysql-operator:main-haproxy',
            name: 'IMAGE_HAPROXY')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM')
        string(
            defaultValue: '',
            description: 'PMM server image repo: perconalab/pmm-server',
            name: 'IMAGE_PMM_SERVER_REPO')
        string(
            defaultValue: '',
            description: 'PMM server image tag: dev-latest',
            name: 'IMAGE_PMM_SERVER_TAG')
    }
    environment {
        CLEAN_NAMESPACE = 1
    }
    agent {
         label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '10', artifactNumToKeepStr: '10'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            prepareNode()
            sh '''
                sudo sudo git config --global --add safe.directory '*'
                sudo git reset --hard
                sudo git clean -xdf
                sudo rm -rf source
                ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
            '''
            withCredentials([file(credentialsId: 'cloud-secret-file-ps', variable: 'CLOUD_SECRET_FILE')]) {
            sh '''
                cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                chmod 600 ./source/e2e-tests/conf/cloud-secret.yml
            '''
            }
            stash includes: "source/**", name: "sourceFILES"
        }
        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        if [ -n "${OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-server-mysql-operator:$GIT_BRANCH
                                ./e2e-tests/build
                                docker logout
                            "
                            sudo rm -rf ./build
                        fi
                    '''
                }
            }
        }

        stage('Run e2e tests') {
            environment {
                GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                CLUSTER_NAME = sh(script: "echo jenkins-par-psmo-${GIT_SHORT_COMMIT} | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
            }
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            parallel {
                stage('Cluster1') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        createCluster('cluster1')
                        runTest('auto-config','cluster1')
//                        runTest('config','cluster1')
//                        runTest('demand-backup', 'cluster1')
//                        runTest('gr-demand-backup', 'cluster1')
//                        runTest('gr-one-pod', 'cluster1')
//                        runTest('gr-ignore-annotations', 'cluster1')
                        shutdownCluster('cluster1')
                    }
                }
                stage('Cluster2') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        createCluster('cluster2')
                        runTest('gr-init-deploy','cluster2')
//                        runTest('haproxy', 'cluster2')
//                        runTest('init-deploy', 'cluster2')
//                        runTest('limits', 'cluster2')
//                        runTest('async-ignore-annotations', 'cluster2')
//                        runTest('gr-scaling', 'cluster2')
                        shutdownCluster('cluster2')
                    }
                }
                stage('Cluster3') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        createCluster('cluster3')
                        runTest('monitoring', 'cluster3')
//                        runTest('one-pod', 'cluster3')
//                        runTest('scaling', 'cluster3')
//                        runTest('semi-sync', 'cluster3')
//                        runTest('config-router', 'cluster3')
//                        runTest('gr-tls-cert-manager', 'cluster3')
                        shutdownCluster('cluster3')
                    }
                }
                stage('Cluster4') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        createCluster('cluster4')
                        runTest('service-per-pod', 'cluster4')
//                        runTest('sidecars', 'cluster4')
//                        runTest('tls-cert-manager', 'cluster4')
//                        runTest('users', 'cluster4')
//                        runTest('version-service', 'cluster4')
                        shutdownCluster('cluster4')
                    }
                }
            }

        }
        stage('Make report') {
            steps {
                makeReport()
                sh """
                    echo "${TestsReport}" > TestsReport.xml
                """
                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
                archiveArtifacts '*.xml'
            }
        }
    }

    post {
        always {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {

                    sh '''
                    export CLUSTER_NAME=$(echo jenkins-par-psmo-$(git -C source rev-parse --short HEAD) | tr '[:upper:]' '[:lower:]')
                    
                    eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-cluster1" --region $AWSRegion > /dev/null 2>&1 || true
                    eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-cluster2" --region $AWSRegion > /dev/null 2>&1 || true
                    eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-cluster3" --region $AWSRegion > /dev/null 2>&1 || true
                    eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-cluster4" --region $AWSRegion > /dev/null 2>&1 || true
                    
                    eksctl delete cluster -f cluster-cluster1.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1 || true
                    eksctl delete cluster -f cluster-cluster2.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1 || true
                    eksctl delete cluster -f cluster-cluster3.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1 || true
                    eksctl delete cluster -f cluster-cluster4.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1 || true
                    '''
                }

            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf $HOME/google-cloud-sdk
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
