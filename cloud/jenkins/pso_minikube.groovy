import groovy.transform.Field

@Field def tests = []
@Field def release_versions = "source/e2e-tests/release_versions"
@Field Map testVariables = [:]

void installTools() {
    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.dependencies.install()
    libraries.dependencies.installKuttl()

    sh """
        sudo curl -sLo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo chmod +x /usr/local/bin/minikube
    """
}

void prepareAgent() {
    checkout scm
    installTools()
}

void prepareNode() {
    checkout scm
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tools.gitResetWorkspace()
    libraries.tools.gitClone(
        branch: GIT_BRANCH,
        repo: 'https://github.com/percona/percona-server-mysql-operator'
    )

    def platformVersion = "$PLATFORM_VER"
    if ("$PILLAR_VERSION" != "none" && platformVersion.toLowerCase() == "max") {
        platformVersion = libraries.tests.getReleaseVersionsParam(release_versions, "PLATFORM_VER", "MINIKUBE_MAX")
    }

    testVariables = libraries.tests.prepareVersions([
        libraries             : libraries,
        release_versions      : release_versions,
        operator              : 'ps-operator',
        platform              : 'minikube',
        platform_provider     : 'minikube',
        platform_version      : platformVersion,
        cluster_wide          : CLUSTER_WIDE,
        pillar_version        : PILLAR_VERSION,
        git_branch            : GIT_BRANCH,
        job_name              : JOB_NAME,
        db_tag                : DB_TAG,
        test_executor_type    : 'kuttl',
        default_operator_image: "perconalab/percona-server-mysql-operator:${GIT_BRANCH}",
        images: [
            IMAGE_OPERATOR     : IMAGE_OPERATOR,
            IMAGE_MYSQL        : IMAGE_MYSQL,
            IMAGE_BACKUP       : IMAGE_BACKUP,
            IMAGE_ROUTER       : IMAGE_ROUTER,
            IMAGE_HAPROXY      : IMAGE_HAPROXY,
            IMAGE_ORCHESTRATOR : IMAGE_ORCHESTRATOR,
            IMAGE_TOOLKIT      : IMAGE_TOOLKIT,
            IMAGE_PMM_CLIENT   : IMAGE_PMM_CLIENT,
            IMAGE_PMM_SERVER   : IMAGE_PMM_SERVER,
            IMAGE_BINLOG_SERVER: IMAGE_BINLOG_SERVER
        ]
    ])

    PLATFORM_VER = testVariables.platform_version
    IMAGE_OPERATOR = testVariables.images.IMAGE_OPERATOR
    IMAGE_MYSQL = testVariables.images.IMAGE_MYSQL
    IMAGE_BACKUP = testVariables.images.IMAGE_BACKUP
    IMAGE_ROUTER = testVariables.images.IMAGE_ROUTER
    IMAGE_HAPROXY = testVariables.images.IMAGE_HAPROXY
    IMAGE_ORCHESTRATOR = testVariables.images.IMAGE_ORCHESTRATOR
    IMAGE_TOOLKIT = testVariables.images.IMAGE_TOOLKIT
    IMAGE_PMM_CLIENT = testVariables.images.IMAGE_PMM_CLIENT
    IMAGE_PMM_SERVER = testVariables.images.IMAGE_PMM_SERVER
    IMAGE_BINLOG_SERVER = testVariables.images.IMAGE_BINLOG_SERVER
    DB_TAG = testVariables.db_tag
    GIT_SHORT_COMMIT = testVariables.git_short_commit
    PARAMS_HASH = testVariables.params_hash

    if ("$IMAGE_MYSQL") {
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.displayName = "#" + currentBuild.number + " $GIT_BRANCH"
        currentBuild.description = "$PLATFORM_VER " + "$IMAGE_MYSQL".split(":")[1] + " $cw"
    }
}

void initTests() {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tests.initTests(tests, testVariables, [
        testSuite              : TEST_SUITE,
        testList               : TEST_LIST,
        ignorePreviousRun      : IGNORE_PREVIOUS_RUN,
        cloudSecretCredentialId: 'cloud-secret-file-ps',
        secretFileMode         : '600'
    ])
    stash includes: "source/**", name: "sourceFILES"
}

void clusterRunner(String cluster) {
    def clusterCreated = 0

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
}

void createCluster(String CLUSTER_SUFFIX) {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.minikube.createCluster([platformVersion: PLATFORM_VER])
}

void runTest(Integer TEST_ID) {
    def retryCount = 0
    def testName = tests[TEST_ID]["name"]

    waitUntil {
        def timeStart = new Date().getTime()
        def testsLib = load('cloud/common/vars/tests.groovy')
        try {
            echo "The $testName test was started !"
            tests[TEST_ID]["result"] = "failure"

            timeout(time: 90, unit: 'MINUTES') {
                def testVars = testsLib.buildPsTestVariables(
                    cluster_name: 'minikube',
                    skip_kubeconfig: true,
                    cluster_wide: CLUSTER_WIDE,
                    default_operator_image: "perconalab/percona-server-mysql-operator:${GIT_BRANCH}",
                    images: testVariables.images
                )
                def exports = testsLib.getExportedVariablesForTests(testVars, 'cluster1')
                def testCmd = testsLib.defineTestCommand(testVars, testName)
                sh """
                    cd source

                    ${exports}

                    ${testCmd}
                """
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
            echo "The $testName test was finished!"
        }
    }
}

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \$IMAGE_MYSQL ]] && echo \$IMAGE_MYSQL | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
        PMM_TELEMETRY_TOKEN = credentials('PMM-CHECK-DEV-TOKEN')
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-minikube.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '84', '80'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mysql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'Minikube kubernetes version. If set to max, value will be automatically taken from release_versions file.')
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
        string(name: 'IMAGE_BINLOG_SERVER', defaultValue: '', description: 'ex: perconalab/percona-binlog-server:0.2.1')
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
                prepareNode()
            }
        }
        stage('Docker Build and Push') {
            steps {
                script {
                    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
                    libraries.tools.dockerBuildAndPush(
                        operatorImage: 'perconalab/percona-server-mysql-operator',
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
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            parallel {
                stage('cluster1') {
                    agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
                    steps {
                        prepareAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    agent { label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb' }
                    steps {
                        prepareAgent()
                        unstash "sourceFILES"
                        clusterRunner('cluster2')
                    }
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
            }
            junit testResults: '*.xml', healthScaleFactor: 1.0
            archiveArtifacts '*.xml,*.txt'

            script {
                def libraries = load('cloud/common/libraries.groovy').loadLibraries()
                try {
                    def sendJobSlack = load "cloud/common/sendJobSlackNotification.groovy"
                    sendJobSlack.call(
                        tests: tests,
                        gitBranch: GIT_BRANCH,
                        platformVer: PLATFORM_VER,
                        clusterWide: CLUSTER_WIDE,
                        image: IMAGE_MYSQL,
                        operatorImage: IMAGE_OPERATOR
                    )

                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
                }

                libraries.tools.dockerCleanupVolumes()
            }
            deleteDir()
        }
    }
}
