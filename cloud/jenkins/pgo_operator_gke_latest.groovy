region='us-central1-c'
tests=[]
clusters=[]

void prepareNode() {
    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        kubectl version --client --output=yaml

        curl -fsSL https://get.helm.sh/helm-v3.12.3-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        sudo sh -c "curl -s -L https://github.com/mikefarah/yq/releases/download/v4.35.1/yq_linux_amd64 > /usr/local/bin/yq"
        sudo chmod +x /usr/local/bin/yq

        sudo sh -c "curl -s -L https://github.com/stedolan/jq/releases/download/jq-1.7rc2/jq-linux-amd64 > /usr/local/bin/jq"
        sudo chmod +x /usr/local/bin/jq

        curl -fsSL https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_amd64.tar.gz | tar -xzf -
        ./krew-linux_amd64 install krew
        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"
        kubectl krew install kuttl assert

        sudo tee /etc/yum.repos.d/google-cloud-sdk.repo << EOF
[google-cloud-cli]
name=Google Cloud CLI
baseurl=https://packages.cloud.google.com/yum/repos/cloud-sdk-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo yum install -y google-cloud-cli google-cloud-cli-gke-gcloud-auth-plugin
    """

    echo "=========================[ Logging in the Kubernetes provider ]========================="
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-alpha-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            gcloud auth activate-service-account --key-file $CLIENT_SECRET_FILE
            gcloud config set project $GCP_PROJECT
        """
    }
}

void prepareSources() {
    if ("$PLATFORM_VER" == "latest") {
        USED_PLATFORM_VER = sh(script: "gcloud container get-server-config --region=$region --flatten=channels --filter='channels.channel=RAPID' --format='value(channels.defaultVersion)' | cut -d- -f1", , returnStdout: true).trim()
    } else {
        USED_PLATFORM_VER="$PLATFORM_VER"
    }
    echo "USED_PLATFORM_VER=$USED_PLATFORM_VER"

    echo "=========================[ Cloning the sources ]========================="
    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf source
        cloud/local/checkout $GIT_REPO $GIT_BRANCH
    """

    script {
        GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
        CLUSTER_NAME = sh(script: "echo jenkins-lat-pg-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
        PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$USED_PLATFORM_VER-$PG_VERSION-$OPERATOR_IMAGE-$PGO_PGBOUNCER_IMAGE-$PGO_POSTGRES_HA_IMAGE-$PGO_BACKREST_IMAGE-$PGO_PGBADGER_IMAGE-$PMM_SERVER_IMAGE_BASE-$PMM_SERVER_IMAGE_TAG-$PMM_CLIENT_IMAGE | md5sum | cut -d' ' -f1", , returnStdout: true).trim()
    }
}

void dockerBuildPush() {
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            if [[ "$OPERATOR_IMAGE" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c "
                    docker login -u '$USER' -p '$PASS'
                    export IMAGE=perconalab/percona-postgresql-operator:$GIT_BRANCH
                    e2e-tests/build
                    docker logout
                "
                sudo rm -rf build
            fi
        """
    }
}

