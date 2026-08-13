import groovy.transform.Field

@Field Integer numClusters = 5
@Field def tests = []
@Field def clusters = []
@Field def release_versions = "source/e2e-tests/release_versions"
@Field Map testVariables = [:]

void prepareAgent() {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()

    echo "=========================[ Installing tools on the Jenkins executor ]========================="
    libraries.dependencies.install()
    libraries.dependencies.installKuttl()
    libraries.dependencies.installEksctl()
}

void prepareNode() {
    checkout(scm)
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tools.gitResetWorkspace()
    libraries.tools.gitClone(
        branch: GIT_BRANCH,
        repo: GIT_REPO
    )

    prepareAgent()

    def platformVersion = "$PLATFORM_VER"
    if ("$PILLAR_VERSION" != "none" && (platformVersion.toLowerCase() in ["min", "max"])) {
        platformVersion = libraries.tests.getReleaseVersionsParam(release_versions, "PLATFORM_VER", "EKS_${platformVersion.toUpperCase()}")
    }

    testVariables = libraries.tests.prepareVersions([
        libraries             : libraries,
        release_versions      : release_versions,
        operator              : 'pg-operator',
        platform              : 'eks',
        platform_provider     : 'eks',
        platform_version      : platformVersion,
        region                : EKS_REGION,
        cluster_wide          : CLUSTER_WIDE,
        pillar_version        : PILLAR_VERSION,
        git_branch            : GIT_BRANCH,
        job_name              : JOB_NAME,
        db_tag                : DB_TAG,
        default_operator_image: "perconalab/percona-postgresql-operator:${GIT_BRANCH}",
        images: [
            IMAGE_OPERATOR  : IMAGE_OPERATOR,
            IMAGE_POSTGRESQL: IMAGE_POSTGRESQL,
            IMAGE_PGBOUNCER : IMAGE_PGBOUNCER,
            IMAGE_BACKREST  : IMAGE_BACKREST,
            IMAGE_PMM_CLIENT: IMAGE_PMM_CLIENT,
            IMAGE_PMM_SERVER: IMAGE_PMM_SERVER,
            IMAGE_UPGRADE   : IMAGE_UPGRADE
        ]
    ])

    PLATFORM_VER = testVariables.platform_version
    DB_TAG = testVariables.db_tag
    GIT_SHORT_COMMIT = testVariables.git_short_commit
    CLUSTER_NAME = testVariables.cluster_name
    PARAMS_HASH = testVariables.params_hash

    if (testVariables.images.IMAGE_POSTGRESQL) {
        release = ("$PILLAR_VERSION" != "none") ? "RELEASE-" : ""
        cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
        currentBuild.description = "$release$GIT_BRANCH-$PLATFORM_VER-$cw-" + testVariables.images.IMAGE_POSTGRESQL.split(":")[1]
    }
}

void initTests() {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.tests.initTests(tests, testVariables, [
        testSuite              : TEST_SUITE,
        testList               : TEST_LIST,
        ignorePreviousRun      : IGNORE_PREVIOUS_RUN,
        cloudSecretCredentialId: 'cloud-secret-file'
    ])

    stash includes: "source/**", name: "sourceFILES"
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
    libraries.eks.createCluster([
        clusterName    : CLUSTER_NAME,
        clusterSuffix  : CLUSTER_SUFFIX,
        platformVersion: PLATFORM_VER,
        region         : EKS_REGION,
        product        : 'pg-operator',
        hugepages      : true
    ])

    // Needed for the post/always cleanup, which runs on the main agent while
    // each parallel cluster stage runs on its own instance.
    stash includes: "cluster-${CLUSTER_SUFFIX}.yaml", name: "cluster-${CLUSTER_SUFFIX}-config"
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
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'eks-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    def extraEnvs = [SKIP_TEST_WARNINGS: SKIP_TEST_WARNINGS]
                    if (!testVariables.images.IMAGE_POSTGRESQL) {
                        extraEnvs['PG_VER'] = PG_VER
                    }

                    def testVars = testsLib.buildPgTestVariables(
                        cluster_name: CLUSTER_NAME,
                        cluster_wide: CLUSTER_WIDE,
                        default_operator_image: "perconalab/percona-postgresql-operator:${GIT_BRANCH}",
                        images: testVariables.images,
                        extra_envs: extraEnvs
                    )
                    def exports = testsLib.getExportedVariablesForTests(testVars, clusterSuffix)
                    def testCmd = testsLib.defineTestCommand(testVars, testName)
                    sh """
                        cd source

                        ${exports}

                        mkdir -p e2e-tests/logs
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

    try {
        unstash "cluster-${CLUSTER_SUFFIX}-config"
    } catch (err) {
        echo "Warning: could not unstash cluster-${CLUSTER_SUFFIX}-config: ${err}"
    }

    libraries.tools.kubernetesCleanupCluster("/tmp/${CLUSTER_NAME}-${CLUSTER_SUFFIX}")
    libraries.eks.shutdownCluster([
        clusterName  : CLUSTER_NAME,
        clusterSuffix: CLUSTER_SUFFIX,
        region       : EKS_REGION
    ])
}

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \"$IMAGE_POSTGRESQL\" ]] && echo $IMAGE_POSTGRESQL | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis', '19'], description: 'For release runs. PG version to test. Use -postgis to take PostGIS images from release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'GIT_REPO', defaultValue: 'https://github.com/percona/percona-postgresql-operator', description: 'percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'EKS kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'PG_VER', defaultValue: '', description: 'PG version')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg18-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbouncer18')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbackrest18')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-upgrade')
        string(name: 'EKS_REGION', defaultValue: 'eu-west-3', description: 'EKS region to use for cluster')
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
        timeout(time: 6, unit: 'HOURS')
        copyArtifactPermission('weekly-pgo');
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
                        operatorImage: 'perconalab/percona-postgresql-operator',
                        branch       : GIT_BRANCH,
                        buildCommand : 'make build'
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
            steps {
                script {
                    def agentLabel = params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    def parallelStages = [:]
                    for (int i = 1; i <= numClusters; i++) {
                        def clusterName = "cluster${i}"
                        parallelStages[clusterName] = {
                            stage(clusterName) {
                                node(agentLabel) {
                                    prepareAgent()
                                    unstash "sourceFILES"
                                    clusterRunner(clusterName)
                                }
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
                        image: testVariables?.images?.IMAGE_POSTGRESQL,
                        operatorImage: testVariables?.images?.IMAGE_OPERATOR
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
