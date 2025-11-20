def gkeLib

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
        IMAGE_MYSQL = IMAGE_MYSQL ?: gkeLib.getParam(release_versions, 'IMAGE_MYSQL', "IMAGE_MYSQL${PILLAR_VERSION}")
        IMAGE_BACKUP = IMAGE_BACKUP ?: gkeLib.getParam(release_versions, 'IMAGE_BACKUP', "IMAGE_BACKUP${PILLAR_VERSION}")
        IMAGE_ROUTER = IMAGE_ROUTER ?: gkeLib.getParam(release_versions, 'IMAGE_ROUTER', "IMAGE_ROUTER${PILLAR_VERSION}")
        IMAGE_HAPROXY = IMAGE_HAPROXY ?: gkeLib.getParam(release_versions, 'IMAGE_HAPROXY')
        IMAGE_ORCHESTRATOR = IMAGE_ORCHESTRATOR ?: gkeLib.getParam(release_versions, 'IMAGE_ORCHESTRATOR')
        IMAGE_TOOLKIT = IMAGE_TOOLKIT ?: gkeLib.getParam(release_versions, 'IMAGE_TOOLKIT')
        IMAGE_PMM_CLIENT = IMAGE_PMM_CLIENT ?: gkeLib.getParam(release_versions, 'IMAGE_PMM_CLIENT')
        IMAGE_PMM_SERVER = IMAGE_PMM_SERVER ?: gkeLib.getParam(release_versions, 'IMAGE_PMM_SERVER')
        if ("$PLATFORM_VER".toLowerCase() == 'min' || "$PLATFORM_VER".toLowerCase() == 'max') {
            PLATFORM_VER = gkeLib.getParam(release_versions, 'PLATFORM_VER', "GKE_${PLATFORM_VER}")
        }
    } else {
        echo '=========================[ Not a release run. Using job params only! ]========================='
    }

    if ("$PLATFORM_VER" == 'latest') {
        PLATFORM_VER = sh(script: "gcloud container get-server-config --region=${GKE_REGION} --flatten=channels --filter='channels.channel=$GKE_RELEASE_CHANNEL' --format='value(channels.validVersions)' | cut -d- -f1", returnStdout: true).trim()
    }

    if ("$IMAGE_MYSQL") {
        cw = ("$CLUSTER_WIDE" == 'YES') ? 'CW' : 'NON-CW'
        currentBuild.displayName = '#' + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER-$GKE_RELEASE_CHANNEL " + "$IMAGE_MYSQL".split(':')[1] + " $cw"
    }
}

void prepareSources() {
    echo '=========================[ Cloning the sources ]========================='
    sh """
        git clone -b $GIT_BRANCH https://github.com/percona/percona-server-mysql-operator source
    """

    GIT_SHORT_COMMIT = sh(script: 'git -C source rev-parse --short HEAD', returnStdout: true).trim()
    PARAMS_HASH = sh(script: "echo $GIT_BRANCH-$GIT_SHORT_COMMIT-$GKE_RELEASE_CHANNEL-$PLATFORM_VER-$CLUSTER_WIDE-$IMAGE_OPERATOR-$IMAGE_MYSQL-$IMAGE_BACKUP-$IMAGE_ROUTER-$IMAGE_HAPROXY-$IMAGE_ORCHESTRATOR-$IMAGE_TOOLKIT-$IMAGE_PMM_CLIENT-$IMAGE_PMM_SERVER | md5sum | cut -d' ' -f1", returnStdout: true).trim()
    CLUSTER_NAME = sh(script: "echo $JOB_NAME-$GIT_SHORT_COMMIT | tr '[:upper:]' '[:lower:]'", returnStdout: true).trim()
}

void dockerBuildPush() {
    gkeLib.dockerBuildPush('percona-server-mysql-operator', GIT_BRANCH, IMAGE_OPERATOR, 'e2e-tests/build', false)
}

void initTests() {
    gkeLib.initTests(tests, [
        testList: "$TEST_LIST",
        testSuite: "$TEST_SUITE",
        gitShortCommit: GIT_SHORT_COMMIT,
        paramsHash: PARAMS_HASH,
        ignorePreviousRun: "$IGNORE_PREVIOUS_RUN",
        cloudSecretCredId: 'cloud-secret-file-ps',
        setPermissions: true,
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

                    [[ "$CLUSTER_WIDE" == "YES" ]] && export OPERATOR_NS=ps-operator
                    [[ "$IMAGE_OPERATOR" ]] && export IMAGE=$IMAGE_OPERATOR || export IMAGE=perconalab/percona-server-mysql-operator:$GIT_BRANCH
                    export IMAGE_MYSQL=$IMAGE_MYSQL
                    export IMAGE_BACKUP=$IMAGE_BACKUP
                    export IMAGE_ROUTER=$IMAGE_ROUTER
                    export IMAGE_HAPROXY=$IMAGE_HAPROXY
                    export IMAGE_ORCHESTRATOR=$IMAGE_ORCHESTRATOR
                    export IMAGE_TOOLKIT=$IMAGE_TOOLKIT
                    export IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT
                    export IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER
                    export KUBECONFIG=/tmp/$CLUSTER_NAME-$clusterSuffix
                    export PATH="\${KREW_ROOT:-\$HOME/.krew}/bin:\$PATH"

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
IMAGE_OPERATOR=${IMAGE_OPERATOR ?: 'e2e_defaults'}
IMAGE_MYSQL=${IMAGE_MYSQL ?: 'e2e_defaults'}
IMAGE_BACKUP=${IMAGE_BACKUP ?: 'e2e_defaults'}
IMAGE_ROUTER=${IMAGE_ROUTER ?: 'e2e_defaults'}
IMAGE_HAPROXY=${IMAGE_HAPROXY ?: 'e2e_defaults'}
IMAGE_ORCHESTRATOR=${IMAGE_ORCHESTRATOR ?: 'e2e_defaults'}
IMAGE_TOOLKIT=${IMAGE_TOOLKIT ?: 'e2e_defaults'}
IMAGE_PMM_CLIENT=${IMAGE_PMM_CLIENT ?: 'e2e_defaults'}
IMAGE_PMM_SERVER=${IMAGE_PMM_SERVER ?: 'e2e_defaults'}
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
        DB_TAG = sh(script: "[[ \$IMAGE_MYSQL ]] && echo \$IMAGE_MYSQL | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
        PMM_TELEMETRY_TOKEN = credentials('PMM-CHECK-DEV-TOKEN')
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '84', '80'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mysql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'GKE kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'GKE_RELEASE_CHANNEL', choices: ['rapid', 'stable', 'regular', 'None'], description: 'GKE release channel. Will be forced to stable for release run.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main')
        string(name: 'IMAGE_MYSQL', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-psmysql8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-backup8.0')
        string(name: 'IMAGE_ROUTER', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-router8.0')
        string(name: 'IMAGE_HAPROXY', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-haproxy')
        string(name: 'IMAGE_ORCHESTRATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-orchestrator')
        string(name: 'IMAGE_TOOLKIT', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-toolkit')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'GKE_REGION', defaultValue: 'us-central1-a', description: 'GKE region to use for cluster')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('weekly-pso');
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
                stage('cluster5') {
                    agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker' }
                    steps {
                        prepareParallelAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster5')
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
