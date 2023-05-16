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
                gcloud auth activate-service-account --key-file $CLIENT_SECRET_FILE
                gcloud config set project $GCP_PROJECT
                gcloud container clusters list --filter \$(echo $CLUSTER_NAME-${CLUSTER_PREFIX} | cut -c-40) --zone $GKERegion --format='csv[no-heading](name)' | xargs gcloud container clusters delete --zone $GKERegion --quiet || true
                gcloud container clusters create --zone $GKERegion \$(echo $CLUSTER_NAME-${CLUSTER_PREFIX} | cut -c-40) --cluster-version=$GKE_VERSION --machine-type=n1-standard-4 --preemptible --disk-size 30 --num-nodes=3 --network=jenkins-pg-vpc --subnetwork=jenkins-pg-${CLUSTER_PREFIX} --no-enable-autoupgrade --cluster-ipv4-cidr=/21 --labels delete-cluster-after-hours=6 && \
                kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user jenkins@"$GCP_PROJECT".iam.gserviceaccount.com || ret_val=\$?
                if [ \${ret_val} -eq 0 ]; then break; fi
                ret_num=\$((ret_num + 1))
            done
            if [ \${ret_num} -eq 15 ]; then
                gcloud container clusters list --filter \$(echo $CLUSTER_NAME-${CLUSTER_PREFIX} | cut -c-40) --zone $GKERegion --format='csv[no-heading](name)' | xargs gcloud container clusters delete --zone $GKERegion --quiet || true
                exit 1
            fi
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
                gcloud alpha container clusters create --release-channel rapid \$(echo $CLUSTER_NAME-${CLUSTER_PREFIX} | cut -c-40) --zone ${GKERegion} --cluster-version $GKE_VERSION --project $GCP_PROJECT --preemptible --disk-size 30 --machine-type n1-standard-4 --num-nodes=4 --min-nodes=4 --max-nodes=6 --network=jenkins-pg-vpc --subnetwork=jenkins-pg-${CLUSTER_PREFIX} --cluster-ipv4-cidr=/21 --labels delete-cluster-after-hours=6 && \
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=\$(gcloud config get-value core/account) || ret_val=\$?
                if [ \${ret_val} -eq 0 ]; then break; fi
                ret_num=\$((ret_num + 1))
            done
            if [ \${ret_num} -eq 15 ]; then exit 1; fi
        """
   }
}
void ShutdownCluster(String CLUSTER_PREFIX) {
    if ( "${params.IS_GKE_ALPHA}" == "YES" ) {
        ACCOUNT='alpha-svc-acct'
        CRED_ID='gcloud-alpha-key-file'
    } else {
        ACCOUNT='jenkins'
        CRED_ID='gcloud-key-file'
    }
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: CRED_ID, variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
            export USE_GKE_GCLOUD_AUTH_PLUGIN=True
            source $HOME/google-cloud-sdk/path.bash.inc
            gcloud auth activate-service-account $ACCOUNT@"$GCP_PROJECT".iam.gserviceaccount.com --key-file=$CLIENT_SECRET_FILE
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

            timeout(time: 120, unit: 'MINUTES') {
                sh """
                    if [ -f "${params.GIT_BRANCH}-${env.GIT_SHORT_COMMIT}-$TEST_NAME-${params.GKE_VERSION}-$PPG_TAG" ]; then
                        echo Skip $TEST_NAME test
                    else
                        cd ./source
                        if [ -n "${PG_VERSION}" ]; then
                            export PG_VER=${PG_VERSION}
                        fi
                        if [ -n "${PGO_OPERATOR_IMAGE}" ]; then
                            export IMAGE=${PGO_OPERATOR_IMAGE}
                        else
                            export IMAGE=perconalab/percona-postgresql-operator:${env.GIT_BRANCH}
                        fi

                        if [ -n "${PGO_PGBOUNCER_IMAGE}" ]; then
                            export IMAGE_PGBOUNCER=${PGO_PGBOUNCER_IMAGE}
                        fi

                        if [ -n "${PGO_POSTGRES_HA_IMAGE}" ]; then
                            export IMAGE_POSTGRESQL=${PGO_POSTGRES_HA_IMAGE}
                            export PG_VER=\$(echo \${IMAGE_POSTGRESQL} | grep -Eo 'ppg[0-9]+'| sed 's/ppg//g')
                        fi

                        if [ -n "${PGO_BACKREST_IMAGE}" ]; then
                            export IMAGE_BACKREST=${PGO_BACKREST_IMAGE}
                        fi

                        if [ -n "${PGO_PGBADGER_IMAGE}" ]; then
                            export IMAGE_PGBADGER=${PGO_PGBADGER_IMAGE}
                        fi

                        if [ -n "${PMM_SERVER_IMAGE_BASE}" ]; then
                            export IMAGE_PMM_SERVER_REPO=${PMM_SERVER_IMAGE_BASE}
                        fi

                        if [ -n "${PMM_SERVER_IMAGE_TAG}" ]; then
                            export IMAGE_PMM_SERVER_TAG=${PMM_SERVER_IMAGE_TAG}
                        fi

                        if [ -n "${PMM_CLIENT_IMAGE}" ]; then
                            export IMAGE_PMM=${PMM_CLIENT_IMAGE}
                        fi

                        export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_PREFIX}
                        export PATH="$HOME/.krew/bin:$PATH"
                        source $HOME/google-cloud-sdk/path.bash.inc
                        set -o pipefail
                        time kubectl kuttl test --config ./e2e-tests/kuttl.yaml --test "^${TEST_NAME}\$"

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
            defaultValue: '1.24',
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
            description: 'Operator image: perconalab/percona-postgresql-operator:main',
            name: 'PGO_OPERATOR_IMAGE')
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
            description: 'Operators pgBadger image: perconalab/percona-postgresql-operator:main-ppg13-pgbadger',
            name: 'PGO_PGBADGER_IMAGE')
        string(
            defaultValue: 'perconalab/pmm-server',
            description: 'PMM server image base: perconalab/pmm-server',
            name: 'PMM_SERVER_IMAGE_BASE')
        string(
            defaultValue: 'dev-latest',
            description: 'PMM server image tag: dev-latest',
            name: 'PMM_SERVER_IMAGE_TAG')
        string(
            defaultValue: 'perconalab/pmm-client:dev-latest',
            description: 'PMM server image: perconalab/pmm-client:dev-latest',
            name: 'PMM_CLIENT_IMAGE')
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

                    curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -
                    curl -s -L https://github.com/openshift/origin/releases/download/v3.11.0/openshift-origin-client-tools-v3.11.0-0cbc58b-linux-64bit.tar.gz \
                        | sudo tar -C /usr/local/bin --strip-components 1 --wildcards -zxvpf - '*/oc'
                    curl -s -L https://github.com/mitchellh/golicense/releases/latest/download/golicense_0.2.0_linux_x86_64.tar.gz \
                        | sudo tar -C /usr/local/bin --wildcards -zxvpf -
                    sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.14.2/yq_linux_amd64 > /usr/local/bin/yq"
                    sudo chmod +x /usr/local/bin/yq
                    cd "$(mktemp -d)"
                    OS="$(uname | tr '[:upper:]' '[:lower:]')"
                    ARCH="$(uname -m | sed -e 's/x86_64/amd64/')"
                    KREW="krew-${OS}_${ARCH}"
                    curl -fsSLO "https://github.com/kubernetes-sigs/krew/releases/download/v0.4.3/${KREW}.tar.gz"
                    tar zxvf "${KREW}.tar.gz"
                    ./"${KREW}" install krew
                    rm -f "${KREW}.tar.gz"
                    export PATH="${KREW_ROOT:-$HOME/.krew}/bin:$PATH"
                    kubectl krew install kuttl
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
                        runTest('demand-backup', 'sandbox')
                        runTest('start-from-backup', 'sandbox')
                        runTest('affinity', 'sandbox')
                        runTest('monitoring', 'sandbox')
                        ShutdownCluster('sandbox')
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
