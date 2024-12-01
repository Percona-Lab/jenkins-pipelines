location='westeurope'
tests=[]
clusters=[]
release_versions="source/e2e-tests/release_versions"

void verifyParams() {
    if ("$RELEASE_RUN" == "YES") {
        echo "=========================[ RELEASE RUN ]========================="
        if (!"$PILLAR_VERSION" && !"$IMAGE_PXC") {
            error("Either PILLAR_VERSION or IMAGE_PXC should be provided for release run!")
        }
    }
    USED_PLATFORM_VER="$PLATFORM_VER"
}

String getParam(String PARAM_NAME) {
    def param = "${params[PARAM_NAME]}"

    if ("$param" && "$param" != "null" && param != "") {
        echo "$PARAM_NAME=$param (from job parameters)"
    } else {
        param = sh(script: "grep -iE '^\\s*$PARAM_NAME=' $release_versions | cut -d = -f 2 | tr -d \'\"\'| tail -1", , returnStdout: true).trim()
        if ("$param") {
            echo "$PARAM_NAME=$param (from params file)"
        } else {
            error("$PARAM_NAME not found in params file $release_versions")
        }
    }
    return param
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
        echo "=========================[ Getting parameters for release test ]========================="
        IMAGE_OPERATOR = getParam("IMAGE_OPERATOR")
        IMAGE_PXC = getParam("IMAGE_PXC${PILLAR_VERSION}")
        IMAGE_PROXY = getParam("IMAGE_PROXY")
        IMAGE_HAPROXY = getParam("IMAGE_HAPROXY")
        IMAGE_BACKUP = getParam("IMAGE_BACKUP${PILLAR_VERSION}")
        IMAGE_LOGCOLLECTOR = getParam("IMAGE_LOGCOLLECTOR")
        IMAGE_PMM_CLIENT = getParam("IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = getParam("IMAGE_PMM_SERVER")
        if ("$PLATFORM_VER" == "min".toLowerCase() || "$PLATFORM_VER" == "max".toLowerCase()) {
            PLATFORM_VER = getParam("AKS_${PLATFORM_VER}")
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

        if ! command -v az &>/dev/null; then
            curl -s -L https://azurecliprod.blob.core.windows.net/install.py -o install.py
            printf "/usr/azure-cli\\n/usr/bin" | sudo python3 install.py
            sudo /usr/azure-cli/bin/python -m pip install "urllib3<2.0.0" > /dev/null
        fi
    """

    echo "=========================[ Logging in the Kubernetes provider ]========================="
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
        sh """
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID"  --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
        """
    }

    if ("$PLATFORM_VER" == "latest") {
        USED_PLATFORM_VER = sh(script: "az aks get-versions --location $location --output json | jq -r '.values | max_by(.patchVersions) | .patchVersions | keys[]' | sort --version-sort | tail -1", , returnStdout: true).trim()
    }

    if ("$IMAGE_PXC") {
        release = ("$RELEASE_RUN" == "YES") ? "RELEASE-" : ""
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.description = "$release$GIT_BRANCH-$PLATFORM_VER-$cw-" + "$IMAGE_PXC".split(":")[1]
    }

    script {
        GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', , returnStdout: true).trim()
        CLUSTER_NAME = sh(script: "echo jenkins-ver-pxc-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
        PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$USED_PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_PXC-$IMAGE_PROXY-$IMAGE_HAPROXY-$IMAGE_BACKUP-$IMAGE_LOGCOLLECTOR-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER | md5sum | cut -d' ' -f1", , returnStdout: true).trim()
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
                    export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
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
                def file="$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$PXC_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH"
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

    sh """
        export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
        az aks create -n $CLUSTER_NAME-$CLUSTER_SUFFIX \
            -g percona-operators \
            --subscription eng-cloud-dev \
            --load-balancer-sku basic \
            --enable-managed-identity \
            --node-count 3 \
            --node-vm-size Standard_B4ms \
            --min-count 3 \
            --max-count 3 \
            --node-osdisk-size 30 \
            --network-plugin kubenet \
            --generate-ssh-keys \
            --enable-cluster-autoscaler \
            --outbound-type loadbalancer \
            --kubernetes-version $USED_PLATFORM_VER \
            -l $location
        az aks get-credentials --subscription eng-cloud-dev --resource-group percona-operators --name $CLUSTER_NAME-$CLUSTER_SUFFIX --overwrite-existing
    """
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
                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=pxc-operator
                    export IMAGE=$IMAGE_OPERATOR
                    export IMAGE_PXC=$IMAGE_PXC
                    export IMAGE_PROXY=$IMAGE_PROXY
                    export IMAGE_HAPROXY=$IMAGE_HAPROXY
                    export IMAGE_BACKUP=$IMAGE_BACKUP
                    export IMAGE_LOGCOLLECTOR=$IMAGE_LOGCOLLECTOR
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix

                    e2e-tests/$testName/run
                """
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$USED_PLATFORM_VER-$PXC_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
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

void makeReport() {
    echo "=========================[ Generating Test Report ]========================="
    testsReport = '<testsuite name="PXC-AKS-version">\n'
    for (int i = 0; i < tests.size(); i ++) {
        testsReport += '<testcase name="' + tests[i]["name"] + '" time="' + tests[i]["time"] + '"><'+ tests[i]["result"] +'/></testcase>\n'
    }
    testsReport += '</testsuite>\n'

    echo "=========================[ Generating Parameters Report ]========================="
    pipelineParameters = """
        testsuite name=PXC-AKS-version
        IMAGE_OPERATOR=$IMAGE_OPERATOR
        IMAGE_PXC=$IMAGE_PXC
        IMAGE_PROXY=$IMAGE_PROXY
        IMAGE_HAPROXY=$IMAGE_HAPROXY
        IMAGE_BACKUP=$IMAGE_BACKUP
        IMAGE_LOGCOLLECTOR=$IMAGE_LOGCOLLECTOR
        IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
        IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
        USED_PLATFORM_VER=$USED_PLATFORM_VER
    """

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
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

            az aks delete --name $CLUSTER_NAME-$CLUSTER_SUFFIX --resource-group percona-operators --subscription eng-cloud-dev --yes || true
        """
    }
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        PXC_TAG = sh(script: "[[ \"$IMAGE_PXC\" ]] && echo $IMAGE_PXC | awk -F':' '{print \$2}' || echo main", , returnStdout: true).trim()
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
        string(
            defaultValue: '80',
            description: 'For RELEASE_RUN only. Major version like 80, 57, etc',
            name: 'PILLAR_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'AKS kubernetes version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main',
            name: 'IMAGE_OPERATOR')
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
            description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector',
            name: 'IMAGE_LOGCOLLECTOR')
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
        copyArtifactPermission('pxc-operator-latest-scheduler');
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
                stage('cluster7') {
                    steps {
                        clusterRunner('cluster7')
                    }
                }
                stage('cluster8') {
                    steps {
                        clusterRunner('cluster8')
                    }
                }
            }
        }
    }
    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
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
            """
            deleteDir()
        }
    }
}
