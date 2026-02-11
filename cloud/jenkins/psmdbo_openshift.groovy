import groovy.transform.Field

@Field def numClusters = 8
@Field def tests = []
@Field def clusters = []
@Field def release_versions = "source/e2e-tests/release_versions"

String getParam(String paramName, String keyName = null) {
    keyName = keyName ?: paramName

    def param = sh(script: "grep -iE '^\\s*$keyName=' $release_versions | cut -d = -f 2 | tr -d \'\"\'| tail -1", returnStdout: true).trim()
    if ("$param") {
        echo "$paramName=$param (from params file)"
    } else {
        error("$keyName not found in params file $release_versions")
    }
    return param
}

void downloadKubectl() {
    sh """
        KUBECTL_VERSION="\$(curl -L -s https://api.github.com/repos/kubernetes/kubernetes/releases/latest | jq -r .tag_name)"
        for i in {1..5}; do
          if [ -f /usr/local/bin/kubectl ]; then
              break
          fi
          echo "Attempt \$i: downloading kubectl..."
          sudo curl -s -L -o /usr/local/bin/kubectl "https://dl.k8s.io/release/\${KUBECTL_VERSION}/bin/linux/amd64/kubectl"
          sudo curl -s -L -o /tmp/kubectl.sha256 "https://dl.k8s.io/release/\${KUBECTL_VERSION}/bin/linux/amd64/kubectl.sha256"
          if echo "\$(cat /tmp/kubectl.sha256) /usr/local/bin/kubectl" | sha256sum --check --status; then
            echo 'Download passed checksum'
            sudo chmod +x /usr/local/bin/kubectl
            kubectl version --client --output=yaml
            break
          else
            echo 'Checksum failed, retrying...'
            sudo rm -f /usr/local/bin/kubectl /tmp/kubectl.sha256
            sleep 5
          fi
        done
    """
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
        git clone -b $GIT_BRANCH https://github.com/percona/percona-server-mongodb-operator source
    """

    if ("$PILLAR_VERSION" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        IMAGE_OPERATOR = IMAGE_OPERATOR ?: getParam("IMAGE_OPERATOR")
        IMAGE_MONGOD = IMAGE_MONGOD ?: getParam("IMAGE_MONGOD", "IMAGE_MONGOD${PILLAR_VERSION}")
        IMAGE_BACKUP = IMAGE_BACKUP ?: getParam("IMAGE_BACKUP")
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: getParam("IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: getParam("IMAGE_PMM_SERVER")
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: getParam("IMAGE_PMM3_CLIENT")
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: getParam("IMAGE_PMM3_SERVER")
        IMAGE_LOGCOLLECTOR = IMAGE_LOGCOLLECTOR ?: getParam("IMAGE_LOGCOLLECTOR")
        if ("$PLATFORM_VER".toLowerCase() == "min" || "$PLATFORM_VER".toLowerCase() == "max") {
            PLATFORM_VER = getParam("PLATFORM_VER", "OPENSHIFT_${PLATFORM_VER}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    if ("$PLATFORM_VER" == "latest") {
        PLATFORM_VER = sh(script: "curl -s https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/$PLATFORM_VER/release.txt | sed -n 's/^\\s*Version:\\s\\+\\(\\S\\+\\)\\s*\$/\\1/p'", returnStdout: true).trim()
    }
 
    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        sudo curl -fsSL https://github.com/mikefarah/yq/releases/download/v4.44.1/yq_linux_amd64 -o /usr/local/bin/yq && sudo chmod +x /usr/local/bin/yq
        sudo curl -fsSL https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 -o /usr/local/bin/jq && sudo chmod +x /usr/local/bin/jq
    """
    downloadKubectl()
    sh """
        curl -fsSL https://get.helm.sh/helm-v3.20.0-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - oc
        curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - openshift-install
    """

    installAzureCLI()
    azureAuth()

    if ("$IMAGE_MONGOD") {
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.displayName = "#" + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER " + "$IMAGE_MONGOD".split(":")[1] + " $cw"
    }

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    CLUSTER_NAME = "psmdbo-${GIT_SHORT_COMMIT.take(6)}".toLowerCase()
    env.CLUSTER_NAME = CLUSTER_NAME
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MONGOD-$IMAGE_BACKUP-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER-$IMAGE_LOGCOLLECTOR | md5sum | cut -d' ' -f1", returnStdout: true).trim()
}

