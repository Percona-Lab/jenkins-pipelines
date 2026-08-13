import groovy.transform.Field

@Field Integer numClusters = 5
@Field List clusters = []
@Field Map libraries = [:]
@Field Map testVariables = [:]
@Field String sourceRepo = 'https://github.com/percona/percona-postgresql-operator'
@Field String operatorImage = 'docker.io/perconalab/percona-postgresql-operator'

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

String jenkinsAgentLabel() {
    return params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
}

pipeline {
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
        label jenkinsAgentLabel()
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 6, unit: 'HOURS')
        copyArtifactPermission('weekly-pgo');
    }
    stages {
        stage('Init Workspace') {
            steps {
                script {
                    deleteDir()
                    checkout scm
                    getLibraries()
                    libraries.tools.gitClone([
                        repo: params.GIT_REPO ?: sourceRepo,
                        branch: GIT_BRANCH
                    ])
                }
            }
        }

        stage('Prepare Node') {
            steps {
                script {
                    libraries.dependencies.prepareNode(
                        libraries,
                        'kuttl',
                        'pg-operator',
                        'eks'
                    )
                }
            }
        }

        stage('Docker Build and Push') {
            steps {
                script {
                    libraries.tools.dockerBuildAndPush(
                        operatorImage: operatorImage,
                        branch       : GIT_BRANCH,
                        buildCommand : 'make build'
                    )
                }
            }
        }

        stage('Prepare Test Variables') {
            steps {
                script {
                    def extraEnvs = [SKIP_TEST_WARNINGS: SKIP_TEST_WARNINGS]

                    testVariables = libraries.tests.prepareVersions([
                        libraries             : libraries,
                        release_versions      : 'source/e2e-tests/release_versions',
                        operator              : 'pg-operator',
                        platform              : 'eks',
                        platform_provider     : 'eks',
                        platform_version      : PLATFORM_VER,
                        region                : EKS_REGION,
                        hugepages             : true,
                        cluster_wide          : CLUSTER_WIDE,
                        pillar_version        : PILLAR_VERSION,
                        git_branch            : GIT_BRANCH,
                        source_repo           : params.GIT_REPO ?: sourceRepo,
                        job_name              : JOB_NAME,
                        db_version            : PG_VER,
                        test_executor_type    : 'kuttl',
                        default_operator_image: "${operatorImage}:${GIT_BRANCH}",
                        extra_envs            : extraEnvs,
                        clusters              : clusters,
                        numClusters           : numClusters,
                        kubeconfigPath        : '/tmp',
                        retries               : 1,
                        jenkins_agent_label   : jenkinsAgentLabel(),
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

                    if (!testVariables.images.IMAGE_POSTGRESQL) {
                        testVariables.extra_envs.PG_VER = PG_VER
                    }

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

                    stash includes: 'source/**', name: 'sourceFILES', useDefaultExcludes: false
                }
            }
        }

        stage('Run Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                script {
                    parallel libraries.tests.buildParallelClusterStages(testVariables)
                }
            }
        }
    }
    post {
        always {
            script {
                echo "CLUSTER ASSIGNMENTS\n" +
                    (testVariables.tests ?: []).toString()
                        .replace('], ', ']\n')
                        .replace(']]', ']')
                        .replaceFirst('\\[', '')

                if (testVariables.tests) {
                    libraries.tests.makeReportJUnit(testVariables.tests, testVariables)
                }

                try {
                    def sendJobSlack = load('cloud/common/sendJobSlackNotification.groovy')
                    sendJobSlack.call(
                        tests        : testVariables.tests,
                        gitBranch    : GIT_BRANCH,
                        platformVer  : testVariables.platform_version,
                        clusterWide  : testVariables.cluster_wide,
                        image        : testVariables.images?.IMAGE_POSTGRESQL,
                        operatorImage: testVariables.images?.IMAGE_OPERATOR
                    )
                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
                }

                clusters.each { clusterSuffix ->
                    try {
                        libraries.eks.shutdownCluster([
                            clusterName  : testVariables.cluster_name,
                            clusterSuffix: clusterSuffix,
                            region       : testVariables.region
                        ])
                    } catch (err) {
                        echo "Cleanup failed for ${clusterSuffix}: ${err}"
                    }
                }

                libraries.tools.dockerCleanupVolumes()
            }

            junit testResults: 'TestsReport.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
