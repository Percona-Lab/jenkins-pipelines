void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to Azure Storage!"
    withCredentials([usernamePassword(credentialsId: 'percona-operators', passwordVariable: 'AZURE_STORAGE_ACCOUNT_KEY', usernameVariable: 'AZURE_STORAGE_ACCOUNT_NAME')]) {
        sh '''
            az storage blob upload-batch --file $JOB_NAME/\$(git -C source describe --always --dirty) --container-name percona-jenkins-artifactory --account-name $AZURE_STORAGE_ACCOUNT_NAME --account-key $AZURE_STORAGE_ACCOUNT_KEY
    '''
    }
}

void popArtifactFile(String FILE_NAME) {
    echo "Try to get $FILE_NAME file from Azure Storage!"
    withCredentials([usernamePassword(credentialsId: 'percona-operators', passwordVariable: 'AZURE_STORAGE_ACCOUNT_KEY', usernameVariable: 'AZURE_STORAGE_ACCOUNT_NAME')]) {
        sh '''
            az storage blob directory download -s "$JOB_NAME/\$(git -C source describe --always --dirty)" -d "${FILE_NAME}"  --container-name percona-jenkins-artifactory --account-name $AZURE_STORAGE_ACCOUNT_NAME --account-key $AZURE_STORAGE_ACCOUNT_KEY || :
        '''
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

void runTest(String TEST_NAME) {
    def retryCount = 0
    waitUntil {
        try {
            echo "The $TEST_NAME test was started!"

            GIT_SHORT_COMMIT = sh(script: 'git -C source describe --always --dirty', , returnStdout: true).trim()
            VERSION = "${env.GIT_BRANCH}-$GIT_SHORT_COMMIT"
            testsReportMap[TEST_NAME] = 'failure'
            MDB_TAG = sh(script: "if [ -n \"\${IMAGE_MONGOD}\" ] ; then echo ${IMAGE_MONGOD} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()

            popArtifactFile("$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG")
// TODO
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd'], file(credentialsId: 'eks-conf-file', variable: 'EKS_CONF_FILE')]) {
                sh """
                    if [ -f "$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG" ]; then
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

                        export PATH=/home/ec2-user/.local/bin:$PATH
                        source $HOME/google-cloud-sdk/path.bash.inc
                        export KUBECONFIG=~/.kube/config

                        ./e2e-tests/$TEST_NAME/run
                    fi
                """
            }
            pushArtifactFile("$VERSION-$TEST_NAME-${params.PLATFORM_VER}-$MDB_TAG")
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
        uname -a
        sudo yum install -y jq | true
        sudo yum install azure-cli
        az --version
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
            defaultValue: '1.20',
            description: 'EKS kubernetes version',
            name: 'PLATFORM_VER')
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
            description: 'PMM image: perconalab/percona-server-mongodb-operator:main-pmm',
            name: 'IMAGE_PMM')
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

                    curl -s https://get.helm.sh/helm-v3.2.3-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq
                '''

            }
        }
//        stage('Build docker image') {
//            steps {
//                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
//                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
//                    sh '''
//                        sudo git reset --hard
//                        sudo git clean -xdf
//                        sudo rm -rf source
//                        ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
//
//                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
//
//                        if [ -n "${PSMDB_OPERATOR_IMAGE}" ]; then
//                            echo "SKIP: Build is not needed, PSMDB operator image was set!"
//                        else
//                            cd ./source/
//                            sg docker -c "
//                                docker login -u '${USER}' -p '${PASS}'
//                                export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
//                                ./e2e-tests/build
//                                docker logout
//                            "
//                            sudo rm -rf ./build
//                        fi
//                    '''
//                }
//            }
//        }
//        stage('Create Azure Infrastructure') {
//            steps {
//                withCredentials([azureServicePrincipal(credentialsId: 'percona-operators', subscriptionIdVariable: 'AZURE_SUBS_ID', clientIdVariable: 'AZURE_CLIENT_ID', clientSecretVariable: 'AZURE_CLIENT_SECRET', tenantIdVariable: 'AZURE_TENANT_ID')]) {
//                     sh """
//                         export PATH=/home/ec2-user/.local/bin:$PATH
//                         source $HOME/google-cloud-sdk/path.bash.inc
//                         az login --service-principal -u $AZURE_CLIENT_ID -p $AZURE_CLIENT_SECRET -t $AZURE_TENANT_ID'
//                         az account set -s $AZURE_SUBS_ID
//                         az aks create -g percona-operators -n aks-psmdb --load-balancer-sku basic --enable-managed-identity --node-count 3 --node-vm-size Standard_B4ms --min-count 3 --max-count 3 --node-osdisk-size 30 --network-plugin kubenet  --generate-ssh-keys --enable-cluster-autoscaler --outbound-type loadbalancer
//                         az aks get-credentials --subscription Pay-As-You-Go --resource-group percona-operators --name aks-psmdb
//                     """
//                }
//                stash includes: 'cluster.yaml', name: 'cluster_conf'
//            }
//        }
//        stage('E2E Scaling') {
//            steps {
//                runTest('init-deploy')
//                runTest('limits')
//                runTest('scaling')
//                runTest('security-context')
//                runTest('smart-update')
//                runTest('version-service')
//                runTest('rs-shard-migration')
//            }
//        }
//        stage('E2E Basic Tests') {
//            steps {
//                conditionalRunTest('default-cr')
//                runTest('one-pod')
//                runTest('monitoring-2-0')
//                runTest('arbiter')
//                runTest('service-per-pod')
//                runTest('liveness')
//                runTest('users')
//                runTest('data-sharded')
//                runTest('non-voting')
//                runTest('cross-site-sharded')
//           }
//        }
//        stage('E2E SelfHealing') {
//            steps {
//                runTest('storage')
//                runTest('self-healing')
//                runTest('self-healing-chaos')
//                runTest('operator-self-healing')
//                runTest('operator-self-healing-chaos')
//            }
//        }
//        stage('E2E Backups') {
//            steps {
//                runTest('upgrade')
//                runTest('upgrade-consistency')
//                runTest('demand-backup')
//                runTest('demand-backup-sharded')
//                runTest('scheduled-backup')
//                runTest('upgrade-sharded')
//                runTest('pitr')
//                runTest('pitr-sharded')
//                runTest('demand-backup-eks-credentials')
//            }
//        }
//        stage('Make report') {
//            steps {
//                makeReport()
//                sh """
//                    echo "${TestsReport}" > TestsReport.xml
//                """
//                step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
//                archiveArtifacts '*.xml'
//            }
//        }
    }

//    post {
//        always {
//                withCredentials([azureServicePrincipal(credentialsId: 'percona-operators', subscriptionIdVariable: 'AZURE_SUBS_ID', clientIdVariable: 'AZURE_CLIENT_ID', clientSecretVariable: 'AZURE_CLIENT_SECRET', tenantIdVariable: 'AZURE_TENANT_ID')]) {
//                    sh """
//                        az aks delete --name aks-psmdb --resource-group percona-operators --yes --no-wait
//                    """
//                }
//
//            sh '''
//                sudo docker rmi -f \$(sudo docker images -q) || true
//                sudo rm -rf $HOME/google-cloud-sdk
//                sudo rm -rf ./*
//            '''
//            deleteDir()
//        }
//    }
}
