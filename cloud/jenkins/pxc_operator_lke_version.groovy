void CreateCluster(String CLUSTER_PREFIX) {
    if ( "${params.CLUSTER_WIDE}" == "YES" ) {
        env.OPERATOR_NS = 'pxc-operator'
    }

    sh '''
        retry() {
            local max=\$1
            local delay=\$2
            shift 2 # cut delay and max args
            local n=1

            until \$@; do
                if [[ \$n -ge \$max ]]; then
                    echo "The command '$@' has failed after \$n attempts."
                    exit 1
                fi
                ((n++))
                sleep \$delay
            done
        }

        CLUSTER_ID="$(linode-cli lke cluster-create --k8s_version \$PLATFORM_VER --label \$CLUSTER_NAME-''' + CLUSTER_PREFIX + ''' --region us-central --node_pools.count 3 --tags jenkins,\$CLUSTER_NAME-''' + CLUSTER_PREFIX + ''' --node_pools.type g6-standard-6 --json | jq '.[].id')"
        if [[ x\$CLUSTER_ID == "x" ]]; then
           echo "No cluster created. Exiting."
           exit 1
        fi
        retry 10 60 linode-cli lke kubeconfig-view \$CLUSTER_ID --json > /dev/null 2>&1
        linode-cli lke kubeconfig-view \$CLUSTER_ID --json | jq -r '.[].kubeconfig' | base64 -d > /tmp/\$CLUSTER_NAME-''' + CLUSTER_PREFIX + '''
        export KUBECONFIG=/tmp/\$CLUSTER_NAME-''' + CLUSTER_PREFIX + '''
        sleep 120
        until [[ \$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\\n"}{end}' | wc -l) -eq 3 ]]; do
            sleep 5
        done

        for i in \$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\\n"}{end}'); do
            kubectl wait --for=condition=Ready --timeout=600s node/\$i;
        done
        kubectl patch storageclass linode-block-storage-retain -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}'
        kubectl patch storageclass linode-block-storage -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
    '''
}

void ShutdownCluster(String CLUSTER_PREFIX) {
    sh '''
        linode-cli lke cluster-delete $(linode-cli lke clusters-list --json | jq '.[] | select(.label == "'"${CLUSTER_NAME}-''' + CLUSTER_PREFIX + '''"'").id' )
        linode-cli volumes list --json | jq '.[] | select( (.label | startswith("pvc")) and .linode_id == null) | .id' | xargs -I {} linode-cli volumes delete {}
    '''
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
TestsReport = '<testsuite name=\\"PXC\\">\n'

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

void runTest(String TEST_NAME, String CLUSTER_PREFIX) {
    def retryCount = 0
    waitUntil {
        try {
            echo "The $TEST_NAME test was started!"
            testsReportMap[TEST_NAME] = 'failure'
            PXC_TAG = sh(script: "if [ -n \"\${IMAGE_PXC}\" ] ; then echo ${IMAGE_PXC} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()
            popArtifactFile("${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}")

            timeout(time: 90, unit: 'MINUTES') {
                sh """
                    if [ -f "${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}" ]; then
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

                        export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
                        ./e2e-tests/$TEST_NAME/run
                    fi
                """
            }
            pushArtifactFile("${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}")
            testsReportMap[TEST_NAME] = 'passed'
            testsResultsMap["${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.PLATFORM_VER}-$PXC_TAG-CW_${params.CLUSTER_WIDE}"] = 'passed'
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
void installRpms() {
    sh '''
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
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 jq python3-pip kubectl || true
    '''
}
pipeline {
    environment {
        CLOUDSDK_CORE_DISABLE_PROMPTS = 1
    }
    parameters {
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
            defaultValue: '1.16',
            description: 'LKE version',
            name: 'PLATFORM_VER')
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
    agent {
        label 'docker'
    }
    options {
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

                installRpms()
                sh '''
                    sudo pip3 install --upgrade linode-cli

                    curl -s https://get.helm.sh/helm-v3.2.3-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq
                '''
                withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE'), file(credentialsId: 'LINODE-CONFIG', variable: 'LKE_CLIENT_FILE')]) {
                    sh '''
                        mkdir ${HOME}/.config || true
                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        cp ${LKE_CLIENT_FILE} ${HOME}/.config/linode-cli || true
                    '''
                }
            }
        }
        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
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
        stage('Run Tests') {
            environment {
                CLOUDSDK_CORE_DISABLE_PROMPTS = 1
                CLEAN_NAMESPACE = 1
                GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                VERSION = "${env.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}"
                CLUSTER_NAME = sh(script: "echo jenkins-pxc-${GIT_SHORT_COMMIT} | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
            }
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            parallel {
                stage('E2E Upgrade') {
                    steps {
                        CreateCluster('upgrade')
                        runTest('upgrade-haproxy', 'upgrade')
                        ShutdownCluster('upgrade')
                        CreateCluster('upgrade')
                        runTest('upgrade-proxysql', 'upgrade')
                        ShutdownCluster('upgrade')
                        CreateCluster('upgrade')
                        runTest('smart-update', 'upgrade')
                        runTest('upgrade-consistency', 'upgrade')
                        ShutdownCluster('upgrade')
                    }
                }
                stage('E2E Basic Tests') {
                    steps {
                        CreateCluster('basic')
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
                        ShutdownCluster('basic')
                   }
                }
                stage('E2E Scaling') {
                    steps {
                        CreateCluster('scaling')
                        runTest('scaling', 'scaling')
                        runTest('scaling-proxysql', 'scaling')
                        runTest('security-context', 'scaling')
                        ShutdownCluster('scaling')
                    }
                }
                stage('E2E SelfHealing') {
                    steps {
                        CreateCluster('selfheal')
                        runTest('storage', 'selfheal')
                        runTest('self-healing', 'selfheal')
                        runTest('self-healing-chaos', 'selfheal')
                        runTest('self-healing-advanced', 'selfheal')
                        runTest('self-healing-advanced-chaos', 'selfheal')
                        runTest('operator-self-healing', 'selfheal')
                        runTest('operator-self-healing-chaos', 'selfheal')
                        ShutdownCluster('selfheal')
                    }
                }
                stage('E2E Backups') {
                    steps {
                        CreateCluster('backups')
                        runTest('recreate', 'backups')
                        runTest('restore-to-encrypted-cluster', 'backups')
                        runTest('demand-backup', 'backups')
                        runTest('demand-backup-encrypted-with-tls', 'backups')
                        runTest('pitr','backups')
                        runTest('scheduled-backup', 'backups')
                        ShutdownCluster('backups')
                    }
                }
                stage('E2E BigData') {
                    steps {
                        CreateCluster('bigdata')
                        runTest('big-data', 'bigdata')
                        ShutdownCluster('bigdata')
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

            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}
