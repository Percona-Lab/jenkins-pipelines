region='us-central1-a'
tests=[]
clusters=[]
release_versions="source/e2e-tests/release_versions"

void verifyParams() {
    if ("$RELEASE_RUN" == "YES") {
        echo "=========================[ RELEASE RUN ]========================="
        if (!"$PILLAR_VERSION" && !"$IMAGE_MONGOD") {
            error("Either PILLAR_VERSION or IMAGE_MONGOD should be provided for release run!")
        }

        GKE_RELEASE_CHANNEL = "stable"
        echo "Forcing GKE_RELEASE_CHANNEL=stable, because it's a release run!"
    }
}

String getParam(String PARAM_NAME) {
    echo "=========================[ Getting parameters for release test ]========================="
    def param = "${params[PARAM_NAME]}"

    if ("$param" && "$param" != "null" && param != "") {
        echo "$PARAM_NAME=$param (from job parameters)"
        return param
    } else {
        param = sh(script: "cat $release_versions | grep -i $PARAM_NAME= | cut -d = -f 2 | tr -d \'\"\'", , returnStdout: true).trim()
        if ("$param") {
            echo "$PARAM_NAME=$param (from params file)"
            return param
        } else {
            error("$PARAM_NAME not found in params file $release_versions")
        }
    }
}

void prepareNode() {
    echo "=========================[ Cloning the sources ]========================="
    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf source
        cloud/local/checkout $GIT_REPO $GIT_BRANCH
    """

    if ("$RELEASE_RUN" == "YES") {
        IMAGE_OPERATOR = getParam("IMAGE_OPERATOR")
        IMAGE_MONGOD = getParam("IMAGE_MONGOD${PILLAR_VERSION}")
        IMAGE_BACKUP = getParam("IMAGE_BACKUP")
        IMAGE_PMM_CLIENT = getParam("IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = getParam("IMAGE_PMM_SERVER")
        if ("$PLATFORM_VER" == "min".toLowerCase() || "$PLATFORM_VER" == "max".toLowerCase()) {
            PLATFORM_VER = getParam("GKE_${PLATFORM_VER}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        kubectl version --client --output=yaml

        curl -fsSL https://get.helm.sh/helm-v3.12.3-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        sudo curl -fsSL https://github.com/mikefarah/yq/releases/download/v4.44.1/yq_linux_amd64 -o /usr/local/bin/yq && sudo chmod +x /usr/local/bin/yq
        sudo curl -fsSL https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 -o /usr/local/bin/jq && sudo chmod +x /usr/local/bin/jq

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
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            gcloud auth activate-service-account --key-file $CLIENT_SECRET_FILE
            gcloud config set project $GCP_PROJECT
        """
    }

    if ("$PLATFORM_VER" == "latest") {
        USED_PLATFORM_VER = sh(script: "gcloud container get-server-config --region=$region --flatten=channels --filter='channels.channel=RAPID' --format='value(channels.defaultVersion)' | cut -d- -f1", , returnStdout: true).trim()
    } else {
        USED_PLATFORM_VER="$PLATFORM_VER"
    }
    echo "USED_PLATFORM_VER=$USED_PLATFORM_VER"

    if ("$ARCH" == "amd64") {
        MACHINE_TYPE="n1-standard-4"
    } else if ("$ARCH" == "arm64") {
        MACHINE_TYPE="t2a-standard-4"
    } else {
        error("Unknown architecture $ARCH")
    }

    if ("$IMAGE_MONGOD") {
        release = ("$RELEASE_RUN" == "YES") ? "RELEASE-" : ""
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.description = "${release}$GIT_BRANCH-$ARCH-$PLATFORM_VER-$GKE_RELEASE_CHANNEL-$cw-" + "$IMAGE_MONGOD".split(":")[1]
    }

    script {
        GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
        CLUSTER_NAME = sh(script: "echo jenkins-lat-psmdb-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
        PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$GKE_RELEASE_CHANNEL-$ARCH-$USED_PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MONGOD-$IMAGE_BACKUP-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER | md5sum | cut -d' ' -f1", , returnStdout: true).trim()
    }
}

