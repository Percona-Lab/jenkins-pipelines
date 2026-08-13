import groovy.transform.Field

@Field def tests = []
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
    libraries.dependencies.installKuttl()

    sh """
        sudo curl -sLo /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo chmod +x /usr/local/bin/minikube
    """

    def platformVersion = "$PLATFORM_VER"
    if ("$PILLAR_VERSION" != "none" && platformVersion.toLowerCase() == "max") {
        platformVersion = libraries.tests.getReleaseVersionsParam(release_versions, "PLATFORM_VER", "MINIKUBE_MAX")
    }

    testVariables = libraries.tests.prepareVersions([
        libraries             : libraries,
        release_versions      : release_versions,
        operator              : 'pg-operator',
        platform              : 'minikube',
        platform_provider     : 'minikube',
        platform_version      : platformVersion,
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
}

void clusterRunner(String cluster) {
    def libraries = load('cloud/common/libraries.groovy').loadLibraries()
    libraries.minikube.createCluster([platformVersion: PLATFORM_VER])

    for (int i=0; i<tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            runTest(i)
        }
    }
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
                def extraEnvs = [SKIP_TEST_WARNINGS: SKIP_TEST_WARNINGS]
                if (!testVariables.images.IMAGE_POSTGRESQL) {
                    extraEnvs['PG_VER'] = PG_VER
                }

                def testVars = testsLib.buildPgTestVariables(
                    cluster_name: 'minikube',
                    skip_kubeconfig: true,
                    cluster_wide: CLUSTER_WIDE,
                    default_operator_image: "perconalab/percona-postgresql-operator:${GIT_BRANCH}",
                    images: testVariables.images,
                    extra_envs: extraEnvs
                )
                def exports = testsLib.getExportedVariablesForTests(testVars, 'cluster1')
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

pipeline {
    environment {
        DB_TAG = sh(script: "[[ \"$IMAGE_POSTGRESQL\" ]] && echo $IMAGE_POSTGRESQL | awk -F':' '{print \$2}' || echo main", returnStdout: true).trim()
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-minikube.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis'], description: 'For release runs. PG version to test. Use -postgis to take PostGIS images from release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'GIT_REPO', defaultValue: 'https://github.com/percona/percona-postgresql-operator', description: 'percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'Minikube kubernetes version. If set to max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'PG_VER', defaultValue: '', description: 'PG version')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg18-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbouncer18')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbackrest18')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-upgrade')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that requires release documentation')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
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
                clusterRunner('cluster1')
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
            }

            sh """
                minikube delete || true
            """
            deleteDir()
        }
    }
}
