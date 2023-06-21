GKERegion='us-central1-a'
tests=[]
clusters=[]

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("${CLUSTER_SUFFIX}")
    if ("${params.IS_GKE_ALPHA}" == "YES") {
        runGKEclusterAlpha(CLUSTER_SUFFIX)
    } else {
        runGKEcluster(CLUSTER_SUFFIX)
    }
}

void runGKEcluster(String CLUSTER_SUFFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            NODES_NUM=3
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}
            export USE_GKE_GCLOUD_AUTH_PLUGIN=True
            source $HOME/google-cloud-sdk/path.bash.inc
            ret_num=0
            while [ \${ret_num} -lt 15 ]; do
                ret_val=0
                gcloud auth activate-service-account --key-file $CLIENT_SECRET_FILE && \
                gcloud config set project $GCP_PROJECT && \
                gcloud container clusters create --zone $GKERegion $CLUSTER_NAME-${CLUSTER_SUFFIX} --cluster-version $PLATFORM_VER --machine-type n1-standard-4 --preemptible --disk-size 30 --num-nodes=\$NODES_NUM --network=jenkins-ps-vpc --subnetwork=jenkins-ps-${CLUSTER_SUFFIX} --no-enable-autoupgrade --cluster-ipv4-cidr=/21 --labels delete-cluster-after-hours=6 && \
                kubectl create clusterrolebinding cluster-admin-binding --clusterrole cluster-admin --user jenkins@"$GCP_PROJECT".iam.gserviceaccount.com || ret_val=\$?
                if [ \${ret_val} -eq 0 ]; then break; fi
                ret_num=\$((ret_num + 1))
            done
            if [ \${ret_num} -eq 15 ]; then exit 1; fi
        """
   }
}

void runGKEclusterAlpha(String CLUSTER_SUFFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-alpha-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-${CLUSTER_SUFFIX}
            export USE_GKE_GCLOUD_AUTH_PLUGIN=True
            source $HOME/google-cloud-sdk/path.bash.inc
            ret_num=0
            while [ \${ret_num} -lt 15 ]; do
                ret_val=0
                gcloud auth activate-service-account alpha-svc-acct@"${GCP_PROJECT}".iam.gserviceaccount.com --key-file=$CLIENT_SECRET_FILE && \
                gcloud config set project $GCP_PROJECT && \
                gcloud alpha container clusters create --release-channel rapid $CLUSTER_NAME-${CLUSTER_SUFFIX} --cluster-version $PLATFORM_VER --zone $GKERegion --project $GCP_PROJECT --preemptible --disk-size 30 --machine-type n1-standard-4 --num-nodes=4 --min-nodes=4 --max-nodes=6 --network=jenkins-ps-vpc --subnetwork=jenkins-ps-${CLUSTER_SUFFIX} --cluster-ipv4-cidr=/21 --labels delete-cluster-after-hours=6 && \
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=\$(gcloud config get-value core/account) || ret_val=\$?
                if [ \${ret_val} -eq 0 ]; then break; fi
                ret_num=\$((ret_num + 1))
            done
            if [ \${ret_num} -eq 15 ]; then exit 1; fi
        """
   }
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    if ("${params.IS_GKE_ALPHA}" == "YES") {
        ACCOUNT='alpha-svc-acct'
        CRED_ID='gcloud-alpha-key-file'
    } else {
        ACCOUNT='jenkins'
        CRED_ID='gcloud-key-file'
    }
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: CRED_ID, variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            export USE_GKE_GCLOUD_AUTH_PLUGIN=True
            source $HOME/google-cloud-sdk/path.bash.inc
            gcloud auth activate-service-account $ACCOUNT@"$GCP_PROJECT".iam.gserviceaccount.com --key-file=$CLIENT_SECRET_FILE
            gcloud config set project $GCP_PROJECT
            for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
                kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
                kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
                kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
                kubectl delete services --all -n \$namespace --force --grace-period=0 || true
                kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
            done
            kubectl get svc --all-namespaces || true
            gcloud container clusters delete --zone $GKERegion $CLUSTER_NAME-$CLUSTER_SUFFIX || true
        """
    }
}

void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch ${FILE_NAME}
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/${GIT_SHORT_COMMIT}
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void prepareNode() {
    sh '''
        sudo yum install -y jq | true

        if [ ! -d $HOME/google-cloud-sdk/bin ]; then
            rm -rf $HOME/google-cloud-sdk
            curl https://sdk.cloud.google.com | bash
        fi

        source $HOME/google-cloud-sdk/path.bash.inc
        gcloud components install alpha
        gcloud components install kubectl

        curl -s https://get.helm.sh/helm-v3.9.4-linux-amd64.tar.gz \
            | sudo tar -C /usr/local/bin --strip-components 1 -zvxpf -

        sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.29.1/yq_linux_amd64 > /usr/local/bin/yq"
        sudo chmod +x /usr/local/bin/yq

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

void initTests() {
    echo "Populating tests into the tests array!"
    def testList = "${params.TEST_LIST}"
    def suiteFileName = "./source/e2e-tests/${params.TEST_SUITE}"

    if (testList.length() != 0) {
        suiteFileName = './source/e2e-tests/run-custom.csv'
        sh """
            echo -e "${testList}" > ${suiteFileName}
            echo "Custom test suite contains following tests:"
            cat ${suiteFileName}
        """
    }

    def records = readCSV file: suiteFileName

    for (int i=0; i<records.size(); i++) {
        tests.add(["name": records[i][0], "cluster": "NA", "result": "skipped", "time": "0"])
    }

    markPassedTests()
}

void markPassedTests() {
    echo "Marking passed tests in the tests map!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            aws s3 ls "s3://percona-jenkins-artifactory/${JOB_NAME}/${GIT_SHORT_COMMIT}/" || :
        """

        for (int i=0; i<tests.size(); i++) {
            def testName = tests[i]["name"]
            def file="${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-${testName}-${params.PLATFORM_VER}-$PS_TAG"
            def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key ${JOB_NAME}/${GIT_SHORT_COMMIT}/${file} >/dev/null 2>&1", returnStatus: true)

            if (retFileExists == 0) {
                tests[i]["result"] = "passed"
            }
        }
    }
}

