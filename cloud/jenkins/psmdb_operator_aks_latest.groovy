void CreateCluster(String CLUSTER_SUFFIX) {
    if ( "${params.CLUSTER_WIDE}" == "YES" ) {
        env.OPERATOR_NS = 'psmdb-operator'
    }

    withCredentials([azureServicePrincipal('TEST-AZURE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}
            ret_num=0
            while [ \${ret_num} -lt 15 ]; do
                ret_val=0
                az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID"  --allow-no-subscriptions
                az account show --query "{subscriptionId:id, tenantId:tenantId}"
                az account list --all --output table
                az aks create -g percona-operators --subscription eng-cloud-dev  -n $CLUSTER_NAME-${CLUSTER_SUFFIX} --load-balancer-sku basic --enable-managed-identity --node-count 3 --node-vm-size Standard_B4ms --min-count 3 --max-count 3 --node-osdisk-size 30 --network-plugin kubenet  --generate-ssh-keys --enable-cluster-autoscaler --outbound-type loadbalancer --kubernetes-version ${params.PLATFORM_VER} -l westeurope
                az aks get-credentials --subscription eng-cloud-dev --resource-group percona-operators --name $CLUSTER_NAME-${CLUSTER_SUFFIX} 
                if [ \${ret_val} -eq 0 ]; then break; fi
                ret_num=\$((ret_num + 1))
            done
            if [ \${ret_num} -eq 15 ]; then exit 1; fi
        """
    }
}
void ShutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([azureServicePrincipal('TEST-AZURE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID" --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
            az aks delete --name $CLUSTER_NAME-${CLUSTER_SUFFIX} --resource-group percona-operators --subscription eng-cloud-dev  --yes --no-wait
        """
    }
}
void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${env.GIT_SHORT_COMMIT}
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    echo "Try to get $FILE_NAME file from S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${env.GIT_SHORT_COMMIT}
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
        """
    }
}

testsResultsMap = [:]
testsReportMap = [:]
TestsReport = '<testsuite name=\\"PSMDB\\">\n'
void makeReport() {
    for ( test in testsReportMap ) {
        TestsReport = TestsReport + "<testcase name=\\\"${test.key}\\\"><${test.value}/></testcase>\n"
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void setTestsresults() {
    testsResultsMap.each { file ->
        pushArtifactFile("${file.key}")
    }
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

            withCredentials([azureServicePrincipal('TEST-AZURE')]) {
                sh """
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

                        export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}
                        ./e2e-tests/$TEST_NAME/run
                    fi
                """
            }
            pushArtifactFile("$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG-CW_${params.CLUSTER_WIDE}")
            testsResultsMap["${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG-CW_${params.CLUSTER_WIDE}"] = 'passed'
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
        sudo yum install -y jq | true
                cat <<EOF > /tmp/kubernetes.repo
[kubernetes]
name=Kubernetes
baseurl=https://packages.cloud.google.com/yum/repos/kubernetes-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/yum-key.gpg https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo mv /tmp/kubernetes.repo /etc/yum.repos.d
        sudo yum install -y jq python3-pip kubectl || true
    """
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '1.23',
            description: 'AKS kubernetes version',
            name: 'PLATFORM_VER')
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
            description: 'Backup image: perconalab/percona-server-mongodb-operator:main-backup',
            name: 'IMAGE_BACKUP')
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
                    curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq
                    
                    if ! command -v az &>/dev/null; then
                        curl -L https://azurecliprod.blob.core.windows.net/install.py -o install.py
                        printf "/usr/azure-cli\\n/usr/bin" | sudo  python3 install.py
                    fi
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
        stage('Run Tests') {
            environment {
                CLOUDSDK_CORE_DISABLE_PROMPTS = 1
                CLEAN_NAMESPACE = 1
                GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                VERSION = "${env.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}"
                CLUSTER_NAME = sh(script: "echo jenkins-lat-psmdb-${GIT_SHORT_COMMIT} | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
            }
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
                        runTest('data-sharded', 'scaling')
                        runTest('non-voting', 'scaling')
                        runTest('data-at-rest-encryption', 'scaling')
                        ShutdownCluster('scaling')
                    }
                }
                stage('E2E Basic Tests') {
                    steps {
                        CreateCluster('basic')
                        conditionalRunTest('default-cr', 'basic')
                        runTest('one-pod', 'basic')
                        runTest('monitoring-2-0', 'basic')
                        runTest('arbiter', 'basic')
                        runTest('service-per-pod', 'basic')
                        runTest('liveness', 'basic')
                        runTest('users', 'basic')
                        ShutdownCluster('basic')
                    }
                }
                stage('E2E SelfHealing') {
                    steps {
                        CreateCluster('selfhealing')
                        runTest('storage', 'selfhealing')
                        runTest('self-healing', 'selfhealing')
                        runTest('self-healing-chaos', 'selfhealing')
                        runTest('operator-self-healing', 'selfhealing')
                        runTest('operator-self-healing-chaos', 'selfhealing')
                        ShutdownCluster('selfhealing')
                    }
                }
                stage('E2E Backups') {
                    steps {
                        CreateCluster('backups')
                        sleep 60
                        runTest('upgrade', 'backups')
                        runTest('upgrade-consistency', 'backups')
                        runTest('demand-backup', 'backups')
                        runTest('demand-backup-sharded', 'backups')
                        runTest('scheduled-backup', 'backups')
                        ShutdownCluster('backups')
                    }
                }
                stage('CrossSite replication') {
                    steps {
                        CreateCluster('cross-site')
                        runTest('cross-site-sharded', 'cross-site')
                        runTest('upgrade-sharded', 'cross-site')
                        runTest('pitr', 'cross-site')
                        runTest('pitr-sharded', 'cross-site')
                        ShutdownCluster('cross-site')
                    }
                }
            }
        }
    }
    post {
        always {
           setTestsresults()
            makeReport()
            sh """
                    echo "${TestsReport}" > TestsReport.xml
                """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml'
            script {
                if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
                    slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, ${BUILD_URL}"
                    slackSend channel: '@${OWNER_SLACK}', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result}, ${BUILD_URL}"
                }
            }
            withCredentials([azureServicePrincipal('TEST-AZURE')]) {
                sh """
                    export CLUSTER_NAME=\$(echo jenkins-lat-psmdb-\$(git -C source rev-parse --short HEAD) | tr '[:upper:]' '[:lower:]')
                    az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID" --allow-no-subscriptions
                    az account set -s "$AZURE_SUBSCRIPTION_ID"
                    az group list --query "[?starts_with(name, $CLUSTER_NAME)].name | [0]" | xargs az aks delete --resource-group percona-operators --subscription eng-cloud-dev --yes --no-wait || true
                """
            }

        sh '''
            sudo docker rmi -f \$(sudo docker images -q) || true
            sudo rm -rf ./*
        '''
        deleteDir()
        }
    }
}
