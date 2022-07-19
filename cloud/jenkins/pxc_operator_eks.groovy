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

void runTest(String TEST_NAME) {
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

                            export PATH=/home/ec2-user/.local/bin:$PATH
                            source $HOME/google-cloud-sdk/path.bash.inc
                            export KUBECONFIG=~/.kube/config

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

void conditionalRunTest(String TEST_NAME) {
    if ( TEST_NAME == 'default-cr' ) {
        if ( params.GIT_BRANCH.contains('release-') ) {
            runTest(TEST_NAME)
        }
        return 0
    }
    runTest(TEST_NAME)
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
            defaultValue: '1.20',
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

                    curl -s https://get.helm.sh/helm-v3.2.3-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 > /usr/local/bin/yq"
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
                sh '''
cat <<-EOF > cluster.yaml
# An example of ClusterConfig showing nodegroups with mixed instances (spot and on demand):
---
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
    name: eks-pxc-cluster
    region: eu-west-3
    version: "$PLATFORM_VER"

nodeGroups:
    - name: ng-1
      minSize: 3
      maxSize: 5
      instancesDistribution:
        maxPrice: 0.15
        instanceTypes: ["m5.xlarge", "m5.2xlarge"] # At least two instance types should be specified
        onDemandBaseCapacity: 0
        onDemandPercentageAboveBaseCapacity: 50
        spotInstancePools: 2
      tags:
        'iit-billing-tag': 'jenkins-eks'
EOF
                '''

                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                     sh """
                         export PATH=/home/ec2-user/.local/bin:$PATH
                         source $HOME/google-cloud-sdk/path.bash.inc
                         eksctl create cluster -f cluster.yaml
                     """
               }
               stash includes: 'cluster.yaml', name: 'cluster_conf'

            }
        }
        stage('E2E Upgrade') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                runTest('upgrade-haproxy')
                runTest('upgrade-proxysql')
                runTest('smart-update')
                runTest('upgrade-consistency')
            }
        }
        stage('E2E Basic Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                conditionalRunTest('default-cr')
                runTest('init-deploy')
                runTest('limits')
                runTest('monitoring-2-0')
                runTest('affinity')
                runTest('one-pod')
                runTest('auto-tuning')
                runTest('proxysql-sidecar-res-limits')
                runTest('users')
                runTest('haproxy')
                runTest('tls-issue-self')
                runTest('tls-issue-cert-manager')
                runTest('tls-issue-cert-manager-ref')
                runTest('validation-hook')
                runTest('proxy-protocol')
            }
        }
        stage('E2E Scaling') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                runTest('scaling')
                runTest('scaling-proxysql')
                runTest('security-context')
            }
        }
        stage('E2E SelfHealing') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                runTest('storage')
                runTest('self-healing')
                runTest('self-healing-chaos')
                runTest('self-healing-advanced')
                runTest('self-healing-advanced-chaos')
                runTest('operator-self-healing')
                runTest('operator-self-healing-chaos')
            }
        }
        stage('E2E Backups') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                runTest('recreate')
                runTest('restore-to-encrypted-cluster')
                runTest('demand-backup')
                runTest('demand-backup-encrypted-with-tls')
                runTest('pitr')
                runTest('scheduled-backup')
            }
        }
        stage('E2E BigData') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                runTest('big-data')
            }
        }
        stage('E2E Cross-site') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                runTest('cross-site')
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
                    unstash 'cluster_conf'
                    sh """
                        eksctl delete cluster -f cluster.yaml --wait --force
                    """
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