TestsReport = '<testsuite name=\\"PS\\">\n'
void makeReport() {
    for (int i=0; i<tests.size(); i++) {
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"]
        def testName = tests[i]["name"]

        TestsReport = TestsReport + '<testcase name=\\"' + testName + '\\" time=\\"' + testTime + '\\"><'+ testResult +'/></testcase>\n'
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void clusterRunner(String cluster) {
    def clusterCreated=0

    for (int i=0; i<tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            if (clusterCreated == 0) {
                createCluster(cluster)
                clusterCreated++
            }
            runTest(i)
        }
    }

    if (clusterCreated >= 1) {
        shutdownCluster(cluster)
    }
}

void runTest(Integer TEST_ID){
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]
    def clusterSuffix = tests[TEST_ID]["cluster"]

    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started on cluster $CLUSTER_NAME-$clusterSuffix !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 90, unit: 'MINUTES') {
                sh """
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

                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix
                    export PATH="${HOME}/.krew/bin:$PATH"
                    source $HOME/google-cloud-sdk/path.bash.inc

                    kubectl kuttl test --config ./e2e-tests/kuttl.yaml --test "^$testName\$"
                """
            }
            pushArtifactFile("${params.GIT_BRANCH}-${GIT_SHORT_COMMIT}-$testName-${params.PLATFORM_VER}-$PS_TAG")
            tests[TEST_ID]["result"] = "passed"
            return true
        }
        catch (exc) {
            if (retryCount >= 1) {
                currentBuild.result = 'FAILURE'
                return true
            }
            retryCount++
            return false
        }
        finally {
            def timeStop = new Date().getTime()
            def durationSec = (timeStop - timeStart) / 1000
            tests[TEST_ID]["time"] = durationSec
            echo "The $testName test was finished!"
        }
    }
}

pipeline {
    environment {
        CLOUDSDK_CORE_DISABLE_PROMPTS = 1
        PS_TAG = sh(script: "if [ -n \"\${IMAGE_MYSQL}\" ]; then echo ${IMAGE_MYSQL} | awk -F':' '{print \$2}'; else echo 'main'; fi", , returnStdout: true).trim()
    }
    parameters {
        choice(
            choices: ['run-release.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE')
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mysql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mysql-operator',
            description: 'percona-server-mysql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '1.24',
            description: 'GKE version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'NO\nYES',
            description: 'GKE alpha/stable',
            name: 'IS_GKE_ALPHA')
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
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                prepareNode()
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

                script {
                    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
                    CLUSTER_NAME = sh(script: "echo jenkins-par-ps-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
                }
                initTests()

                withCredentials([file(credentialsId: 'cloud-secret-file-ps', variable: 'CLOUD_SECRET_FILE')]) {
                    sh """
                        cp $CLOUD_SECRET_FILE ./source/e2e-tests/conf/cloud-secret.yml
                        chmod 600 ./source/e2e-tests/conf/cloud-secret.yml
                    """
                }
                stash includes: "source/**", name: "sourceFILES"
            }
        }
        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
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
                    """
                }
            }
        }
        stage('Run Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            parallel {
                stage('cluster1') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        clusterRunner('cluster4')
                    }
                }
                stage('cluster5') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareNode()
                        unstash "sourceFILES"
                        clusterRunner('cluster5')
                    }
                }
            }
        }
    }
    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            sh """
                echo "${TestsReport}" > TestsReport.xml
            """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml'

            script {
                clusters.each { shutdownCluster(it) }
            }

            sh """
                sudo docker system prune -fa
                sudo rm -rf ./*
                sudo rm -rf $HOME/google-cloud-sdk
            """
            deleteDir()
        }
    }
}
