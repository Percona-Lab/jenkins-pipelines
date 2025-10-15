def gkeLib

tests=[]
clusters=[]
release_versions="source/e2e-tests/release_versions"

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

    gkeLib = load("${WORKSPACE}/cloud/common/gke-functions.groovy")

    if ("$PILLAR_VERSION" != "none") {
        echo "=========================[ Getting parameters for release test ]========================="
        GKE_RELEASE_CHANNEL = "stable"
        echo "Forcing GKE_RELEASE_CHANNEL=stable, because it's a release run!"

        IMAGE_OPERATOR = IMAGE_OPERATOR ?: gkeLib.getParam(release_versions, "IMAGE_OPERATOR")
        IMAGE_MONGOD = IMAGE_MONGOD ?: gkeLib.getParam(release_versions, "IMAGE_MONGOD", "IMAGE_MONGOD${PILLAR_VERSION}")
        IMAGE_BACKUP = IMAGE_BACKUP ?: gkeLib.getParam(release_versions, "IMAGE_BACKUP")
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: gkeLib.getParam(release_versions, "IMAGE_PMM_CLIENT")
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: gkeLib.getParam(release_versions, "IMAGE_PMM_SERVER")
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: gkeLib.getParam(release_versions, "IMAGE_PMM3_CLIENT")
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: gkeLib.getParam(release_versions, "IMAGE_PMM3_SERVER")
        IMAGE_LOGCOLLECTOR = IMAGE_LOGCOLLECTOR ?: gkeLib.getParam(release_versions, "IMAGE_LOGCOLLECTOR")
        if ("$PLATFORM_VER".toLowerCase() == "min" || "$PLATFORM_VER".toLowerCase() == "max") {
            PLATFORM_VER = gkeLib.getParam(release_versions, "PLATFORM_VER", "GKE_${PLATFORM_VER}")
        }
    } else {
        echo "=========================[ Not a release run. Using job params only! ]========================="
    }

    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    gkeLib.installCommonTools()
    gkeLib.downloadKubectl()
    gkeLib.installHelm()
    gkeLib.installGcloudCLI()

    echo "=========================[ Logging in the Kubernetes provider ]========================="
    gkeLib.gcloudAuth()

    if ("$PLATFORM_VER" == "latest") {
        PLATFORM_VER = sh(script: "gcloud container get-server-config --region=${GKE_REGION} --flatten=channels --filter='channels.channel=$GKE_RELEASE_CHANNEL' --format='value(channels.validVersions)' | cut -d- -f1", returnStdout: true).trim()
    }

    if ("$ARCH" == "amd64") {
        MACHINE_TYPE="n1-standard-4"
    } else if ("$ARCH" == "arm64") {
        MACHINE_TYPE="t2a-standard-4"
    } else {
        error("Unknown architecture $ARCH")
    }

    if ("$IMAGE_MONGOD") {
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.displayName = "#" + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER-$GKE_RELEASE_CHANNEL $ARCH " + "$IMAGE_MONGOD".split(":")[1] + " $cw"
    }

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    CLUSTER_NAME = sh(script: "echo jenkins-$JOB_NAME-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$GKE_RELEASE_CHANNEL-$ARCH-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MONGOD-$IMAGE_BACKUP-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER-$IMAGE_LOGCOLLECTOR | md5sum | cut -d' ' -f1", returnStdout: true).trim()
}

void dockerBuildPush() {
    gkeLib.dockerBuildPush('percona-server-mongodb-operator', GIT_BRANCH, IMAGE_OPERATOR)
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
    gkeLib.createGKECluster(CLUSTER_NAME, CLUSTER_SUFFIX, GKE_REGION, GKE_RELEASE_CHANNEL, PLATFORM_VER, MACHINE_TYPE)
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
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix

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
    gkeLib.pushArtifactFile(FILE_NAME, GIT_SHORT_COMMIT)
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
PLATFORM_VER=$PLATFORM_VER
GKE_RELEASE_CHANNEL=$GKE_RELEASE_CHANNEL"""

    writeFile file: "TestsReport.xml", text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    gkeLib.shutdownCluster(CLUSTER_NAME, CLUSTER_SUFFIX, GKE_REGION, true)
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
        choice(name: 'ARCH', choices: ['amd64', 'arm64'], description: 'Architecture')
        choice(name: 'PILLAR_VERSION', choices: ['none', '80', '70', '60'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mongodb-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'GKE kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'GKE_RELEASE_CHANNEL', choices: ['rapid', 'stable', 'regular', 'None'], description: 'GKE release channel. Will be forced to stable for release run.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongod8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-backup')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'ex: perconalab/fluentbit:main-logcollector')
        string(name: 'GKE_REGION', defaultValue: 'us-central1-a', description: 'GKE region to use for cluster')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Run tests with debug')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
