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

void prepareNode() {
    echo "=========================[ Cloning the sources ]========================="
    checkout(scm)
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
        IMAGE_SEARCH = IMAGE_SEARCH ?: getParam("IMAGE_SEARCH")
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
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.dependencies.install()
    libraries.dependencies.installGoogleCLI()
    libraries.dependencies.installAzureCLI()
    libraries.dependencies.installUv()
    libraries.dependencies.syncPythonDeps()
    libraries.azure.auth()

    sh """
        curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - oc
        curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - openshift-install
    """

    if ("$IMAGE_MONGOD") {
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.displayName = "#" + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER " + "$IMAGE_MONGOD".split(":")[1] + " $cw"
    }

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    CLUSTER_NAME = "psmdbo-${GIT_SHORT_COMMIT.take(6)}".toLowerCase()
    env.CLUSTER_NAME = CLUSTER_NAME
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MONGOD-$IMAGE_BACKUP-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER-$IMAGE_LOGCOLLECTOR-$IMAGE_SEARCH | md5sum | cut -d' ' -f1", returnStdout: true).trim()
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
                    DOCKER_DEFAULT_PLATFORM=linux/amd64,linux/arm64 e2e-tests/build
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

void enableVolumeSnapshotResources(String CLUSTER_SUFFIX) {
    sh """
        export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig

        for i in \$(seq 1 60); do
            if kubectl get crd csisnapshotcontrollers.operator.openshift.io >/dev/null 2>&1; then
                break
            fi
            sleep 10
        done

        cat <<EOF | kubectl apply -f -
apiVersion: operator.openshift.io/v1
kind: CSISnapshotController
metadata:
  name: cluster
spec:
  managementState: Managed
EOF

        kubectl get csisnapshotcontroller cluster -o yaml
    """
}

void verifyVolumeSnapshotResources(String CLUSTER_SUFFIX) {
    sh """
        export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig

        wait_for_deployment() {
            local deployment_name="\$1"
            local namespace="\$2"

            for i in \$(seq 1 60); do
                if kubectl get deployment "\$deployment_name" -n "\$namespace" >/dev/null 2>&1; then
                    kubectl wait --for=condition=Available deployment/"\$deployment_name" -n "\$namespace" --timeout=10m
                    return 0
                fi
                sleep 10
            done

            kubectl get deployment -n "\$namespace" || true
            return 1
        }

        wait_for_deployment csi-snapshot-controller-operator openshift-cluster-storage-operator
        wait_for_deployment csi-snapshot-controller openshift-cluster-storage-operator

        kubectl get crd volumesnapshots.snapshot.storage.k8s.io volumesnapshotcontents.snapshot.storage.k8s.io volumesnapshotclasses.snapshot.storage.k8s.io
        kubectl api-resources --api-group=snapshot.storage.k8s.io
    """
}

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    timeout(time: 60, unit: 'MINUTES') {
        withCredentials([aws(credentialsId: 'openshift-cicd', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'), file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE'), usernamePassword(credentialsId: 'docker.io', passwordVariable: 'DOCKER_READ_PASS', usernameVariable: 'DOCKER_READ_USER')]) {
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
      type: m5.xlarge
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
      delete-cluster-after-hours: 7
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

    enableVolumeSnapshotResources(CLUSTER_SUFFIX)
    verifyVolumeSnapshotResources(CLUSTER_SUFFIX)
}

void runTest(Integer TEST_ID) {
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]
    def clusterSuffix = tests[TEST_ID]["cluster"]

    waitUntil {
        def timeStart = new Date().getTime()
        def testsLib = load('cloud/common/vars/tests.groovy')
        try {
            echo "The $testName test was started on cluster $CLUSTER_NAME-$clusterSuffix !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 90, unit: 'MINUTES') {
                def testVars = testsLib.buildPsmdbTestVariables(
                    cluster_name: CLUSTER_NAME,
                    kubeconfig: "$WORKSPACE/openshift/${clusterSuffix}/auth/kubeconfig",
                    debug_tests: DEBUG_TESTS,
                    cluster_wide: CLUSTER_WIDE,
                    default_operator_image: "perconalab/percona-server-mongodb-operator:${GIT_BRANCH}",
                    images: [
                        IMAGE_OPERATOR    : IMAGE_OPERATOR,
                        IMAGE_MONGOD      : IMAGE_MONGOD,
                        IMAGE_BACKUP      : IMAGE_BACKUP,
                        IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                        IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                        IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
                        IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER,
                        IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR,
                        IMAGE_SEARCH      : IMAGE_SEARCH
                    ]
                )
                def exports = testsLib.getExportedVariablesForTests(testVars, clusterSuffix)
                def testCmd = testsLib.defineTestCommand(testVars, testName)
                sh """
                    cd source

                    ${exports}

                    oc whoami

                    mkdir -p e2e-tests/logs e2e-tests/reports
                    bash -o pipefail <<BASH
                    {
                        ${testCmd}
                    } 2>&1 | tee e2e-tests/logs/${testName}.log
BASH
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
            try {
                testsLib.pushLogFile(testName, [gitShortCommit: GIT_SHORT_COMMIT])
            } catch (logErr) {
                echo "Warning: failed to push log for $testName: ${logErr}"
            }
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
    echo "=========================[ Generating Parameters Report ]========================="
    def pipelineParameters = """
testsuite name=$JOB_NAME
IMAGE_OPERATOR=${IMAGE_OPERATOR ?: 'e2e_defaults'}
IMAGE_MONGOD=${IMAGE_MONGOD ?: 'e2e_defaults'}
IMAGE_BACKUP=${IMAGE_BACKUP ?: 'e2e_defaults'}
IMAGE_PMM_CLIENT=${IMAGE_PMM_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM_SERVER=${IMAGE_PMM_SERVER ?: 'e2e_defaults'}
IMAGE_PMM3_CLIENT=${IMAGE_PMM3_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM3_SERVER=${IMAGE_PMM3_SERVER ?: 'e2e_defaults'}
IMAGE_LOGCOLLECTOR=${IMAGE_LOGCOLLECTOR ?: 'e2e_defaults'}
IMAGE_SEARCH=${IMAGE_SEARCH ?: 'e2e_defaults'}
PLATFORM_VER=$PLATFORM_VER"""

    def testsLib = load('cloud/common/vars/tests.groovy')
    testsLib.writePipelineParameters(pipelineParameters)
    testsLib.publishPytestReports(
        tests         : tests,
        gitShortCommit: GIT_SHORT_COMMIT,
        gitBranch     : GIT_BRANCH,
        title         : "PSMDB e2e tests - ${GIT_BRANCH} (${GIT_SHORT_COMMIT})"
    )
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    timeout(time: 30, unit: 'MINUTES') {
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
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        DB_TAG = sh(script: "[[ \"$IMAGE_MONGOD\" ]] && echo $IMAGE_MONGOD | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv', 'run-backups.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '80', '83', '70', '60'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mongodb-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'OpenShift kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongod8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-backup')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'ex: perconalab/fluentbit:main-logcollector')
        string(name: 'IMAGE_SEARCH', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongot')
        string(name: 'AWS_REGION', defaultValue: 'eu-west-3', description: 'AWS region to use for openshift cluster')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Run tests with debug')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 7, unit: 'HOURS')
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

            script {
                try {
                    def sendJobSlack = load "cloud/common/sendJobSlackNotification.groovy"
                    sendJobSlack.call(
                        tests: tests,
                        gitBranch: GIT_BRANCH,
                        platformVer: PLATFORM_VER,
                        clusterWide: CLUSTER_WIDE,
                        image: IMAGE_MONGOD,
                        operatorImage: IMAGE_OPERATOR
                    )

                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
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