void initTests() {
    echo "=========================[ Initializing the tests ]========================="

    echo "Populating tests into the tests array!"
    def testList = "$TEST_LIST"
    def suiteFileName = "source/e2e-tests/$TEST_SUITE"

    if (testList.length() != 0) {
        suiteFileName = 'source/e2e-tests/run-custom.csv'
        sh """
            echo -e "$testList" > $suiteFileName
            echo "Custom test suite contains following tests:"
            cat $suiteFileName
        """
    }

    def records = readCSV file: suiteFileName

    for (int i=0; i<records.size(); i++) {
        tests.add(["name": records[i][0], "cluster": "NA", "result": "skipped", "time": "0"])
    }

    echo "Marking passed tests in the tests map!"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        if ("$IGNORE_PREVIOUS_RUN" == "NO") {
            sh """
                aws s3 ls s3://percona-jenkins-artifactory/$JOB_NAME/$GIT_SHORT_COMMIT/ || :
            """

            for (int i=0; i<tests.size(); i++) {
                def testName = tests[i]["name"]
                def file="$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$PPG_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH"
                def retFileExists = sh(script: "aws s3api head-object --bucket percona-jenkins-artifactory --key $JOB_NAME/$GIT_SHORT_COMMIT/$file >/dev/null 2>&1", returnStatus: true)

                if (retFileExists == 0) {
                    tests[i]["result"] = "passed"
                }
            }
        } else {
            sh """
                aws s3 rm "s3://percona-jenkins-artifactory/$JOB_NAME/$GIT_SHORT_COMMIT/" --recursive --exclude "*" --include "*-$PARAMS_HASH" || :
            """
        }
    }

    withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE'), file(credentialsId: 'cloud-minio-secret-file', variable: 'CLOUD_MINIO_SECRET_FILE')]) {
        sh """
            cp $CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
            chmod 600 source/e2e-tests/conf/cloud-secret.yml
            cp $CLOUD_MINIO_SECRET_FILE source/e2e-tests/conf/cloud-secret-minio-gw.yml
            chmod 600 source/e2e-tests/conf/cloud-secret-minio-gw.yml
        """
    }
    stash includes: "source/**", name: "sourceFILES"
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

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    if ("$CLUSTER_WIDE" == "YES") {
        OPERATOR_NS = 'pg-operator'
    }

    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-alpha-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX

            maxRetries=15
            exitCode=1
            while [[ \$exitCode != 0 && \$maxRetries > 0 ]]; do
                gcloud container clusters create \$(echo $CLUSTER_NAME-$CLUSTER_SUFFIX | cut -c-40) \
                    --release-channel rapid \
                    --zone $region \
                    --cluster-version $USED_PLATFORM_VER \
                    --preemptible \
                    --disk-size 30 \
                    --machine-type n1-standard-4 \
                    --num-nodes=4 \
                    --min-nodes=4 \
                    --max-nodes=6 \
                    --network=jenkins-pg-vpc \
                    --subnetwork=jenkins-pg-$CLUSTER_SUFFIX \
                    --cluster-ipv4-cidr=/21 \
                    --labels delete-cluster-after-hours=6 &&\
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=\$(gcloud config get-value core/account)
                exitCode=\$?
                if [[ \$exitCode == 0 ]]; then break; fi
                (( maxRetries -- ))
                sleep 1
            done
            if [[ \$exitCode != 0 ]]; then exit \$exitCode; fi
        """
   }
}

void runTest(Integer TEST_ID) {
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]
    def clusterSuffix = tests[TEST_ID]["cluster"]

    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started on cluster $CLUSTER_NAME-$clusterSuffix !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 120, unit: 'MINUTES') {
                sh """
                    cd source

                    [[ "$OPERATOR_IMAGE" ]] && export IMAGE=$OPERATOR_IMAGE || export IMAGE=perconalab/percona-postgresql-operator:$GIT_BRANCH
                    export PG_VER=$PG_VERSION
                    export IMAGE_PGBOUNCER=$PGO_PGBOUNCER_IMAGE

                    if [[ "$PGO_POSTGRES_HA_IMAGE" ]]; then
                        export IMAGE_POSTGRESQL=${PGO_POSTGRES_HA_IMAGE}
                        export PG_VER=\$(echo \${IMAGE_POSTGRESQL} | grep -Eo 'ppg[0-9]+'| sed 's/ppg//g')
                    fi

                    export IMAGE_BACKREST=$PGO_BACKREST_IMAGE
                    export IMAGE_PGBADGER=$PGO_PGBADGER_IMAGE
                    export IMAGE_PMM_SERVER_REPO=$PMM_SERVER_IMAGE_BASE
                    export IMAGE_PMM_SERVER_TAG=$PMM_SERVER_IMAGE_TAG
                    export IMAGE_PMM=$PMM_CLIENT_IMAGE
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix
                    export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

                    kubectl kuttl test --config e2e-tests/kuttl.yaml --test "^$testName\$"
                """
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$PPG_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
            tests[TEST_ID]["result"] = "passed"
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
        finally {
            def timeStop = new Date().getTime()
            def durationSec = (timeStop - timeStart) / 1000
            tests[TEST_ID]["time"] = durationSec
            echo "The $testName test was finished!"
        }
    }
}

void pushArtifactFile(String FILE_NAME) {
    echo "Push $FILE_NAME file to S3!"

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            touch $FILE_NAME
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/$GIT_SHORT_COMMIT
            aws s3 ls \$S3_PATH/$FILE_NAME || :
            aws s3 cp --quiet $FILE_NAME \$S3_PATH/$FILE_NAME || :
        """
    }
}

