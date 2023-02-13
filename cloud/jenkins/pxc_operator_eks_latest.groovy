void CreateCluster( String CLUSTER_SUFFIX ){

    sh """
cat <<-EOF > cluster-${CLUSTER_SUFFIX}.yaml
# An example of ClusterConfig showing nodegroups with mixed instances (spot and on demand):
---
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
    name: ${CLUSTER_NAME}-${CLUSTER_SUFFIX}
    region: eu-west-3
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

void ShutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}
            eksctl delete addon --name aws-ebs-csi-driver --cluster $CLUSTER_NAME-${CLUSTER_SUFFIX} --region eu-west-3
            eksctl delete cluster -f cluster-${CLUSTER_SUFFIX}.yaml --wait --force --disable-nodegroup-eviction
        """
    }
}

void IsRunTestsInClusterWide() {
    if ( "${params.CLUSTER_WIDE}" == "YES" ) {
        env.OPERATOR_NS = 'pxc-operator'
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

TestsReport = '<testsuite name=\\"PXC\\">\n'
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

            GIT_SHORT_COMMIT = sh(script: 'git -C source describe --always --dirty', , returnStdout: true).trim()
            PXC_TAG = sh(script: "if [ -n \"\${IMAGE_PXC}\" ] ; then echo ${IMAGE_PXC} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()
            VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
            testsReportMap[TEST_NAME] = 'failure'

            popArtifactFile("$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}")

            timeout(time: 90, unit: 'MINUTES') {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd'], file(credentialsId: 'eks-conf-file', variable: 'EKS_CONF_FILE')]) {
                    sh """
                        if [ -f "$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}" ]; then
                            echo Skip $TEST_NAME test
                        else
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
                            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}

                            ./e2e-tests/$TEST_NAME/run
                        fi
                    """
                }
            }
            pushArtifactFile("$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}")
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

void conditionalRunTest(String TEST_NAME, String CLUSTER_SUFFIX) {
    if ( TEST_NAME == 'default-cr' ) {
        if ( params.GIT_BRANCH.contains('release-') ) {
            runTest(TEST_NAME, CLUSTER_SUFFIX)
        }
        return 0
    }
    runTest(TEST_NAME, CLUSTER_SUFFIX)
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
    environment {
        CLEAN_NAMESPACE = 1
        CLUSTER_NAME = sh(script: "echo jenkins-lat-pxc-${GIT_SHORT_COMMIT} | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
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
            steps {
                installRpms()
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

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.27.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq

                    curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
                    sudo mv -v /tmp/eksctl /usr/local/bin
                '''

            }
        }
        stage('Build docker image') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
                    sh '''
                        sudo sudo git config --global --add safe.directory '*'
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf source
                        ./cloud/local/checkout $GIT_REPO $GIT_BRANCH

                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml

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
                stage('E2E Upgrade') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('upgrade')
                        runTest('upgrade-haproxy', 'upgrade')
                        runTest('upgrade-proxysql', 'upgrade')
                        runTest('smart-update1', 'upgrade')
                        runTest('smart-update2', 'upgrade')
                        runTest('upgrade-consistency', 'upgrade')
                        ShutdownCluster('upgrade')
                    }
                }
                stage('E2E Basic Tests') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('basic')
                        conditionalRunTest('default-cr', 'basic')
                        runTest('init-deploy', 'basic')
                        runTest('limits', 'basic')
                        runTest('monitoring-2-0', 'basic')
                        runTest('affinity', 'basic')
                        runTest('one-pod', 'basic')
                        runTest('auto-tuning', 'basic')
                        runTest('proxysql-sidecar-res-limits', 'basic')
                        runTest('users', 'basic')
                        runTest('haproxy', 'basic')
                        runTest('tls-issue-self', 'basic')
                        runTest('tls-issue-cert-manager', 'basic')
                        runTest('tls-issue-cert-manager-ref', 'basic')
                        runTest('validation-hook', 'basic')
                        runTest('proxy-protocol', 'basic')
                        ShutdownCluster('basic')
                    }
                }
                stage('E2E Scaling') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('scaling')
                        runTest('scaling', 'scaling')
                        runTest('scaling-proxysql', 'scaling')
                        runTest('security-context', 'scaling')
                        ShutdownCluster('scaling')
                    }
                }
                stage('E2E SelfHealing') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('selfhealing')
                        runTest('storage', 'selfhealing')
                        runTest('self-healing-chaos', 'selfhealing')
                        runTest('self-healing-advanced-chaos', 'selfhealing')
                        runTest('operator-self-healing-chaos', 'selfhealing')
                        ShutdownCluster('selfhealing')
                    }
                }
                stage('E2E Backups') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('backup')
                        runTest('recreate', 'backup')
                        runTest('restore-to-encrypted-cluster', 'backup')
                        runTest('demand-backup', 'backup')
                        runTest('demand-backup-cloud', 'backup')
                        runTest('demand-backup-encrypted-with-tls', 'backup')
                        runTest('pitr', 'backup')
                        runTest('scheduled-backup', 'backup')
                        ShutdownCluster('backup')
                    }
                }
                stage('E2E BigData and CrossSite') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('bigcross')
                        runTest('big-data', 'bigcross')
                        runTest('cross-site', 'bigcross')
                        ShutdownCluster('bigcross')
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
                        export CLUSTER_NAME=$(echo jenkins-lat-pxc-$(git -C source rev-parse --short HEAD) | tr '[:upper:]' '[:lower:]')
                    
                        eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-scaling" --region eu-west-3 > /dev/null 2>&1
                        eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-basic" --region eu-west-3 > /dev/null 2>&1
                        eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-selfhealing" --region eu-west-3 > /dev/null 2>&1
                        eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-backup" --region eu-west-3 > /dev/null 2>&1
                        eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-upgrade" --region eu-west-3 > /dev/null 2>&1
                        eksctl delete addon --name aws-ebs-csi-driver --cluster "$CLUSTER_NAME-bigcross" --region eu-west-3 > /dev/null 2>&1
                        
                        eksctl delete cluster -f cluster-scaling.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1
                        eksctl delete cluster -f cluster-basic.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1
                        eksctl delete cluster -f cluster-selfhealing.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1
                        eksctl delete cluster -f cluster-backup.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1
                        eksctl delete cluster -f cluster-upgrade.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1
                        eksctl delete cluster -f cluster-bigcross.yaml --wait --force --disable-nodegroup-eviction > /dev/null 2>&1
                       
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
