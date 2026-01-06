def utils

tests = []
clusters = []
release_versions = 'source/e2e-tests/release_versions'

void prepareNode() {
    echo '=========================[ Cloning the sources ]========================='
    git branch: 'gke-cloud-lib', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    utils = load('cloud/common/utils.groovy')
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf source
        git clone -b $GIT_BRANCH https://github.com/percona/percona-server-mongodb-operator source
    """

    if ("$PILLAR_VERSION" != 'none') {
        echo '=========================[ Getting parameters for release test ]========================='
        GKE_RELEASE_CHANNEL = 'stable'
        echo 'Forcing GKE_RELEASE_CHANNEL=stable, because it\'s a release run!'

        IMAGE_OPERATOR = IMAGE_OPERATOR ?: utils.getParam(release_versions, 'IMAGE_OPERATOR')
        IMAGE_MONGOD = IMAGE_MONGOD ?: utils.getParam(release_versions, 'IMAGE_MONGOD', "IMAGE_MONGOD${PILLAR_VERSION}")
        IMAGE_BACKUP = IMAGE_BACKUP ?: utils.getParam(release_versions, 'IMAGE_BACKUP')
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: utils.getParam(release_versions, 'IMAGE_PMM_CLIENT')
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: utils.getParam(release_versions, 'IMAGE_PMM_SERVER')
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: utils.getParam(release_versions, 'IMAGE_PMM3_CLIENT')
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: utils.getParam(release_versions, 'IMAGE_PMM3_SERVER')
        IMAGE_LOGCOLLECTOR = IMAGE_LOGCOLLECTOR ?: utils.getParam(release_versions, 'IMAGE_LOGCOLLECTOR')
        if ("$PLATFORM_VER".toLowerCase() == 'min' || "$PLATFORM_VER".toLowerCase() == 'max') {
            PLATFORM_VER = utils.getParam(release_versions, 'PLATFORM_VER', "GKE_${PLATFORM_VER}")
        }
    } else {
        echo '=========================[ Not a release run. Using job params only! ]========================='
    }

    echo '=========================[ Installing tools on the Jenkins executor ]========================='
    utils.installCommonTools()
    utils.installKubectl()
    utils.installHelm()
    utils.installGcloudCLI()
    utils.installAzureCLI(JENKINS_AGENT)

    echo '=========================[ Logging in the Kubernetes providers ]========================='
    utils.gcloudAuth()
    utils.azureAuth()

    if ("$PLATFORM_VER" == 'latest') {
        PLATFORM_VER = sh(script: "gcloud container get-server-config --region=${GKE_REGION} --flatten=channels --filter='channels.channel=$GKE_RELEASE_CHANNEL' --format='value(channels.validVersions)' | cut -d- -f1", returnStdout: true).trim()
    }

    if ("$ARCH" == 'amd64') {
        MACHINE_TYPE = 'n1-standard-4'
    } else if ("$ARCH" == 'arm64') {
        MACHINE_TYPE = 't2a-standard-4'
    } else {
        error("Unknown architecture $ARCH")
    }

    if ("$IMAGE_MONGOD") {
        cw = ("$CLUSTER_WIDE" == 'YES') ? 'CW' : 'NON-CW'
        currentBuild.displayName = '#' + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER-$GKE_RELEASE_CHANNEL $ARCH " + "$IMAGE_MONGOD".split(':')[1] + " $cw"
    }

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    CLUSTER_NAME = sh(script: "echo jenkins-$JOB_NAME-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$GKE_RELEASE_CHANNEL-$ARCH-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MONGOD-$IMAGE_BACKUP-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER-$IMAGE_LOGCOLLECTOR | md5sum | cut -d' ' -f1", returnStdout: true).trim()
}

void dockerBuildPush() {
    utils.dockerBuildPush('percona-server-mongodb-operator', GIT_BRANCH, IMAGE_OPERATOR)
}

void initTests() {
    utils.initTests(tests, [
        testList: "$TEST_LIST",
        testSuite: "$TEST_SUITE",
        gitShortCommit: GIT_SHORT_COMMIT,
        paramsHash: PARAMS_HASH,
        ignorePreviousRun: "$IGNORE_PREVIOUS_RUN"
    ])
}

void clusterRunner(String cluster) {
    def clusterCreated = 0

    try {
        for (int i = 0; i < tests.size(); i++) {
            if (tests[i]['result'] == 'skipped') {
                tests[i]['result'] = 'failure'
                tests[i]['cluster'] = cluster
                if (clusterCreated == 0) {
                    createCluster(cluster)
                    clusterCreated++
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

void createCluster(String clusterSuffix) {
    utils.createGKECluster(CLUSTER_NAME, clusterSuffix, GKE_REGION, GKE_RELEASE_CHANNEL, PLATFORM_VER, MACHINE_TYPE)
    clusters.add(clusterSuffix)
}

void runTest(Integer testId) {
    def retryCount = 0
    def testName = tests[testId]['name']
    def clusterSuffix = tests[testId]['cluster']

    waitUntil {
        def timeStart = new Date().getTime()
        try {
            echo "The $testName test was started on cluster $CLUSTER_NAME-$clusterSuffix !"
            tests[testId]['result'] = 'failure'

            timeout(time: 90, unit: 'MINUTES') {
                sh """
                    cd source

                    [[ "$DEBUG_TESTS" == "YES" ]] && export DEBUG_TESTS=1
                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=psmdb-operator
                    [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-server-mongodb-operator:$GIT_BRANCH
                    export IMAGE_MONGOD=$IMAGE_MONGOD
                    export IMAGE_BACKUP=$IMAGE_BACKUP
                    export IMAGE_LOGCOLLECTOR=$IMAGE_LOGCOLLECTOR
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                    export IMAGE_PMM3_CLIENT=$IMAGE_PMM3_CLIENT
                    export IMAGE_PMM3_SERVER=$IMAGE_PMM3_SERVER
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix

                    e2e-tests/$testName/run
                """
            }
            pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH")
            tests[testId]['result'] = 'passed'
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
            tests[testId]['time'] = durationSec
            echo "The $testName test was finished!"
        }
    }
}

void pushArtifactFile(String fileName) {
    utils.pushArtifactFile(fileName, GIT_SHORT_COMMIT)
}

void makeReport() {
    echo '=========================[ Generating Test Report ]========================='
    testsReport = "<testsuite name=\"$JOB_NAME\">\n"
    for (int i = 0; i < tests.size(); i ++) {
        testsReport += '<testcase name="' + tests[i]['name'] + '" time="' + tests[i]['time'] + '"><'+ tests[i]['result'] +'/></testcase>\n'
    }
    testsReport += '</testsuite>\n'

    echo '=========================[ Generating Parameters Report ]========================='
    pipelineParameters = """
testsuite name=$JOB_NAME
IMAGE_OPERATOR=${IMAGE_OPERATOR ?: 'e2e_defaults'}
IMAGE_MONGOD=${IMAGE_MONGOD ?: 'e2e_defaults'}
IMAGE_BACKUP=${IMAGE_BACKUP ?: 'e2e_defaults'}
IMAGE_LOGCOLLECTOR=${IMAGE_LOGCOLLECTOR ?: 'e2e_defaults'}
IMAGE_PMM_CLIENT=${IMAGE_PMM_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM_SERVER=${IMAGE_PMM_SERVER ?: 'e2e_defaults'}
IMAGE_PMM3_CLIENT=${IMAGE_PMM3_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM3_SERVER=${IMAGE_PMM3_SERVER ?: 'e2e_defaults'}
PLATFORM_VER=$PLATFORM_VER
GKE_RELEASE_CHANNEL=$GKE_RELEASE_CHANNEL"""

    writeFile file: 'TestsReport.xml', text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void shutdownCluster(String clusterSuffix) {
    utils.shutdownCluster(CLUSTER_NAME, clusterSuffix, GKE_REGION, true)
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
            junit testResults: '*.xml', healthScaleFactor: 1.0
            archiveArtifacts '*.xml,*.txt'

            script {
                // if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
                //    slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "[$JOB_NAME]: build $currentBuild.result, $BUILD_URL"
                // }

                echo "Clusters to shutdown: ${clusters.join(', ')}"
                clusters.each { shutdownCluster(it) }
            }

            sh 'sudo docker system prune --volumes -af'
            deleteDir()
        }
    }
}