TestsReport = '<testsuite name=\\"PGO\\">\n'
void makeReport() {
    echo "=========================[ Generating Test Report ]========================="
    for (int i=0; i<tests.size(); i++) {
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"]
        def testName = tests[i]["name"]

        TestsReport = TestsReport + '<testcase name=\\"' + testName + '\\" time=\\"' + testTime + '\\"><'+ testResult +'/></testcase>\n'
    }
    TestsReport = TestsReport + '</testsuite>\n'
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-alpha-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            gcloud container clusters delete --zone $region \$(echo $CLUSTER_NAME-$CLUSTER_SUFFIX | cut -c-40) || true
        """
    }
}

pipeline {
    environment {
        CLOUDSDK_CORE_DISABLE_PROMPTS = 1
        CLEAN_NAMESPACE = 1
        PPG_TAG = sh(script: "[[ \"$OPERATOR_IMAGE\" ]] && echo $OPERATOR_IMAGE | awk -F':' '{print \$2}' | grep -oE '[A-Za-z0-9\\.]+-ppg[0-9]{2}' || echo main-ppg15", , returnStdout: true).trim()
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
        choice(
            choices: 'NO\nYES',
            description: 'Ignore passed tests in previous run (run all)',
            name: 'IGNORE_PREVIOUS_RUN'
        )
        choice(
            choices: 'YES\nNO',
            description: 'Run tests with cluster wide',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: 'latest',
            description: 'Kubernetes target version',
            name: 'PLATFORM_VER')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-postgresql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-postgresql-operator',
            description: 'percona-postgresql-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '',
            description: 'PG version',
            name: 'PG_VERSION')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-postgresql-operator:main',
            name: 'OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators pgBouncer image: perconalab/percona-postgresql-operator:main-ppg15-pgbouncer',
            name: 'PGO_PGBOUNCER_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators postgres image: perconalab/percona-postgresql-operator:main-ppg15-postgres-ha',
            name: 'PGO_POSTGRES_HA_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators backrest utility image: perconalab/percona-postgresql-operator:main-ppg15-pgbackrest',
            name: 'PGO_BACKREST_IMAGE')
        string(
            defaultValue: '',
            description: 'Operators pgBadger image: perconalab/percona-postgresql-operator:main-ppg15-pgbadger',
            name: 'PGO_PGBADGER_IMAGE')
        string(
            defaultValue: '',
            description: 'PMM server image base: perconalab/pmm-server',
            name: 'PMM_SERVER_IMAGE_BASE')
        string(
            defaultValue: '',
            description: 'PMM server image tag: dev-latest',
            name: 'PMM_SERVER_IMAGE_TAG')
        string(
            defaultValue: '',
            description: 'PMM server image: perconalab/pmm-client:dev-latest',
            name: 'PMM_CLIENT_IMAGE')
    }
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        cron('0 15 * * 0')
    }
    stages {
        stage('Prepare node') {
            steps {
                prepareNode()
                prepareSources()
            }
        }
        stage('Docker Build and Push') {
            steps {
                dockerBuildPush()
            }
        }
        stage('Init tests') {
            steps {
                initTests()
            }
        }
        stage('Run Tests') {
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
            }
        }
    }
    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            sh """
                echo "$TestsReport" > TestsReport.xml
            """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml'

            script {
                if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
                    slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "[$JOB_NAME]: build $currentBuild.result, $BUILD_URL"
                }

                clusters.each { shutdownCluster(it) }
            }

            sh """
                sudo docker system prune --volumes -af
                sudo rm -rf *
            """
            deleteDir()
        }
    }
}
