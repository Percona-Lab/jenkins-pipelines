import groovy.transform.Field

@Field def numClusters = 8
@Field def tests = []
@Field def clusters = []
@Field def release_versions = "source/e2e-tests/release_versions"
@Field Map testVariables = [:]

void prepareNode() {
    checkout(scm)
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tools.gitResetWorkspace()
    libraries.tools.gitClone(
        branch: GIT_BRANCH,
        repo: GIT_REPO
    )

    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    libraries.dependencies.install()
    libraries.dependencies.installGoogleCLI()
    libraries.dependencies.installDoctl()
    libraries.dependencies.installPxcTools()

    def platformVersion = "$PLATFORM_VER"
    if ("$PILLAR_VERSION" != "none" && (platformVersion.toLowerCase() in ["min", "max"])) {
        platformVersion = libraries.tests.getReleaseVersionsParam(release_versions, "PLATFORM_VER", "DOKS_${platformVersion.toUpperCase()}")
    }

    testVariables = libraries.tests.prepareVersions([
        libraries             : libraries,
        release_versions      : release_versions,
        operator              : 'pxc-operator',
        platform              : 'doks',
        platform_provider     : 'doks',
        platform_version      : platformVersion,
        region                : DO_REGION,
        cluster_wide          : CLUSTER_WIDE,
        pillar_version        : PILLAR_VERSION,
        git_branch            : GIT_BRANCH,
        job_name              : JOB_NAME,
        db_tag                : DB_TAG,
        debug_tests           : DEBUG_TESTS,
        default_operator_image: "perconalab/percona-xtradb-cluster-operator:${GIT_BRANCH}",
        images: [
            IMAGE_OPERATOR    : IMAGE_OPERATOR,
            IMAGE_PXC         : IMAGE_PXC,
            IMAGE_PROXY       : IMAGE_PROXY,
            IMAGE_HAPROXY     : IMAGE_HAPROXY,
            IMAGE_BACKUP      : IMAGE_BACKUP,
            IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR,
            IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
            IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
            IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
            IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER
        ]
    ])

    PLATFORM_VER = testVariables.platform_version
    DB_TAG = testVariables.db_tag
    GIT_SHORT_COMMIT = testVariables.git_short_commit
    CLUSTER_NAME = testVariables.cluster_name
    PARAMS_HASH = testVariables.params_hash

    if (testVariables.images.IMAGE_PXC) {
        release = ("$PILLAR_VERSION" != "none") ? "RELEASE-" : ""
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.description = "$release$GIT_BRANCH-$PLATFORM_VER-$cw-" + testVariables.images.IMAGE_PXC.split(":")[1]
    }
}

void initTests() {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tests.initTests(tests, testVariables, [
        testSuite              : TEST_SUITE,
        testList               : TEST_LIST,
        ignorePreviousRun      : IGNORE_PREVIOUS_RUN,
        cloudSecretCredentialId: 'cloud-secret-file',
        secretFileMode         : '600'
    ])
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

    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.doks.createCluster([
        clusterName    : CLUSTER_NAME,
        clusterSuffix  : CLUSTER_SUFFIX,
        platformVersion: PLATFORM_VER,
        region         : DO_REGION
    ])
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
                withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
                    def testVars = testsLib.buildPxcTestVariables(
                        cluster_name: CLUSTER_NAME,
                        debug_tests: DEBUG_TESTS,
                        cluster_wide: CLUSTER_WIDE,
                        default_operator_image: "perconalab/percona-xtradb-cluster-operator:${GIT_BRANCH}",
                        images: testVariables.images
                    )
                    def exports = testsLib.getExportedVariablesForTests(testVars, clusterSuffix)
                    def testCmd = testsLib.defineTestCommand(testVars, testName)
                    sh """
                        cd source

                        ${exports}

                        mkdir -p e2e-tests/logs e2e-tests/reports
                        bash -o pipefail <<BASH
                        {
                            ${testCmd}
                        } 2>&1 | tee e2e-tests/logs/${testName}.log
BASH
                    """
                }
            }
            testsLib.pushArtifactFile("$GIT_BRANCH-$GIT_SHORT_COMMIT-$testName-$PLATFORM_VER-$DB_TAG-CW_$CLUSTER_WIDE-$PARAMS_HASH", GIT_SHORT_COMMIT)
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
            try {
                testsLib.pushLogFile(testName, [gitShortCommit: GIT_SHORT_COMMIT])
            } catch (logErr) {
                echo "Warning: failed to push log for $testName: ${logErr}"
            }
            echo "The $testName test was finished!"
        }
    }
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tools.kubernetesCleanupCluster("/tmp/${CLUSTER_NAME}-${CLUSTER_SUFFIX}")
    libraries.doks.shutdownCluster([
        clusterName  : CLUSTER_NAME,
        clusterSuffix: CLUSTER_SUFFIX,
        region       : DO_REGION
    ])
}

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \"$IMAGE_PXC\" ]] && echo $IMAGE_PXC | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '84', '80', '57'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository')
        string(name: 'GIT_REPO', defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator', description: 'percona-xtradb-cluster-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'Digital Ocean kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main')
        string(name: 'IMAGE_PXC', defaultValue: '', description: 'PXC image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0')
        string(name: 'IMAGE_PROXY', defaultValue: '', description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:main-proxysql')
        string(name: 'IMAGE_HAPROXY', defaultValue: '', description: 'PXC haproxy image: perconalab/percona-xtradb-cluster-operator:main-haproxy')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'Backup image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'PMM client image: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'PMM server image: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'DO_REGION', defaultValue: 'nyc1', description: 'Digital ocean region to use for cluster')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Run tests with debug')
    }
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 6, unit: 'HOURS')
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
                script {
                    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
                    libraries.tools.dockerBuildAndPush(
                        operatorImage: 'perconalab/percona-xtradb-cluster-operator',
                        branch       : GIT_BRANCH
                    )
                }
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
                        def clusterName = "cluster${i}"
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

            script {
                def libraries = load('cloud/common/libraries.groovy').loadLibraries()
                libraries.tests.makeReportJUnit(tests, testVariables)
                junit testResults: '*.xml', healthScaleFactor: 1.0
                archiveArtifacts '*.xml,*.txt'

                try {
                    def sendJobSlack = load "cloud/common/sendJobSlackNotification.groovy"
                    sendJobSlack.call(
                        tests: tests,
                        gitBranch: GIT_BRANCH,
                        platformVer: PLATFORM_VER,
                        clusterWide: CLUSTER_WIDE,
                        image: testVariables.images.IMAGE_PXC,
                        operatorImage: testVariables.images.IMAGE_OPERATOR
                    )
                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
                }

                clusters.each { shutdownCluster(it) }

                libraries.tools.dockerCleanupVolumes()
            }

            deleteDir()
        }
    }
}
