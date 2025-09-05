tests=[]
clusters=[]
release_versions="source/e2e-tests/release_versions"

String getParam(String paramName, String keyName = null) {
    keyName = keyName ?: paramName

    param = sh(script: "grep -iE '^\\s*$keyName=' $release_versions | cut -d = -f 2 | tr -d \'\"\'| tail -1", returnStdout: true).trim()
    if ("$param") {
        echo "$paramName=$param (from params file)"
    } else {
        error("$keyName not found in params file $release_versions")
    }
    return param
}

void initParams() {
    if ("$PILLAR_VERSION" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="

        IMAGE_OPERATOR = IMAGE_OPERATOR ?: getParam("IMAGE_OPERATOR")
        IMAGE_POSTGRESQL = IMAGE_POSTGRESQL ?: getParam("IMAGE_POSTGRESQL", "IMAGE_POSTGRESQL${PILLAR_VERSION}")
        IMAGE_PGBOUNCER = IMAGE_PGBOUNCER ?: getParam("IMAGE_PGBOUNCER", "IMAGE_PGBOUNCER${PILLAR_VERSION}")
        IMAGE_BACKREST = IMAGE_BACKREST ?: getParam("IMAGE_BACKREST", "IMAGE_BACKREST${PILLAR_VERSION}")
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: getParam("IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: getParam("IMAGE_PMM_SERVER")
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: getParam("IMAGE_PMM3_CLIENT")
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: getParam("IMAGE_PMM3_SERVER")
        IMAGE_UPGRADE = IMAGE_UPGRADE ?: getParam("IMAGE_UPGRADE")
        if ("$PLATFORM_VER".toLowerCase() == "min" || "$PLATFORM_VER".toLowerCase() == "max") {
            PLATFORM_VER = getParam("PLATFORM_VER", "OPENSHIFT_${PLATFORM_VER}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    if ("$PLATFORM_VER" == "latest") {
        OC_VER="4.15.25"
        PLATFORM_VER = sh(script: "curl -s https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/$PLATFORM_VER/release.txt | sed -n 's/^\\s*Version:\\s\\+\\(\\S\\+\\)\\s*\$/\\1/p'", returnStdout: true).trim()
    } else {
        if ("$PLATFORM_VER" <= "4.15.25") {
            OC_VER="$PLATFORM_VER"
        } else {
            OC_VER="4.15.25"
        }
    }
    echo "OC_VER=$OC_VER"

    if ("$IMAGE_POSTGRESQL") {
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.displayName = "#" + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER " + "$IMAGE_POSTGRESQL".split(":")[1] + " $cw"
    }
}

void prepareSources() {
    echo "=========================[ Cloning the sources ]========================="
    sh """
        git clone -b $GIT_BRANCH https://github.com/percona/percona-postgresql-operator.git  source
    """

    initParams()

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$PLATFORM_VER-$CLUSTER_WIDE-$PG_VER-$IMAGE_OPERATOR-$IMAGE_POSTGRESQL-$IMAGE_PGBOUNCER-$IMAGE_BACKREST-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER-$IMAGE_UPGRADE | md5sum | cut -d' ' -f1", returnStdout: true).trim()
    CLUSTER_NAME = sh(script: "echo $JOB_NAME-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", returnStdout: true).trim()
}

void prepareAgent() {
    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        sudo curl -sLo /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -sL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
        kubectl version --client --output=yaml

        curl -fsSL https://get.helm.sh/helm-v3.18.3-linux-amd64.tar.gz | sudo tar -C /usr/local/bin --strip-components 1 -xzf - linux-amd64/helm

        sudo curl -fsSL https://github.com/mikefarah/yq/releases/download/v4.44.1/yq_linux_amd64 -o /usr/local/bin/yq && sudo chmod +x /usr/local/bin/yq
        sudo curl -fsSL https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux64 -o /usr/local/bin/jq && sudo chmod +x /usr/local/bin/jq

        curl -fsSL https://github.com/kubernetes-sigs/krew/releases/latest/download/krew-linux_amd64.tar.gz | tar -xzf -
        ./krew-linux_amd64 install krew
        export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

        kubectl krew install assert

        # v0.22.0 kuttl version
        kubectl krew install --manifest-url https://raw.githubusercontent.com/kubernetes-sigs/krew-index/02d5befb2bc9554fdcd8386b8bfbed2732d6802e/plugins/kuttl.yaml
        echo \$(kubectl kuttl --version) is installed

        curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$OC_VER/openshift-client-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - oc
        curl -s -L https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - openshift-install
    """
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

    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE'), usernamePassword(credentialsId: 'docker.io', passwordVariable: 'DOCKER_READ_PASS', usernameVariable: 'DOCKER_READ_USER')]) {
        sh """
            mkdir -p openshift/$CLUSTER_SUFFIX
            timestamp="\$(date +%s)"
tee openshift/$CLUSTER_SUFFIX/install-config.yaml << EOF
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
  name: $CLUSTER_NAME-$CLUSTER_SUFFIX
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
      delete-cluster-after-hours: 8
      team: cloud
      product: pg-operator
      creation-time: \$timestamp

publish: External
EOF
            cat $OPENSHIFT_CONF_FILE >> openshift/$CLUSTER_SUFFIX/install-config.yaml
        """

        withEnv(["CLUSTER_SUFFIX=${CLUSTER_SUFFIX}"]) {
            sshagent(['aws-openshift-41-key']) {
                sh '''
                    /usr/local/bin/openshift-install create cluster --dir=openshift/$CLUSTER_SUFFIX
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

                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=pg-operator
                    [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-postgresql-operator:$GIT_BRANCH
                    export PG_VER=$PG_VER
                    if [[ "$IMAGE_POSTGRESQL" ]]; then
                        export IMAGE_POSTGRESQL=$IMAGE_POSTGRESQL
                        export PG_VER=\$(echo \$IMAGE_POSTGRESQL | grep -Eo 'ppg[0-9]+'| sed 's/ppg//g')
                    fi
                    export IMAGE_PGBOUNCER=$IMAGE_PGBOUNCER
                    export IMAGE_BACKREST=$IMAGE_BACKREST
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                    export IMAGE_PMM3_CLIENT=$IMAGE_PMM3_CLIENT
                    export IMAGE_PMM3_SERVER=$IMAGE_PMM3_SERVER
                    export IMAGE_UPGRADE=$IMAGE_UPGRADE
                    export KUBECONFIG=$WORKSPACE/openshift/$clusterSuffix/auth/kubeconfig
                    export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"
                    export SKIP_TEST_WARNINGS=$SKIP_TEST_WARNINGS

                    kubectl kuttl test --config e2e-tests/kuttl.yaml --test "^$testName\$"
                """
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
            tests[TEST_ID]["result"] = "passed"
            return true
        }
        catch (exc) {
            echo "Error occurred while running test $testName: $exc"
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
    testsReport = "<testsuite name=\"$JOB_NAME\">\n"
    for (int i = 0; i < tests.size(); i ++) {
        testsReport += '<testcase name="' + tests[i]["name"] + '" time="' + tests[i]["time"] + '"><'+ tests[i]["result"] +'/></testcase>\n'
    }
    testsReport += '</testsuite>\n'

    echo "=========================[ Generating Parameters Report ]========================="
    pipelineParameters = """
testsuite name=$JOB_NAME
PG_VER=${PG_VER ?: 'e2e_defaults'}
IMAGE_OPERATOR=${IMAGE_OPERATOR ?: 'e2e_defaults'}
IMAGE_POSTGRESQL=${IMAGE_POSTGRESQL ?: 'e2e_defaults'}
IMAGE_PGBOUNCER=${IMAGE_PGBOUNCER ?: 'e2e_defaults'}
IMAGE_BACKREST=${IMAGE_BACKREST ?: 'e2e_defaults'}
IMAGE_PMM_CLIENT=${IMAGE_PMM_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM_SERVER=${IMAGE_PMM_SERVER ?: 'e2e_defaults'}
IMAGE_PMM3_CLIENT=${IMAGE_PMM3_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM3_SERVER=${IMAGE_PMM3_SERVER ?: 'e2e_defaults'}
IMAGE_UPGRADE=${IMAGE_UPGRADE ?: 'e2e_defaults'}
PLATFORM_VER=$PLATFORM_VER"""

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd'], file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
        sshagent(['aws-openshift-41-key']) {
            sh """
                export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                for namespace in \$(kubectl get namespaces --no-headers | awk '{print \$1}' | grep -vE "^kube-|^openshift" | sed '/-operator/ s/^/1-/' | sort | sed 's/^1-//'); do
                    kubectl delete deployments --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete sts --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete replicasets --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete poddisruptionbudget --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete services --all -n \$namespace --force --grace-period=0 || true
                    kubectl delete pods --all -n \$namespace --force --grace-period=0 || true
                done
                kubectl get svc --all-namespaces || true
                /usr/local/bin/openshift-install destroy cluster --dir=openshift/$CLUSTER_SUFFIX || true
            """
        }
    }
}

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \$IMAGE_POSTGRESQL ]] && echo \$IMAGE_POSTGRESQL | awk -F':' '{print \$2}' | grep -oE '[A-Za-z0-9\\.]+-ppg[0-9]{2}' || echo main-ppg17", , returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: 'NO\nYES', description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: 'none\n12\n13\n14\n15\n16\n17', description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'OpenShift kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: 'YES\nNO', description: 'Run tests in cluster wide mode')
        string(name: 'PG_VER', defaultValue: '', description: 'PG version')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg17-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg17-pgbouncer')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg17-pgbackrest')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-upgrade')
        string(name: 'AWS_REGION', defaultValue: 'eu-west-3', description: 'AWS region to use for openshift cluster')
        choice(name: 'SKIP_TEST_WARNINGS', choices: 'false\ntrue', description: 'Skip test warnings that requires release documentation')
    }
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('weekly-pgo');
    }
    stages {
        stage('Prepare Node') {
            steps {
                script { deleteDir() }
                prepareSources()
                prepareAgent()
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
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            parallel {
                stage('cluster1') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        prepareAgent()
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
