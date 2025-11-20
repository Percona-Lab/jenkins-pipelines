def gkeLib

region = 'us-central1-c'
tests = []
clusters = []
release_versions = 'source/e2e-tests/release_versions'

void loadLibrary() {
    git branch: 'gke-cloud-lib', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
    gkeLib = load('cloud/common/gke-lib.groovy')
}

void prepareParallelAgent() {
    loadLibrary()
    installTools()
    gkeLib.gcloudAuth()
}

void installTools() {
    echo '=========================[ Installing tools on the Jenkins executor ]========================='
    gkeLib.installCommonTools()
    gkeLib.installKubectl()
    gkeLib.installHelm()
    gkeLib.installKrewAndKuttl()
    gkeLib.installGcloudCLI()
}

void initParams() {
    if ("$PILLAR_VERSION" != 'none') {
        echo '=========================[ Getting parameters for release test ]========================='
        GKE_RELEASE_CHANNEL = 'stable'
        echo 'Forcing GKE_RELEASE_CHANNEL=stable, because it\'s a release run!'

        IMAGE_OPERATOR = IMAGE_OPERATOR ?: gkeLib.getParam(release_versions, 'IMAGE_OPERATOR')
        IMAGE_POSTGRESQL = IMAGE_POSTGRESQL ?: gkeLib.getParam(release_versions, 'IMAGE_POSTGRESQL', "IMAGE_POSTGRESQL${PILLAR_VERSION}")
        IMAGE_PGBOUNCER = IMAGE_PGBOUNCER ?: gkeLib.getParam(release_versions, 'IMAGE_PGBOUNCER', "IMAGE_PGBOUNCER${PILLAR_VERSION}")
        IMAGE_BACKREST = IMAGE_BACKREST ?: gkeLib.getParam(release_versions, 'IMAGE_BACKREST', "IMAGE_BACKREST${PILLAR_VERSION}")
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: gkeLib.getParam(release_versions, 'IMAGE_PMM_CLIENT')
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: gkeLib.getParam(release_versions, 'IMAGE_PMM_SERVER')
        IMAGE_PMM3_CLIENT = IMAGE_PMM3_CLIENT ?: gkeLib.getParam(release_versions, 'IMAGE_PMM3_CLIENT')
        IMAGE_PMM3_SERVER = IMAGE_PMM3_SERVER ?: gkeLib.getParam(release_versions, 'IMAGE_PMM3_SERVER')
        IMAGE_UPGRADE = IMAGE_UPGRADE ?: gkeLib.getParam(release_versions, 'IMAGE_UPGRADE')
        if ("$PLATFORM_VER".toLowerCase() == 'min' || "$PLATFORM_VER".toLowerCase() == 'max') {
            PLATFORM_VER = gkeLib.getParam(release_versions, 'PLATFORM_VER', "GKE_${PLATFORM_VER}")
        }
    } else {
        echo '=========================[ Not a release run. Using job params only! ]========================='
    }

    if ("$PLATFORM_VER" == 'latest') {
        PLATFORM_VER = sh(script: "gcloud container get-server-config --region=${GKE_REGION} --flatten=channels --filter='channels.channel=$GKE_RELEASE_CHANNEL' --format='value(channels.validVersions)' | cut -d- -f1", returnStdout: true).trim()
    }

    if ("$IMAGE_POSTGRESQL") {
        cw = ("$CLUSTER_WIDE" == 'YES') ? 'CW' : 'NON-CW'
        currentBuild.displayName = '#' + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER-$GKE_RELEASE_CHANNEL " + "$IMAGE_POSTGRESQL".split(':')[1] + " $cw"
    }
}

void prepareSources() {
    echo '=========================[ Cloning the sources ]========================='
    sh """
        git clone -b $GIT_BRANCH https://github.com/percona/percona-postgresql-operator.git  source
    """

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$GKE_RELEASE_CHANNEL-$PLATFORM_VER-$CLUSTER_WIDE-$PG_VER-$IMAGE_OPERATOR-$IMAGE_POSTGRESQL-$IMAGE_PGBOUNCER-$IMAGE_BACKREST-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER-$IMAGE_PMM3_CLIENT-$IMAGE_PMM3_SERVER-$IMAGE_UPGRADE | md5sum | cut -d' ' -f1", returnStdout: true).trim()
    CLUSTER_NAME = sh(script: "echo jenkins-$JOB_NAME-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", returnStdout: true).trim()
}

void dockerBuildPush() {
    gkeLib.dockerBuildPush('percona-postgresql-operator', GIT_BRANCH, IMAGE_OPERATOR, 'make build-docker-image', false)
}

void initTests() {
    gkeLib.initTests(tests, [
        testList: "$TEST_LIST",
        testSuite: "$TEST_SUITE",
        gitShortCommit: GIT_SHORT_COMMIT,
        paramsHash: PARAMS_HASH,
        ignorePreviousRun: "$IGNORE_PREVIOUS_RUN",
        setPermissions: true,
        additionalSecret: [credId: 'cloud-minio-secret-file', file: 'cloud-secret-minio-gw.yml'],
        useStash: true
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
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix
                    export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"
                    export SKIP_TEST_WARNINGS=$SKIP_TEST_WARNINGS

                    kubectl kuttl test --config e2e-tests/kuttl.yaml --test "^$testName\$"
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
PLATFORM_VER=$PLATFORM_VER
GKE_RELEASE_CHANNEL=$GKE_RELEASE_CHANNEL"""

    writeFile file: 'TestsReport.xml', text: testsReport
    writeFile file: 'PipelineParameters.txt', text: pipelineParameters

    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
        text: "<pre>${pipelineParameters}</pre>"
    )
}

void shutdownCluster(String clusterSuffix) {
    gkeLib.shutdownCluster(CLUSTER_NAME, clusterSuffix, GKE_REGION, false)
}

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \$IMAGE_POSTGRESQL ]] && echo \$IMAGE_POSTGRESQL | awk -F':' '{print \$2}' | grep -oE '[A-Za-z0-9\\.]+-ppg[0-9]{2}' || echo main-ppg17", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '13', '14', '15', '16', '17'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'GKE kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'GKE_RELEASE_CHANNEL', choices: ['rapid', 'stable', 'regular', 'None'], description: 'GKE release channel. Will be forced to stable for release run.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
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
        string(name: 'GKE_REGION', defaultValue: 'us-central1-c', description: 'GKE region to use for cluster')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that requires release documentation')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
                script { 
                    deleteDir()
                    loadLibrary()
                    initParams()
                    installTools()
                    gkeLib.gcloudAuth()
                    prepareSources()
                }
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
                    agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker' }
                    steps {
                        prepareParallelAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker' }
                    steps {
                        prepareParallelAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker' }
                    steps {
                        prepareParallelAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker' }
                    steps {
                        prepareParallelAgent()
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
            junit testResults: '*.xml', healthScaleFactor: 1.0
            archiveArtifacts '*.xml,*.txt'

            script {
                // if (currentBuild.result != null && currentBuild.result != 'SUCCESS') {
                //     slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "[$JOB_NAME]: build $currentBuild.result, $BUILD_URL"
                // }

                echo "Clusters to shutdown: ${clusters.join(', ')}"
                clusters.each { shutdownCluster(it) }
            }
            sh '''
                sudo docker system prune --volumes -af
                sudo rm -rf *
            '''
            deleteDir()
        }
    }
}
