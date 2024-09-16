location='eastus'
tests=[]
clusters=[]

void prepareNode() {
    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        #sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/v1.31.0/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        kubectl version --client --output=yaml

        curl -fsSL https://get.helm.sh/helm-v3.12.3-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        sudo curl -fsSL https://github.com/mikefarah/yq/releases/download/v4.44.1/yq_linux_amd64 -o /usr/local/bin/yq && sudo chmod +x /usr/local/bin/yq
        sudo curl -fsSL https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 -o /usr/local/bin/jq && sudo chmod +x /usr/local/bin/jq

        curl -fsSL https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_amd64.tar.gz | tar -xzf -
        ./krew-linux_amd64 install krew
        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

        kubectl krew install assert

        # v0.17.0 kuttl version
        kubectl krew install --manifest-url https://raw.githubusercontent.com/kubernetes-sigs/krew-index/336ef83542fd2f783bfa2c075b24599e834dcc77/plugins/kuttl.yaml
        echo \$(kubectl kuttl --version) is installed

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

    if ("$PGO_POSTGRES_IMAGE") {
        currentBuild.description = "$GIT_BRANCH-$PLATFORM_VER-CW_$CLUSTER_WIDE-" + "$PGO_POSTGRES_IMAGE".split(":")[1]
    }
}

void prepareSources() {
    if ("$PLATFORM_VER" == "latest") {
        USED_PLATFORM_VER = sh(script: "az aks get-versions --location $location --output json | jq -r '.values | max_by(.patchVersions) | .patchVersions | keys[]' | sort --version-sort | tail -1", , returnStdout: true).trim()
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
        CLUSTER_NAME = sh(script: "echo jenkins-ver-pgv2-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
        PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$USED_PLATFORM_VER-$PG_VERSION-$OPERATOR_IMAGE-$PGO_PGBOUNCER_IMAGE-$PGO_POSTGRES_IMAGE-$PGO_BACKREST_IMAGE-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER | md5sum | cut -d' ' -f1", , returnStdout: true).trim()
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
                    docker buildx create --use
                    docker login -u '$USER' -p '$PASS'
                    export IMAGE=perconalab/percona-postgresql-operator:$GIT_BRANCH
                    make build-docker-image
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

            timeout(time: 120, unit: 'MINUTES') {
                sh """
                    cd source

                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=pg-operator
                    [[ "$OPERATOR_IMAGE" ]] && export IMAGE=$OPERATOR_IMAGE || export IMAGE=perconalab/percona-postgresql-operator:$GIT_BRANCH
                    export PG_VER=$PG_VERSION
                    export IMAGE_PGBOUNCER=$PGO_PGBOUNCER_IMAGE
                    if [[ "$PGO_POSTGRES_IMAGE" ]]; then
                        export IMAGE_POSTGRESQL=$PGO_POSTGRES_IMAGE
                        export PG_VER=\$(echo \$IMAGE_POSTGRESQL | grep -Eo 'ppg[0-9]+'| sed 's/ppg//g')
                    fi
                    export IMAGE_BACKREST=$PGO_BACKREST_IMAGE
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
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

TestsReport = '<testsuite name=\\"PG-AKS-version\\">\n'
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
        CLOUDSDK_CORE_DISABLE_PROMPTS = 1
        CLEAN_NAMESPACE = 1
        PPG_TAG = sh(script: "[[ \$PGO_POSTGRES_IMAGE ]] && echo \$PGO_POSTGRES_IMAGE | awk -F':' '{print \$2}' | grep -oE '[A-Za-z0-9\\.]+-ppg[0-9]{2}' || echo main-ppg16", , returnStdout: true).trim()
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
            name: 'IGNORE_PREVIOUS_RUN')
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: 'latest',
            description: 'AKS kubernetes version',
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
            description: 'Postgres image: perconalab/percona-postgresql-operator:main-ppg16-postgres',
            name: 'PGO_POSTGRES_IMAGE')
        string(
            defaultValue: '',
            description: 'pgBouncer image: perconalab/percona-postgresql-operator:main-ppg16-pgbouncer',
            name: 'PGO_PGBOUNCER_IMAGE')
        string(
            defaultValue: '',
            description: 'pgBackRest utility image: perconalab/percona-postgresql-operator:main-ppg16-pgbackrest',
            name: 'PGO_BACKREST_IMAGE')
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
        copyArtifactPermission('pg-operator-latest-scheduler');
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
                // if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
                //     slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "[$JOB_NAME]: build $currentBuild.result, $BUILD_URL"
                // }

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
