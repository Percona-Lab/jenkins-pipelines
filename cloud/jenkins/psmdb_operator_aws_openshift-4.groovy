void IsRunTestsInClusterWide() {
    if ( "${params.CLUSTER_WIDE}" == "YES" ) {
        env.OPERATOR_NS = 'psmdb-operator'
    }
}

void CreateCluster( String CLUSTER_SUFFIX ){
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE')]) {
        sh """
            platform_version=`echo "\${params.PLATFORM_VER}" | awk -F. '{ printf("%d%03d%03d%03d\\n", \$1,\$2,\$3,\$4); }';`
            version=`echo "4.12.0" | awk -F. '{ printf("%d%03d%03d%03d\\n", \$1,\$2,\$3,\$4); }';`
            if [ \$platform_version -ge \$version ];then
                POLICY="additionalTrustBundlePolicy: Proxyonly"
                NETWORK_TYPE="OVNKubernetes"
            else
                POLICY=""
                NETWORK_TYPE="OpenShiftSDN"
            fi
            mkdir -p openshift/${CLUSTER_SUFFIX}
cat <<-EOF > ./openshift/${CLUSTER_SUFFIX}/install-config.yaml
\$POLICY
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
  name: openshift4-psmdb-jenkins-${CLUSTER_SUFFIX}
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: \$NETWORK_TYPE
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: eu-west-3
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: 8
      team: cloud
      product: psmdb-operator
      
publish: External
EOF
            cat $OPENSHIFT_CONF_FILE >> ./openshift/${CLUSTER_SUFFIX}/install-config.yaml
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

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'psmdb-openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
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

TestsReport = '<testsuite name=\\"PSMDB\\">\n'
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
            VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
            testsReportMap[TEST_NAME] = 'failure'
            MDB_TAG = sh(script: "if [ -n \"\${IMAGE_MONGOD}\" ] ; then echo ${IMAGE_MONGOD} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()

            popArtifactFile("$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG-CW_${params.CLUSTER_WIDE}")

            sh """
                if [ $retryCount -eq 0 ]; then
                    export DEBUG_TESTS=0
                else
                    export DEBUG_TESTS=1
                fi
                if [ -f "$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG-CW_${params.CLUSTER_WIDE}" ]; then
                    echo Skip $TEST_NAME test
                else
                    cd ./source
                    if [ -n "${PSMDB_OPERATOR_IMAGE}" ]; then
                        export IMAGE=${PSMDB_OPERATOR_IMAGE}
                    else
                        export IMAGE=perconalab/percona-server-mongodb-operator:${env.GIT_BRANCH}
                    fi

                    if [ -n "${IMAGE_MONGOD}" ]; then
                        export IMAGE_MONGOD=${IMAGE_MONGOD}
                    fi

                    if [ -n "${IMAGE_BACKUP}" ]; then
                        export IMAGE_BACKUP=${IMAGE_BACKUP}
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

                    source $HOME/google-cloud-sdk/path.bash.inc
                    export KUBECONFIG=$WORKSPACE/openshift/${CLUSTER_SUFFIX}/auth/kubeconfig
                    oc whoami

                    ./e2e-tests/$TEST_NAME/run
                fi
            """
            pushArtifactFile("$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG-CW_${params.CLUSTER_WIDE}")
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
            runTest(TEST_NAME, CLUSTER_SUFFIX )
        }
        return 0
    }
    runTest(TEST_NAME, CLUSTER_SUFFIX)
}

void installRpms() {
    sh """
        sudo yum install -y jq | true
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
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        choice(
            choices: 'NO\nYES',
            description: 'Run tests with cluster wide',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mongodb-operator:main',
            name: 'PSMDB_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'MONGOD image: perconalab/percona-server-mongodb-operator:main-mongod4.0',
            name: 'IMAGE_MONGOD')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:main-pxc5.7-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM image: perconalab/percona-server-mongodb-operator:main-pmm',
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

                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin --wildcards -zxvpf -
                    curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux-$PLATFORM_VER.tar.gz \
                        | sudo tar -C /usr/local/bin  --wildcards -zxvpf -

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.27.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq
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

                        if [ -n "${PSMDB_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PSMDB operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
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
                stage('E2E Scaling') {
                    steps {
                        CreateCluster('scaling')
                        runTest('init-deploy', 'scaling')
                        runTest('limits', 'scaling')
                        runTest('scaling', 'scaling')
                        runTest('security-context', 'scaling')
                        runTest('smart-update', 'scaling')
                        runTest('version-service', 'scaling')
                        runTest('rs-shard-migration', 'scaling')
                        ShutdownCluster('scaling')
                    }
                }
                stage('E2E Basic Tests') {
                    steps {
                        CreateCluster('basic')
                        conditionalRunTest('default-cr', 'basic')
                        runTest('one-pod', 'basic')
                        runTest('arbiter', 'basic')
                        runTest('service-per-pod', 'basic')
                        runTest('liveness', 'basic')
                        runTest('users', 'basic')
                        runTest('data-sharded', 'basic')
                        runTest('monitoring-2-0', 'basic')
                        runTest('non-voting', 'basic')
                        runTest('data-at-rest-encryption', 'basic')
                        runTest('demand-backup-physical-sharded', 'basic')
                        runTest('multi-cluster-service', 'basic')
                        ShutdownCluster('basic')
                    }
                }
                stage('E2E SelfHealing') {
                    steps {
                        CreateCluster('selfhealing')
                        runTest('self-healing-chaos', 'selfhealing')
                        runTest('operator-self-healing-chaos', 'selfhealing')
                        runTest('ignore-labels-annotations', 'selfhealing')
                        runTest('expose-sharded', 'selfhealing')
                        ShutdownCluster('selfhealing')
                    }
                }
                stage('E2E Backups') {
                    steps {
                        CreateCluster('backup')
                        runTest('upgrade', 'backup')
                        runTest('upgrade-consistency', 'backup')
                        runTest('demand-backup', 'backup')
                        runTest('demand-backup-sharded', 'backup')
                        runTest('scheduled-backup', 'backup')
                        runTest('upgrade-sharded', 'backup')
                        runTest('pitr', 'backup')
                        runTest('pitr-sharded', 'backup')
                        runTest('mongod-major-upgrade-sharded', 'backup')
                        runTest('serviceless-external-nodes', 'backup')
                        ShutdownCluster('backup')
                    }
                }
                stage('CrossSite replication') {
                    steps {
                        CreateCluster('cross-site')
                        runTest('cross-site-sharded', 'cross-site')
                        runTest('recover-no-primary', 'cross-site')
                        runTest('demand-backup-physical', 'cross-site')
                        runTest('mongod-major-upgrade', 'cross-site')
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
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'psmdb-openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
                     sshagent(['aws-openshift-41-key']) {
                         sh """
                             for cluster_suffix in 'scaling' 'basic' 'cross-site' 'selfhealing' 'backup'
                             do
                                /usr/local/bin/openshift-install destroy cluster --dir=./openshift/\$cluster_suffix > /dev/null 2>&1 || true
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