void dockerBuildPush() {
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            if [[ "$IMAGE_OPERATOR" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c "
                    docker buildx create --use
                    docker login -u '$USER' -p '$PASS'
                    export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
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
                def file="$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$MDB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH"
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

    withCredentials([file(credentialsId: 'cloud-secret-file', variable: 'CLOUD_SECRET_FILE')]) {
        sh """
            cp $CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
        """
    }
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

    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            maxRetries=15
            exitCode=1

            while [[ \$exitCode != 0 && \$maxRetries > 0 ]]; do
                gcloud container clusters create $CLUSTER_NAME-$CLUSTER_SUFFIX \
                    --release-channel $GKE_RELEASE_CHANNEL \
                    --zone $region \
                    --cluster-version $USED_PLATFORM_VER \
                    --preemptible \
                    --disk-size 30 \
                    --machine-type $MACHINE_TYPE \
                    --num-nodes=4 \
                    --min-nodes=4 \
                    --max-nodes=6 \
                    --network=jenkins-vpc \
                    --subnetwork=jenkins-$CLUSTER_SUFFIX \
                    --cluster-ipv4-cidr=/21 \
                    --labels delete-cluster-after-hours=6 \
                    --enable-ip-alias \
                    --workload-pool=cloud-dev-112233.svc.id.goog &&\
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=\$(gcloud config get-value core/account)
                exitCode=\$?
                if [[ \$exitCode == 0 ]]; then break; fi
                (( maxRetries -- ))
                sleep 1
            done
            if [[ \$exitCode != 0 ]]; then exit \$exitCode; fi

            CURRENT_TIME=\$(date --rfc-3339=seconds)
            FUTURE_TIME=\$(date -d '6 hours' --rfc-3339=seconds)

            gcloud container clusters update $CLUSTER_NAME-$CLUSTER_SUFFIX \
                --zone $region \
                --add-maintenance-exclusion-start "\$CURRENT_TIME" \
                --add-maintenance-exclusion-end "\$FUTURE_TIME"

            kubectl get nodes -o custom-columns="NAME:.metadata.name,TAINTS:.spec.taints,AGE:.metadata.creationTimestamp"
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

            timeout(time: 90, unit: 'MINUTES') {
                sh """
                    cd source

                    export DEBUG_TESTS=1
                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=psmdb-operator
                    [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
                    export IMAGE_MONGOD=$IMAGE_MONGOD
                    export IMAGE_BACKUP=$IMAGE_BACKUP
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix

                    e2e-tests/$testName/run
                """
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$MDB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
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

TestsReport = '<testsuite name=\\"PSMDB-GKE-latest\\">\n'
void makeReport() {
    echo "=========================[ Generating Test Report ]========================="
    for (int i=0; i<tests.size(); i++) {
        def testResult = tests[i]["result"]
        def testTime = tests[i]["time"]
        def testName = tests[i]["name"]

        TestsReport = TestsReport + '<testcase name=\\"' + testName + '\\" time=\\"' + testTime + '\\"><'+ testResult +'/></testcase>\n'
    }
    TestsReport = TestsReport + '</testsuite>\n'

    echo "=========================[ Generating Images Report ]========================="
    TestsImages = "testsuite name='PSMDB-GKE-latest'\n" +\
                    "IMAGE_OPERATOR=$IMAGE_OPERATOR\n" +\
                    "IMAGE_MONGOD=$IMAGE_MONGOD\n" +\
                    "IMAGE_BACKUP=$IMAGE_BACKUP\n" +\
                    "IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT\n" +\
                    "IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER\n" +\
                    "USED_PLATFORM_VER=$USED_PLATFORM_VER"
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
                kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
                kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
                kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
                kubectl delete services --all -n \$namespace --force --grace-period=0 || true
                kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
            done
            kubectl get svc --all-namespaces || true
            gcloud container clusters delete --zone $region $CLUSTER_NAME-$CLUSTER_SUFFIX --quiet || true
        """
    }
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        MDB_TAG = sh(script: "[[ \"$IMAGE_MONGOD\" ]] && echo $IMAGE_MONGOD | awk -F':' '{print \$2}' || echo main", , returnStdout: true).trim()
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
            choices: 'NO\nYES',
            description: 'Release run?',
            name: 'RELEASE_RUN'
        )
        choice(
            choices: 'amd64\narm64',
            description: 'Architecture',
            name: 'ARCH'
        )
        string(
            defaultValue: '70',
            description: 'For RELEASE_RUN only. Major version like 70,60, etc',
            name: 'PILLAR_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'GKE kubernetes version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'rapid\nstable\nregular\nNone',
            description: 'GKE release channel',
            name: 'GKE_RELEASE_CHANNEL')
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mongodb-operator:main',
            name: 'IMAGE_OPERATOR')
        string(
            defaultValue: '',
            description: 'MONGOD image: perconalab/percona-server-mongodb-operator:main-mongod5.0',
            name: 'IMAGE_MONGOD')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:main-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM client image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM_CLIENT')
        string(
            defaultValue: '',
            description: 'PMM server image: perconalab/pmm-server:dev-latest',
            name: 'IMAGE_PMM_SERVER')
    }
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('psmdb-operator-latest-scheduler');
    }
    stages {
        stage('Prepare node') {
            steps {
                verifyParams()
                prepareNode()
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
                    steps {
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    steps {
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    steps {
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    steps {
                        clusterRunner('cluster4')
                    }
                }
                stage('cluster5') {
                    steps {
                        clusterRunner('cluster5')
                    }
                }
                stage('cluster6') {
                    steps {
                        clusterRunner('cluster6')
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
                echo "$TestsImages" > TestsImages.txt
            """
            step([$class: 'JUnitResultArchiver', testResults: '*.xml', healthScaleFactor: 1.0])
            archiveArtifacts '*.xml,*.txt'

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
