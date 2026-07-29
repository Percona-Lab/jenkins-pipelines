import groovy.transform.Field

@Field Integer numClusters = 4
@Field List clusters = []

@Field Map libraries = [:]
@Field Map testVariables = [:]

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

pipeline {
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis'], description: 'Set PG version for a release run. Release runs force PLATFORM_CHANNEL=stable and load images from source/e2e-tests/release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch')
        choice(name: 'PLATFORM_CHANNEL', choices: ['stable', 'latest', 'testing'], description: 'Used when PLATFORM_VERSION=latest. Release runs override this to stable.')
        string(name: 'PLATFORM_VERSION', defaultValue: 'latest', description: 'RKE2/Kubernetes version. Use latest to resolve from PLATFORM_CHANNEL, min to use RKE2_MIN from release_versions, max to use RKE2_MAX from release_versions, or pass an explicit version.')
        choice(name: 'PLATFORM_ARCH', choices: ['amd64', 'arm64'], description: 'Platform architecture used to select the machine type.')
        string(name: 'RANCHER_VERSION', defaultValue: 'latest', description: 'Rancher chart version. In release runs, latest or empty is replaced with RANCHER from source/e2e-tests/release_versions.')
        string(name: 'RANCHER_ZONE', defaultValue: 'us-central1-a', description: 'Google zone to schedule Rancher instances')
        string(name: 'PG_VERSION', defaultValue: '', description: 'PostgreSQL version used to generate DB_TAG and select release image keys, for example 14, 15, 16, 17, or 18.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster-wide mode')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that require release documentation')

        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-ppg18-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pgbouncer18')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pgbackrest18')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'Example: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'Example: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'Example: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'Example: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-upgrade')

        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Enable debug mode for tests')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Jenkins agent provider')
    }

    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
    }

    options {
        buildDiscarder(logRotator(
            daysToKeepStr: '-1',
            artifactDaysToKeepStr: '-1',
            numToKeepStr: '30',
            artifactNumToKeepStr: '30'
        ))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 6, unit: 'HOURS')
    }

    stages {
        stage('Init Workspace') {
            steps {
                script {
                    deleteDir()
                    checkout scm
                    getLibraries()
                }
            }
        }

        stage('Prepare Node') {
            steps {
                script {
                    libraries.tools.gitClone(
                        branch: GIT_BRANCH,
                        repo: 'https://github.com/percona/percona-postgresql-operator'
                    )

                    libraries.dependencies.install()
                    libraries.dependencies.installKuttlAndAssert()
                    libraries.dependencies.installGoogleCLI()
                    libraries.dependencies.installAzureCLI()

                    libraries.gcloud.auth()
                    libraries.azure.auth()
                }
            }
        }

        stage('Docker Build and Push') {
            steps {
                script {
                    libraries.tools.dockerBuildAndPush(
                        operatorImage: 'perconalab/percona-postgresql-operator',
                        branch: GIT_BRANCH
                    )
                }
            }
        }

        stage('Prepare Test Variables') {
            steps {
                script {
                    def extraEnvs = [
                        SKIP_TEST_WARNINGS: SKIP_TEST_WARNINGS,
                        PG_VER: PG_VERSION
                    ]

                    testVariables = libraries.tests.prepareVersions([
                        libraries             : libraries,
                        release_versions      : 'source/e2e-tests/release_versions',
                        operator              : 'pg-operator',

                        platform              : 'rke2',
                        platform_provider     : 'rancher',
                        platform_channel      : PLATFORM_CHANNEL,
                        platform_version      : PLATFORM_VERSION,
                        platform_arch         : PLATFORM_ARCH,
                        rancher_version       : RANCHER_VERSION,
                        worker_count          : 4,
                        zone                  : RANCHER_ZONE,

                        cluster_wide          : CLUSTER_WIDE,
                        pillar_version        : PILLAR_VERSION,

                        git_branch            : GIT_BRANCH,
                        job_name              : JOB_NAME,
                        db_version            : PG_VERSION,
                        debug_tests           : DEBUG_TESTS,
                        test_executor_type    : 'kuttl',

                        default_operator_image: "perconalab/percona-postgresql-operator:${GIT_BRANCH}",

                        images: [
                            IMAGE_OPERATOR    : IMAGE_OPERATOR,
                            IMAGE_POSTGRESQL  : IMAGE_POSTGRESQL,
                            IMAGE_PGBOUNCER   : IMAGE_PGBOUNCER,
                            IMAGE_BACKREST    : IMAGE_BACKREST,
                            IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                            IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                            IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
                            IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER,
                            IMAGE_UPGRADE     : IMAGE_UPGRADE
                        ],

                        extra_envs: extraEnvs
                    ])

                    currentBuild.displayName = "#${currentBuild.number} ${GIT_BRANCH}"
                    currentBuild.description = libraries.tests.buildJobDescription(testVariables)
                    libraries.tests.printTestVariables(testVariables)
                }
            }
        }

        stage('Init Tests') {
            steps {
                script {
                    testVariables.tests = libraries.tests.loadTestList(TEST_LIST, TEST_SUITE)

                    if (IGNORE_PREVIOUS_RUN == 'NO') {
                        libraries.tests.updateListWithLastExecutionStatus(testVariables)
                    } else {
                        echo 'All tests will be re-run, ignoring previous execution results!'
                    }

                    libraries.tests.loadCloudSecret(testVariables.operator)
                }
            }
        }

        stage('Run Tests') {
            steps {
                script {
                    testVariables.clusters = clusters
                    testVariables.numClusters = numClusters
                    testVariables.kubeconfigPath = '/tmp'
                    testVariables.retries = 1

                    parallel libraries.tests.getParallelStages(testVariables)
                }
            }
        }
    }

    post {
        always {
            script {
                echo "CLUSTER ASSIGNMENTS\n" +
                    testVariables.tests.toString()
                        .replace('], ', ']\n')
                        .replace(']]', ']')
                        .replaceFirst('\\[', '')

                libraries.tests.makeReport(testVariables.tests, testVariables)

                try {
                    def sendJobSlack = load('cloud/common/sendJobSlackNotification.groovy')

                    // sendJobSlack.call(
                    //     tests          : testVariables.tests,
                    //     gitBranch      : GIT_BRANCH,
                    //     platformVer    : testVariables.platform_version,
                    //     platformChannel: testVariables.platform_channel,
                    //     platformArch   : testVariables.platform_arch,
                    //     clusterWide    : testVariables.cluster_wide,
                    //     image          : testVariables.images.IMAGE_POSTGRESQL,
                    //     operatorImage  : testVariables.images.IMAGE_OPERATOR
                    // )
                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
                }

                clusters.each { clusterSuffix ->
                    try {
                        def clusterCfg = [
                            clusterName  : testVariables.cluster_name,
                            clusterSuffix: clusterSuffix,
                            projectId    : testVariables.project_id,
                            zone         : RANCHER_ZONE,
                            kubeconfig   : "/tmp/${testVariables.cluster_name}-${clusterSuffix}"
                        ]

                        libraries.tools.kubernetesCleanupCluster(clusterCfg.kubeconfig)
                        libraries.rancher.shutdownCluster(clusterCfg)
                    } catch (err) {
                        echo "Cleanup failed for ${clusterSuffix}: ${err}"
                    }
                }

                libraries.tools.dockerCleanupVolumes()
            }

            junit testResults: '*.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
