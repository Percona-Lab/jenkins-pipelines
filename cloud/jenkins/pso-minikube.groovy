tests=[]
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

void prepareAgent() {
    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    sh """
        sudo curl -s -L -o /usr/local/bin/kubectl https://dl.k8s.io/release/\$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && sudo chmod +x /usr/local/bin/kubectl
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

        sudo curl -sLo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo chmod +x /usr/local/bin/minikube
    """
}

void prepareSources() {
    echo "=========================[ Cloning the sources ]========================="
    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf source
        git clone -b $GIT_BRANCH https://github.com/percona/percona-server-mysql-operator source
    """

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MYSQL-$IMAGE_BACKUP-$IMAGE_ROUTER-$IMAGE_HAPROXY-$IMAGE_ORCHESTRATOR-$IMAGE_TOOLKIT-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER | md5sum | cut -d' ' -f1", returnStdout: true).trim()
}

void initParams() {
    if ("$PILLAR_VERSION" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        IMAGE_OPERATOR = IMAGE_OPERATOR ?: getParam("IMAGE_OPERATOR")
        IMAGE_MYSQL = IMAGE_MYSQL ?: getParam("IMAGE_MYSQL", "IMAGE_MYSQL${PILLAR_VERSION}")
        IMAGE_BACKUP = IMAGE_BACKUP ?: getParam("IMAGE_BACKUP", "IMAGE_BACKUP${PILLAR_VERSION}")
        IMAGE_ROUTER = IMAGE_ROUTER ?: getParam("IMAGE_ROUTER", "IMAGE_ROUTER${PILLAR_VERSION}")
        IMAGE_HAPROXY = IMAGE_HAPROXY ?: getParam("IMAGE_HAPROXY")
        IMAGE_ORCHESTRATOR = IMAGE_ORCHESTRATOR ?: getParam("IMAGE_ORCHESTRATOR")
        IMAGE_TOOLKIT = IMAGE_TOOLKIT ?: getParam("IMAGE_TOOLKIT")
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: getParam("IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: getParam("IMAGE_PMM_SERVER")
        if ("$PLATFORM_VER".toLowerCase() == "max") {
            PLATFORM_VER = getParam("PLATFORM_VER", "MINIKUBE_${PLATFORM_VER}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    if ("$IMAGE_MYSQL") {
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.displayName = "#" + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER " + "$IMAGE_MYSQL".split(":")[1] + " $cw"
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
                    docker login -u '$USER' -p '$PASS'
                    export IMAGE=perconalab/percona-server-mysql-operator:$GIT_BRANCH
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

    withCredentials([file(credentialsId: 'cloud-secret-file-ps', variable: 'CLOUD_SECRET_FILE')]) {
        sh """
            cp $CLOUD_SECRET_FILE source/e2e-tests/conf/cloud-secret.yml
        """
    }
    stash includes: "source/**", name: "sourceFILES"
}

void clusterRunner(String cluster) {
    def clusterCreated = false

    for (int i=0; i<tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            if (!clusterCreated) {
                createCluster(cluster)
                clusterCreated = true
            }
            runTest(i)
        }
    }
}

void createCluster(String CLUSTER_SUFFIX) {
    sh """
        echo "Creating cluster $CLUSTER_SUFFIX"
        export CHANGE_MINIKUBE_NONE_USER=true
        minikube start --kubernetes-version $PLATFORM_VER --cpus=6 --memory=28G
    """
}

void runTest(Integer TEST_ID) {
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]
    def clusterSuffix = tests[TEST_ID]["cluster"]

    unstash "sourceFILES"
    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started on cluster $clusterSuffix !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 90, unit: 'MINUTES') {
                sh """
                    cd source

                    export DEBUG_TESTS=1
                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=ps-operator
                    export IMAGE=$IMAGE_OPERATOR
                    export IMAGE_MYSQL=$IMAGE_MYSQL
                    export IMAGE_BACKUP=$IMAGE_BACKUP
                    export IMAGE_ROUTER=$IMAGE_ROUTER
                    export IMAGE_HAPROXY=$IMAGE_HAPROXY
                    export IMAGE_ORCHESTRATOR=$IMAGE_ORCHESTRATOR
                    export IMAGE_TOOLKIT=$IMAGE_TOOLKIT
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                    export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

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
        IMAGE_OPERATOR=$IMAGE_OPERATOR
        IMAGE_MYSQL=$IMAGE_MYSQL
        IMAGE_BACKUP=$IMAGE_BACKUP
        IMAGE_ROUTER=$IMAGE_ROUTER
        IMAGE_HAPROXY=$IMAGE_HAPROXY
        IMAGE_ORCHESTRATOR=$IMAGE_ORCHESTRATOR
        IMAGE_TOOLKIT=$IMAGE_TOOLKIT
        IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
        IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
        PLATFORM_VER=$PLATFORM_VER
    """

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters
}

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \"$IMAGE_MYSQL\" ]] && echo $IMAGE_MYSQL | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-minikube.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: 'NO\nYES', description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: 'none\n80', description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mysql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'Minikube kubernetes version. If set to rel, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: 'YES\nNO', description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main')
        string(name: 'IMAGE_MYSQL', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-psmysql')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-backup')
        string(name: 'IMAGE_ROUTER', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-router')
        string(name: 'IMAGE_HAPROXY', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-haproxy')
        string(name: 'IMAGE_ORCHESTRATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-orchestrator')
        string(name: 'IMAGE_TOOLKIT', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-toolkit')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
    }
    agent {
        label 'docker-32gb'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('ps-operator-latest-scheduler');
    }
    stages {
        stage('Prepare Node') {
            steps {
                prepareAgent()
                prepareSources()
                initParams()
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
                    agent { label 'docker' }
                    steps {
                        prepareAgent()
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    agent { label 'docker' }
                    steps {
                        prepareAgent()
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    agent { label 'docker' }
                    steps {
                        prepareAgent()
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    agent { label 'docker' }
                    steps {
                        prepareAgent()
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
            }
        }
    }
}