void dockerBuildPush() {
    echo "=========================[ Building and Pushing the operator Docker image ]========================="
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh '''
            if [[ "$IMAGE_OPERATOR" ]]; then
                echo "SKIP: Build is not needed, operator image was set!"
            else
                cd source
                sg docker -c '
                    docker buildx create --use
                    echo "$PASS" | docker login -u "$USER" --password-stdin
                    export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
                    e2e-tests/build
                    docker logout
                '
                sudo rm -rf build
            fi
        '''
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
    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
        if ("$IGNORE_PREVIOUS_RUN" == "NO") {
            sh """
                aws s3 ls s3://percona-jenkins-artifactory/$JOB_NAME/$GIT_SHORT_COMMIT/ || :
            """

            for (int i=0; i<tests.size(); i++) {
                def testName = tests[i]["name"]
                def file="$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH"
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

    withCredentials([file(credentialsId: 'cloud-secret-file-psmdb', variable: 'CLOUD_SECRET_FILE')]) {
        sh '''
            cp $CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
        '''
    }
}

void clusterRunner(String cluster) {
    def clusterCreated = 0

    try {
        for (int i=0; i<tests.size(); i++) {
            if (tests[i]["result"] == "skipped") {
                tests[i]["result"] = "failure"
                tests[i]["cluster"] = cluster
                if (clusterCreated == 0) {
                    clusterCreated = 1
                    createCluster(cluster)
                }
                runTest(i)
            }
        }
    } finally {
        if (clusterCreated >= 1) {
            try {
                shutdownCluster(cluster)
                clusters.remove(cluster)
            } catch (Exception e) {
                echo "Warning: Error shutting down cluster $cluster: ${e.getMessage()}"
            }
        }
    }
}

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE'), usernamePassword(credentialsId: 'docker.io', passwordVariable: 'DOCKER_READ_PASS', usernameVariable: 'DOCKER_READ_USER')]) {
        withEnv(["CLUSTER_SUFFIX=${CLUSTER_SUFFIX}", "CLUSTER_NAME=${CLUSTER_NAME}"]) {
            sh """
                mkdir -p openshift/\$CLUSTER_SUFFIX
                timestamp="\$(date +%s)"
tee openshift/\$CLUSTER_SUFFIX/install-config.yaml << EOF
additionalTrustBundlePolicy: Proxyonly
credentialsMode: Mint
apiVersion: v1
baseDomain: cd.percona.com
compute:
- architecture: amd64
  hyperthreading: Enabled
  name: worker
  platform:
    aws:
      type: m5.2xlarge
  replicas: 3
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform: {}
  replicas: 1
metadata:
  creationTimestamp: null
  name: \$CLUSTER_NAME-\$CLUSTER_SUFFIX
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: OVNKubernetes
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: ${AWS_REGION}
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: 6
      team: cloud
      product: psmdb-operator
      creation-time: \$timestamp

publish: External
EOF
                cat $OPENSHIFT_CONF_FILE >> openshift/\$CLUSTER_SUFFIX/install-config.yaml
            """
            sshagent(['aws-openshift-41-key']) {
                sh '''
                    /usr/local/bin/openshift-install create cluster --dir=openshift/$CLUSTER_SUFFIX --log-level=debug || {
                        /usr/local/bin/openshift-install gather bootstrap --dir=openshift/$CLUSTER_SUFFIX || true
                        exit 1
                    }
                    export KUBECONFIG=openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                    TMP=$(mktemp)
                    oc get secret/pull-secret -n openshift-config --template='{{index .data ".dockerconfigjson" | base64decode}}' > $TMP
                    oc registry login --registry='docker.io' --auth-basic="$DOCKER_READ_USER:$DOCKER_READ_PASS" --to=$TMP
                    oc set data secret/pull-secret -n openshift-config --from-file=.dockerconfigjson=$TMP
                    rm -rf $TMP
                '''
            }
        }
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

                    [[ "$DEBUG_TESTS" == "YES" ]] && export DEBUG_TESTS=1
                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=psmdb-operator
                    [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
                    export IMAGE_MONGOD=$IMAGE_MONGOD
                    export IMAGE_BACKUP=$IMAGE_BACKUP
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                    export IMAGE_PMM3_CLIENT=$IMAGE_PMM3_CLIENT
                    export IMAGE_PMM3_SERVER=$IMAGE_PMM3_SERVER
                    export IMAGE_LOGCOLLECTOR=$IMAGE_LOGCOLLECTOR
                    export KUBECONFIG=$WORKSPACE/openshift/$clusterSuffix/auth/kubeconfig

                    oc whoami

                    e2e-tests/$testName/run
                """
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
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

    withCredentials([aws(credentialsId: 'AMI/OVF', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
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
    testsReport = "<testsuite name=\"$JOB_NAME\">\n"
    for (int i = 0; i < tests.size(); i ++) {
        testsReport += '<testcase name="' + tests[i]["name"] + '" time="' + tests[i]["time"] + '"><'+ tests[i]["result"] +'/></testcase>\n'
    }
    testsReport += '</testsuite>\n'

    echo "=========================[ Generating Parameters Report ]========================="
    pipelineParameters = """
testsuite name=$JOB_NAME
IMAGE_OPERATOR=${IMAGE_OPERATOR ?: 'e2e_defaults'}
IMAGE_MONGOD=${IMAGE_MONGOD ?: 'e2e_defaults'}
IMAGE_BACKUP=${IMAGE_BACKUP ?: 'e2e_defaults'}
IMAGE_PMM_CLIENT=${IMAGE_PMM_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM_SERVER=${IMAGE_PMM_SERVER ?: 'e2e_defaults'}
IMAGE_PMM3_CLIENT=${IMAGE_PMM3_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM3_SERVER=${IMAGE_PMM3_SERVER ?: 'e2e_defaults'}
IMAGE_LOGCOLLECTOR=${IMAGE_LOGCOLLECTOR ?: 'e2e_defaults'}
PLATFORM_VER=$PLATFORM_VER"""

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([aws(credentialsId: 'openshift-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID'), file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
        sshagent(['aws-openshift-41-key']) {
            sh """
                export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                if [ -s "\$KUBECONFIG" ] && kubectl get --raw='/healthz' --request-timeout=5s >/dev/null 2>&1; then
                    for namespace in \$(kubectl get namespaces --request-timeout=5s --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                        kubectl delete deployments --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                        kubectl delete sts --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                        kubectl delete replicasets --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                        kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                        kubectl delete services --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                        kubectl delete pods --all -n \$namespace --force --grace-period=0 --request-timeout=10s || true
                    done
                else
                    echo "Skipping namespace cleanup: Kubernetes API is not reachable for $CLUSTER_NAME-$CLUSTER_SUFFIX"
                fi

                /usr/local/bin/openshift-install destroy cluster --dir=openshift/$CLUSTER_SUFFIX || true
            """
        }
    }
}

void azureAuth() {
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
        sh '''
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID"  --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
        '''
    }
}

void installAzureCLI() {
    sh """
        if ! command -v az &>/dev/null; then
            if [ "\$JENKINS_AGENT" = "AWS" ]; then
                curl -s -L https://azurecliprod.blob.core.windows.net/install.py -o install.py
                printf "/usr/azure-cli\\n/usr/bin" | sudo python3 install.py
                sudo /usr/azure-cli/bin/python -m pip install "urllib3<2.0.0" > /dev/null
            else
                echo "Installing Azure CLI for Hetzner instances..."
                sudo rpm --import https://packages.microsoft.com/keys/microsoft.asc
                cat <<EOF | sudo tee /etc/yum.repos.d/azure-cli.repo
[azure-cli]
name=Azure CLI
baseurl=https://packages.microsoft.com/yumrepos/azure-cli
enabled=1
gpgcheck=1
gpgkey=https://packages.microsoft.com/keys/microsoft.asc
EOF
                sudo dnf install azure-cli -y
            fi
        fi
    """
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        DB_TAG = sh(script: "[[ \"$IMAGE_MONGOD\" ]] && echo $IMAGE_MONGOD | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv', 'run-backups.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: 'NO\nYES', description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: 'none\n80\n70\n60', description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mongodb-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'OpenShift kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: 'YES\nNO', description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongod8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-backup')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'ex: perconalab/fluentbit:main-logcollector')
        string(name: 'AWS_REGION', defaultValue: 'eu-west-3', description: 'AWS region to use for openshift cluster')
        choice(name: 'DEBUG_TESTS', choices: 'NO\nYES', description: 'Run tests with debug')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner','AWS'], description: 'Cloud infra for build')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('psmdb-operator-latest-scheduler');
    }
    stages {
        stage('Prepare Node') {
            steps {
                prepareNode()
            }
        }
        stage('Docker Build and Push') {
            steps {
                dockerBuildPush()
            }
        }
        stage('Init Tests') {
            steps {
                initTests()
            }
        }
        stage('Run Tests') {
            steps {
                script {
                    def parallelStages = [:]
                    for (int i = 1; i <= numClusters; i++) {
                        def clusterName = "c${i}"
                        parallelStages[clusterName] = {
                            stage(clusterName) {
                                clusterRunner(clusterName)
                            }
                        }
                    }
                    parallel parallelStages
                }
            }
        }
    }
    post {
        always {
            echo "CLUSTER ASSIGNMENTS\n" + tests.toString().replace("], ","]\n").replace("]]","]").replaceFirst("\\[","")
            makeReport()
            junit testResults: '*.xml', healthScaleFactor: 1.0
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
