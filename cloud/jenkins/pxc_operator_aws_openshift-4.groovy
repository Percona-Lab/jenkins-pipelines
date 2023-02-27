void IsRunTestsInClusterWide() {
    if ( "${params.CLUSTER_WIDE}" == "YES" ) {
        env.OPERATOR_NS = 'pxc-operator'
    }
}

void CreateCluster( String CLUSTER_SUFFIX ){
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secret-file', variable: 'OPENSHIFT_CONF_FILE')]) {
        sh """
            mkdir -p openshift/${CLUSTER_SUFFIX}
            cat $OPENSHIFT_CONF_FILE | sed -e "s/name: openshift4-pxc-jenkins/name: openshift4-pxc-jenkins-$CLUSTER_SUFFIX/" > ./openshift/${CLUSTER_SUFFIX}/install-config.yaml
        """
        sshagent(['aws-openshift-41-key']) {
            sh """
                /usr/local/bin/openshift-install create cluster --dir=./openshift/${CLUSTER_SUFFIX}
                export KUBECONFIG=./openshift/${CLUSTER_SUFFIX}/auth/kubeconfig

            """
        }
    }
}

void ShutdownCluster(String CLUSTER_SUFFIX) {

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
        sshagent(['aws-openshift-41-key']) {
            sh """
                /usr/local/bin/openshift-install destroy cluster --dir=./openshift/${CLUSTER_SUFFIX}
            """
        }
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

                        source $HOME/google-cloud-sdk/path.bash.inc
                        export KUBECONFIG=$WORKSPACE/openshift/${CLUSTER_SUFFIX}/auth/kubeconfig
                        oc whoami

                        ./e2e-tests/$TEST_NAME/run
                    fi
                """
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
            defaultValue: '4.10.30',
            description: 'OpenShift version to use',
            name: 'PLATFORM_VER')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
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
        TF_IN_AUTOMATION = 'true'
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
                sh """
                    wget https://releases.hashicorp.com/terraform/0.11.14/terraform_0.11.14_linux_amd64.zip
                    unzip terraform_0.11.14_linux_amd64.zip
                    sudo mv terraform /usr/local/bin/ && rm terraform_0.11.14_linux_amd64.zip
                """
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

                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin --wildcards -zxvpf -
                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin  --wildcards -zxvpf -
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
        stage('Create AWS Infrastructure') {
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
                        conditionalRunTest('default-cr')
                        runTest('init-deploy', 'basic')
                        runTest('limits', 'basic')
                        runTest('affinity', 'basic')
                        runTest('one-pod', 'basic')
                        runTest('auto-tuning', 'basic')
                        runTest('proxysql-sidecar-res-limits', 'basic')
                        runTest('users', 'basic')
                        runTest('haproxy', 'basic')
                        runTest('monitoring-2-0', 'basic')
                        runTest('validation-hook', 'basic')
                        runTest('tls-issue-self', 'basic')
                        runTest('tls-issue-cert-manager', 'basic')
                        runTest('tls-issue-cert-manager-ref', 'basic')
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
                        runTest('demand-backup-encrypted-with-tls', 'backup')
                        runTest('pitr', 'backup')
                        runTest('scheduled-backup', 'backup')
                        ShutdownCluster('backup')
                    }
                }
                stage('E2E BigData') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('big-data')
                        runTest('big-data', 'big-data')
                        ShutdownCluster('big-data')
                    }
                }
                stage('E2E Cross-site') {
                    options {
                        timeout(time: 3, unit: 'HOURS')
                    }
                    steps {
                        CreateCluster('cross-site')
                        runTest('cross-site', 'cross-site')
                        ShutdownCluster('cross-site')
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
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
                     sshagent(['aws-openshift-41-key']) {
                         sh """
                             for cluster_suffix in 'scaling' 'basic' 'cross-site' 'selfhealing' 'backup' 'big-data' 'upgrade'
                             do
                                /usr/local/bin/openshift-install destroy cluster --dir=./openshift/${cluster_suffix}
                             done
                         """
                     }
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
