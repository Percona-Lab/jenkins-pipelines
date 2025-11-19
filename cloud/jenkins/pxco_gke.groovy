def gkeLib

tests = []
clusters = []
release_versions = 'source/e2e-tests/release_versions'

void prepareNode() {
    echo '=========================[ Cloning the sources ]========================='
    git branch: 'gke-cloud-lib', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    gkeLib = load('cloud/common/gke-lib.groovy')
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo git config --global --add safe.directory '*'
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf source
        cloud/local/checkout $GIT_REPO $GIT_BRANCH
    """

    if ("$PILLAR_VERSION" != 'none') {
        echo '=========================[ Getting parameters for release test ]========================='
        GKE_RELEASE_CHANNEL = 'stable'
        echo 'Forcing GKE_RELEASE_CHANNEL=stable, because it\'s a release run!'

        IMAGE_OPERATOR = IMAGE_OPERATOR ?: gkeLib.getParam(release_versions, 'IMAGE_OPERATOR')
        IMAGE_PXC = IMAGE_PXC ?: gkeLib.getParam(release_versions, 'IMAGE_PXC', "IMAGE_PXC${PILLAR_VERSION}")
        IMAGE_PROXY = IMAGE_PROXY ?: gkeLib.getParam(release_versions, 'IMAGE_PROXY')
        IMAGE_HAPROXY = IMAGE_HAPROXY ?: gkeLib.getParam(release_versions, 'IMAGE_HAPROXY')
        IMAGE_BACKUP = IMAGE_BACKUP ?: gkeLib.getParam(release_versions, 'IMAGE_BACKUP', "IMAGE_BACKUP${PILLAR_VERSION}")
        IMAGE_LOGCOLLECTOR = IMAGE_LOGCOLLECTOR ?: gkeLib.getParam(release_versions, 'IMAGE_LOGCOLLECTOR')
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: gkeLib.getParam(release_versions, 'IMAGE_PMM_CLIENT')
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: gkeLib.getParam(release_versions, 'IMAGE_PMM_SERVER')
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: gkeLib.getParam(release_versions, 'IMAGE_PMM3_CLIENT')
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: gkeLib.getParam(release_versions, 'IMAGE_PMM3_SERVER')
        if ("$PLATFORM_VER".toLowerCase() == 'min' || "$PLATFORM_VER".toLowerCase() == 'max') {
            PLATFORM_VER = gkeLib.getParam(release_versions, 'PLATFORM_VER', "GKE_${PLATFORM_VER}")
        }
    } else {
        echo '=========================[ Not a release run. Using job params only! ]========================='
    }

    echo '=========================[ Installing tools on the Jenkins executor ]========================='
    gkeLib.installCommonTools()
    gkeLib.installKubectl()
    gkeLib.installHelm()
    gkeLib.installGcloudCLI()

    sh """
        sudo yum install -y https://repo.percona.com/yum/percona-release-latest.noarch.rpm || true
        sudo percona-release enable-only tools
        sudo yum install -y percona-xtrabackup-80 | true
    """

    echo '=========================[ Logging in the Kubernetes provider ]========================='
    gkeLib.gcloudAuth()

    if ("$PLATFORM_VER" == 'latest') {
        PLATFORM_VER = sh(script: "gcloud container get-server-config --region=${GKE_REGION} --flatten=channels --filter='channels.channel=RAPID' --format='value(channels.validVersions)' | cut -d- -f1", returnStdout: true).trim()
    }

    if ("$IMAGE_PXC") {
        release = ("$PILLAR_VERSION" != 'none') ? 'RELEASE-' : ''
        cw = ("$CLUSTER_WIDE" == 'YES') ? 'CW' : 'NON-CW'
        currentBuild.description = "$release$GIT_BRANCH-$PLATFORM_VER-$GKE_RELEASE_CHANNEL-$cw-" + "$IMAGE_PXC".split(':')[1]
    }

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    CLUSTER_NAME = sh(script: "echo jenkins-$JOB_NAME-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$GKE_RELEASE_CHANNEL-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_PXC-$IMAGE_PROXY-$IMAGE_HAPROXY-$IMAGE_BACKUP-$IMAGE_LOGCOLLECTOR-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER | md5sum | cut -d' ' -f1", returnStdout: true).trim()
}

void dockerBuildPush() {
    gkeLib.dockerBuildPush('percona-xtradb-cluster-operator', GIT_BRANCH, IMAGE_OPERATOR)
}

void initTests() {
    gkeLib.initTests(tests, [
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
    gkeLib.createGKECluster(CLUSTER_NAME, clusterSuffix, GKE_REGION, GKE_RELEASE_CHANNEL, PLATFORM_VER)
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
                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=pxc-operator
                    [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-xtradb-cluster-operator:$GIT_BRANCH
                    export IMAGE_PXC=$IMAGE_PXC
                    export IMAGE_PROXY=$IMAGE_PROXY
                    export IMAGE_HAPROXY=$IMAGE_HAPROXY
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
    gkeLib.pushArtifactFile(fileName, GIT_SHORT_COMMIT)
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
IMAGE_PXC=${IMAGE_PXC ?: 'e2e_defaults'}
IMAGE_PROXY=${IMAGE_PROXY ?: 'e2e_defaults'}
IMAGE_HAPROXY=${IMAGE_HAPROXY ?: 'e2e_defaults'}
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
    gkeLib.shutdownCluster(CLUSTER_NAME, clusterSuffix, GKE_REGION, true)
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        DB_TAG = sh(script: "[[ \"$IMAGE_PXC\" ]] && echo $IMAGE_PXC | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(
            choices: ['run-release.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE'
        )
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST'
        )
        choice(
            choices: ['NO', 'YES'],
            description: 'Ignore passed tests in previous run (run all)',
            name: 'IGNORE_PREVIOUS_RUN'
        )
        choice(
            choices: ['none', '84', '80', '57'],
            description: 'Implies release run.',
            name: 'PILLAR_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH'
        )
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO'
        )
        string(
            defaultValue: 'latest',
            description: 'GKE kubernetes version. If set to min or max, value will be automatically taken from release_versions file.',
            name: 'PLATFORM_VER'
        )
        choice(
            choices: ['rapid', 'stable', 'regular', 'None'],
            description: 'GKE release channel',
            name: 'GKE_RELEASE_CHANNEL'
        )
        choice(
            choices: ['YES', 'NO'],
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE'
        )
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main',
            name: 'IMAGE_OPERATOR'
        )
        string(
            defaultValue: '',
            description: 'PXC image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0',
            name: 'IMAGE_PXC'
        )
        string(
            defaultValue: '',
            description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:main-proxysql',
            name: 'IMAGE_PROXY'
        )
        string(
            defaultValue: '',
            description: 'PXC haproxy image: perconalab/percona-xtradb-cluster-operator:main-haproxy',
            name: 'IMAGE_HAPROXY'
        )
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup',
            name: 'IMAGE_BACKUP'
        )
        string(
            defaultValue: '',
            description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector',
            name: 'IMAGE_LOGCOLLECTOR'
        )
        string(
            defaultValue: '',
            description: 'PMM client image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM_CLIENT'
        )
        string(
            defaultValue: '',
            description: 'PMM server image: perconalab/pmm-server:dev-latest',
            name: 'IMAGE_PMM_SERVER'
        )
        string(
            defaultValue: '',
            description: 'ex: perconalab/pmm-client:3-dev-latest',
            name: 'IMAGE_PMM3_CLIENT'
        )
        string(
            defaultValue: '',
            description: 'ex: perconalab/pmm-server:3-dev-latest',
            name: 'IMAGE_PMM3_SERVER'
        )
        string(
            defaultValue: 'us-central1-a',
            description: 'GKE region to use for cluster',
            name: 'GKE_REGION'
        )
        choice(
            choices: ['NO', 'YES'],
            description: 'Run tests with debug',
            name: 'DEBUG_TESTS'
        )
        choice(
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for build',
            name: 'JENKINS_AGENT'
        )
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('pxc-operator-latest-scheduler');
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
            echo 'CLUSTER ASSIGNMENTS\n' + tests.toString().replace('], ',']\n').replace(']]',']').replaceFirst('\\[','')
            makeReport()
            junit testResults: '*.xml', healthScaleFactor: 1.0
            archiveArtifacts '*.xml,*.txt'

            script {
                // if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
                //     slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "[$JOB_NAME]: build $currentBuild.result, $BUILD_URL"
                // }
                
                echo "Clusters to shutdown: ${clusters.join(', ')}"
                clusters.each { shutdownCluster(it) }
            }

            sh 'sudo docker system prune --volumes -af'
            deleteDir()
        }
    }
}
