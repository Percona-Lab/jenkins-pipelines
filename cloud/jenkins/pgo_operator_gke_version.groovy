GKERegion='us-central1-c'

void CreateCluster(String CLUSTER_PREFIX) {
    if ( "${params.IS_GKE_ALPHA}" == "YES" ) {
        runGKEclusterAlpha(CLUSTER_PREFIX)
    } else {
       runGKEcluster(CLUSTER_PREFIX)
    }
}
void runGKEcluster(String CLUSTER_PREFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
            export USE_GKE_GCLOUD_AUTH_PLUGIN=True
            source $HOME/google-cloud-sdk/path.bash.inc
            ret_num=0
            while [ \${ret_num} -lt 15 ]; do
                ret_val=0
                gcloud auth activate-service-account --key-file $CLIENT_SECRET_FILE && \
                gcloud config set project $GCP_PROJECT && \
                gcloud container clusters create --zone ${GKERegion} \$(echo $CLUSTER_NAME-${CLUSTER_PREFIX} | cut -c-40) --cluster-version $GKE_VERSION --machine-type n1-standard-4 --preemptible --num-nodes=3 --network=jenkins-vpc --subnetwork=jenkins-${CLUSTER_PREFIX} --no-enable-autoupgrade && \
                kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user jenkins@"$GCP_PROJECT".iam.gserviceaccount.com || ret_val=\$?
                if [ \${ret_val} -eq 0 ]; then break; fi
                ret_num=\$((ret_num + 1))
            done
            if [ \${ret_num} -eq 15 ]; then exit 1; fi
        """
   }
}
void runGKEclusterAlpha(String CLUSTER_PREFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-alpha-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
            export USE_GKE_GCLOUD_AUTH_PLUGIN=True
            source $HOME/google-cloud-sdk/path.bash.inc
            ret_num=0
            while [ \${ret_num} -lt 15 ]; do
                ret_val=0
                gcloud auth activate-service-account alpha-svc-acct@"${GCP_PROJECT}".iam.gserviceaccount.com --key-file=$CLIENT_SECRET_FILE && \
                gcloud config set project $GCP_PROJECT && \
                gcloud alpha container clusters create --release-channel rapid \$(echo $CLUSTER_NAME-${CLUSTER_PREFIX} | cut -c-40) --zone ${GKERegion} --cluster-version $GKE_VERSION --project $GCP_PROJECT --preemptible --machine-type n1-standard-4 --num-nodes=4 --min-nodes=4 --max-nodes=6 --network=jenkins-vpc --subnetwork=jenkins-${CLUSTER_PREFIX} && \
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=\$(gcloud config get-value core/account) || ret_val=\$?
                if [ \${ret_val} -eq 0 ]; then break; fi
                ret_num=\$((ret_num + 1))
            done
            if [ \${ret_num} -eq 15 ]; then exit 1; fi
        """
   }
}
void ShutdownCluster(String CLUSTER_PREFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-alpha-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
            export USE_GKE_GCLOUD_AUTH_PLUGIN=True
            source $HOME/google-cloud-sdk/path.bash.inc
            gcloud auth activate-service-account alpha-svc-acct@"${GCP_PROJECT}".iam.gserviceaccount.com --key-file=$CLIENT_SECRET_FILE
            gcloud config set project $GCP_PROJECT
            gcloud container clusters delete --zone ${GKERegion} \$(echo $CLUSTER_NAME-${CLUSTER_PREFIX} | cut -c-40)
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
TestsReport = '<testsuite name=\\"PGO\\">\n'

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
            PPG_TAG = sh(script: "if [ -n \"\${PGO_POSTGRES_HA_IMAGE}\" ] ; then echo ${PGO_POSTGRES_HA_IMAGE} | awk -F':' '{print \$2}' | grep -oE '[A-Za-z0-9\\.]+-ppg[0-9]{2}' ; else echo 'main-ppg13'; fi", , returnStdout: true).trim()
            popArtifactFile("${env.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.GKE_VERSION}-$PPG_TAG")

            timeout(time: 90, unit: 'MINUTES') {
                sh """
                    if [ -f "${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.GKE_VERSION}-$PPG_TAG" ]; then
                        echo Skip $TEST_NAME test
                    else
                        cd ./source
                        if [ -n "${PG_VERSION}" ]; then
                            export PG_VER=${PG_VERSION}
                        fi
                        if [ -n "${PGO_OPERATOR_IMAGE}" ]; then
                            export IMAGE_OPERATOR=${PGO_OPERATOR_IMAGE}
                        else
                            export IMAGE_OPERATOR=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-postgres-operator
                        fi

                        if [ -n "${PGO_APISERVER_IMAGE}" ]; then
                            export IMAGE_APISERVER=${PGO_APISERVER_IMAGE}
                        else
                            export IMAGE_APISERVER=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-apiserver
                        fi

                        if [ -n "${PGO_EVENT_IMAGE}" ]; then
                            export IMAGE_PGOEVENT=${PGO_EVENT_IMAGE}
                        else
                            export IMAGE_PGOEVENT=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-event
                        fi

                        if [ -n "${PGO_RMDATA_IMAGE}" ]; then
                            export IMAGE_RMDATA=${PGO_RMDATA_IMAGE}
                        else
                            export IMAGE_RMDATA=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-rmdata
                        fi

                        if [ -n "${PGO_SCHEDULER_IMAGE}" ]; then
                            export IMAGE_SCHEDULER=${PGO_SCHEDULER_IMAGE}
                        else
                            export IMAGE_SCHEDULER=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-scheduler
                        fi

                        if [ -n "${PGO_DEPLOYER_IMAGE}" ]; then
                            export IMAGE_DEPLOYER=${PGO_DEPLOYER_IMAGE}
                        else
                            export IMAGE_DEPLOYER=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}-pgo-deployer
                        fi

                        if [ -n "${PGO_PGBOUNCER_IMAGE}" ]; then
                            export IMAGE_PGBOUNCER=${PGO_PGBOUNCER_IMAGE}
                        fi

                        if [ -n "${PGO_POSTGRES_HA_IMAGE}" ]; then
                            export IMAGE_PG_HA=${PGO_POSTGRES_HA_IMAGE}
                            export PG_VER=\$(echo \${IMAGE_PG_HA} | grep -Eo 'ppg[0-9]+'| sed 's/ppg//g')
                        fi

                        if [ -n "${PGO_BACKREST_IMAGE}" ]; then
                            export IMAGE_BACKREST=${PGO_BACKREST_IMAGE}
                        fi

                        if [ -n "${PGO_BACKREST_REPO_IMAGE}" ]; then
                            export IMAGE_BACKREST_REPO=${PGO_BACKREST_REPO_IMAGE}
                        fi

                        if [ -n "${PGO_PGBADGER_IMAGE}" ]; then
                            export IMAGE_PGBADGER=${PGO_PGBADGER_IMAGE}
                        fi

                        export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
                        source $HOME/google-cloud-sdk/path.bash.inc
                        ./e2e-tests/$TEST_NAME/run
                    fi
                """
            }
            pushArtifactFile("${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.GKE_VERSION}-$PPG_TAG")
            testsResultsMap["${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.GKE_VERSION}-$PPG_TAG"] = 'passed'
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

void installRpms() {
    sh '''
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y jq | true
    '''
}
pipeline {
    environment {
        CLOUDSDK_CORE_DISABLE_PROMPTS = 1
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-postgresql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-postgresql-operator',
            description: 'percona-postgresql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '1.19',
            description: 'GKE version',
            name: 'GKE_VERSION')
        string(
            defaultValue: '',
            description: 'PG version',
            name: 'PG_VERSION')
        choice(
            choices: 'NO\nYES',
            description: 'GKE alpha/stable',
            name: 'IS_GKE_ALPHA')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-postgresql-operator:main-postgres-operator',
            name: 'PGO_OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators API server image: perconalab/percona-postgresql-operator:main-pgo-apiserver',
            name: 'PGO_APISERVER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators event server image: perconalab/percona-postgresql-operator:main-pgo-event',
            name: 'PGO_EVENT_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators rmdata image: perconalab/percona-postgresql-operator:main-pgo-rmdata',
            name: 'PGO_RMDATA_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators scheduler image: perconalab/percona-postgresql-operator:main-pgo-scheduler',
            name: 'PGO_SCHEDULER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators deployer image: perconalab/percona-postgresql-operator:main-pgo-deployer',
            name: 'PGO_DEPLOYER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators pgBouncer image: perconalab/percona-postgresql-operator:main-ppg13-pgbouncer',
            name: 'PGO_PGBOUNCER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators postgres image: perconalab/percona-postgresql-operator:main-ppg13-postgres-ha',
            name: 'PGO_POSTGRES_HA_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators backrest utility image: perconalab/percona-postgresql-operator:main-ppg13-pgbackrest',
            name: 'PGO_BACKREST_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators backrest utility image: perconalab/percona-postgresql-operator:main-ppg13-pgbackrest-repo',
            name: 'PGO_BACKREST_REPO_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators pgBadger image: perconalab/percona-postgresql-operator:main-ppg13-pgbadger',
            name: 'PGO_PGBADGER_IMAGE')
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
                    if [ ! -d $HOME/google-cloud-sdk/bin ]; then
                        rm -rf $HOME/google-cloud-sdk
                        curl https://sdk.cloud.google.com | bash
                    fi

                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud components install alpha
                    gcloud components install kubectl

                    curl -s https://get.helm.sh/helm-v3.2.3-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                    curl -s -L https://github.com/openshift/origin/releases/download/v3.11.0/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 --wildcards -zxvpf - '*/oc'

                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq
                '''
                unstash "sourceFILES"
                withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE'), file(credentialsId: 'cloud-minio-secret-file', variable: 'CLOUD_MINIO_SECRET_FILE')]) {
                    sh '''
                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        cp $CLOUD_MINIO_SECRET_FILE ./source/e2e-tests/conf/cloud-secret-minio-gw.yml
                    '''
                }
            }
        }
        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        if [ -n "${PGO_OPERATOR_IMAGE}" ]; then
                            echo "SKIP: Build is not needed, PG operator image was set!"
                        else
                            cd ./source/
                            sg docker -c "
                                docker login -u '${USER}' -p '${PASS}'
                                export IMAGE_URI_BASE=perconalab/percona-postgresql-operator:$GIT_BRANCH
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
                CLUSTER_NAME = sh(script: "echo jkns-ver-pgo-${PG_VERSION}-${GIT_SHORT_COMMIT} | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
                PGO_K8S_NAME = "${env.CLUSTER_NAME}-upstream"
                ECR = "119175775298.dkr.ecr.us-east-1.amazonaws.com"
            }
            parallel {
                stage('E2E Basic tests') {
                    steps {
                        CreateCluster('sandbox')
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            runTest('init-deploy', 'sandbox')
                        }
                        runTest('scaling', 'sandbox')
                        runTest('recreate', 'sandbox')
                        runTest('affinity', 'sandbox')
                        runTest('monitoring', 'sandbox')
                        runTest('self-healing', 'sandbox')
                        runTest('operator-self-healing', 'sandbox')
                        runTest('clone-cluster', 'sandbox')
                        runTest('tls-check', 'sandbox')
                        runTest('users', 'sandbox')
                        runTest('ns-mode', 'sandbox')
                        ShutdownCluster('sandbox')
                    }
                }
                stage('E2E Backups') {
                    steps {
                        CreateCluster('backups')
                        runTest('demand-backup', 'backups')
                        runTest('scheduled-backup', 'backups')
                        ShutdownCluster('backups')
                    }
                }
                stage('E2E Upgrade') {
                    steps {
                        CreateCluster('upgrade')
                        runTest('upgrade', 'upgrade')
                        runTest('smart-update', 'upgrade')
                        runTest('version-service', 'upgrade')
                        ShutdownCluster('upgrade')
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

            withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-alpha-key-file', variable: 'CLIENT_SECRET_FILE')]) {
                sh '''
                    export CLUSTER_NAME=$(echo jkns-ver-pgo-${PG_VERSION}-$(git -C source rev-parse --short HEAD) | tr '[:upper:]' '[:lower:]')
                    source $HOME/google-cloud-sdk/path.bash.inc
                    gcloud auth activate-service-account alpha-svc-acct@"${GCP_PROJECT}".iam.gserviceaccount.com --key-file=$CLIENT_SECRET_FILE
                    gcloud config set project $GCP_PROJECT
                    gcloud container clusters list --format='csv[no-heading](name)' --filter $CLUSTER_NAME | xargs gcloud container clusters delete --zone ${GKERegion} --quiet || true
                '''
            }
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./*
                sudo rm -rf $HOME/google-cloud-sdk
            '''
            deleteDir()
        }
    }
}
