import groovy.transform.Field

@Field Integer numClusters = 8
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

Map pgExtraEnvs() {
    def communityRun = PILLAR_VERSION.endsWith('-community')
    if (PILLAR_VERSION == '19-community' && UBI_VERSION != 'UBI9') {
        error('PostgreSQL 19 community images support UBI9 only.')
    }

    return [
        SKIP_TEST_WARNINGS: SKIP_TEST_WARNINGS,
        PG_VER            : communityRun ? PILLAR_VERSION.replace('-community', '') : PG_VER,
        PG_DISTRIBUTION   : communityRun ? 'community' : ''
    ]
}

pipeline {
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-community.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '14-community', '15', '15-postgis', '15-community', '16', '16-postgis', '16-community', '17', '17-postgis', '17-community', '18', '18-postgis', '18-community', '19-community'], description: 'For release runs. PG version to test. Use -postgis or -community to select those images from release_versions. PostgreSQL 19 community supports UBI9 only.')
        choice(name: 'UBI_VERSION', choices: ['UBI9', 'UBI8', 'UBI10'], description: 'Base image for official, PostGIS, and community pillars. Official/PostGIS UBI9 uses unsuffixed keys; community is UBI8/UBI9 only.')
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
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-logcollector')
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
        copyArtifactPermission('weekly-pgo')
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
                        'pg',
                        'eks'
                    )
                }
            }
        }

        stage('Prepare Test Variables') {
            steps {
                script {
                    testVariables = libraries.tests.prepareVersions([
                        libraries             : libraries,
                        release_versions      : 'source/e2e-tests/release_versions',
                        operator              : 'pg',
                        platform              : 'eks',
                        platform_provider     : 'eks',
                        platform_version      : PLATFORM_VER,
                        region                : EKS_REGION,
                        hugepages             : true,
                        cluster_wide          : CLUSTER_WIDE,
                        pillar_version        : PILLAR_VERSION,
                        ubi_version           : UBI_VERSION,
                        git_branch            : GIT_BRANCH,
                        source_repo           : params.GIT_REPO ?: sourceRepo,
                        job_name              : JOB_NAME,
                        db_version            : PG_VER,
                        test_executor_type    : 'kuttl',
                        default_operator_image: "${operatorImage}:${GIT_BRANCH}",
                        extra_envs            : pgExtraEnvs(),
                        clusters              : clusters,
                        numClusters           : numClusters,
                        kubeconfigPath        : '/tmp',
                        retries               : 1,
                        jenkins_agent_label   : libraries.tools.jenkinsAgentLabel(params),
                        images: [
                            IMAGE_OPERATOR    : IMAGE_OPERATOR,
                            IMAGE_POSTGRESQL  : IMAGE_POSTGRESQL,
                            IMAGE_PGBOUNCER   : IMAGE_PGBOUNCER,
                            IMAGE_BACKREST    : IMAGE_BACKREST,
                            IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                            IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                            IMAGE_UPGRADE     : IMAGE_UPGRADE,
                            IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR
                        ]
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
                    libraries.tests.initTestRun(testVariables, [
                        testList          : TEST_LIST,
                        testSuite         : TEST_SUITE,
                        ignorePreviousRun : IGNORE_PREVIOUS_RUN,
                        operator          : 'pg'
                    ])
                }
            }
        }

        stage('Docker Build and Push') {
            steps {
                script {
                    if (!libraries.tests.hasPendingTests(testVariables)) {
                        echo 'SKIP: no pending tests, the operator image is not needed.'
                        return
                    }

                    env.IMAGE_OPERATOR = testVariables.images.IMAGE_OPERATOR
                    libraries.tools.dockerBuildAndPush(
                        operatorImage: operatorImage,
                        branch       : GIT_BRANCH,
                        operator     : 'pg'
                    )
                }
            }
        }

        stage('Run Tests') {
            options {
                timeout(time: 4, unit: 'HOURS')
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
                libraries.tests.finalizeJob(testVariables)
            }

            junit testResults: 'TestsReport.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
