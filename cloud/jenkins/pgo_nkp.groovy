import groovy.transform.Field

@Field Map libraries = [:]
@Field Map testVariables = [:]

pipeline {
    agent {
        label params.JENKINS_AGENT_LABEL
    }

    parameters {
        string(name: 'JENKINS_AGENT_LABEL', defaultValue: 'docker-x64-min', description: 'Jenkins agent label used to run the NKP tests')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'PG Operator branch or tag')
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-community.csv'], description: 'Suite used when TEST_LIST is empty')
        text(name: 'TEST_LIST', defaultValue: 'init-deploy', description: 'Test names, one per line; distributed once across the two clusters')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PLATFORM_VERSION', choices: ['v1.35.2'], description: 'Kubernetes version of the NKP lab (fixed)')
        string(name: 'PG_VERSION', defaultValue: '18', description: 'PostgreSQL major version')
        choice(name: 'CLUSTER_WIDE', choices: ['NO', 'YES'], description: 'Operator installation mode')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Enable test debugging')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['true', 'false'], description: 'Skip warnings requiring release documentation')
        string(name: 'IMAGE_OPERATOR', defaultValue: 'docker.io/perconalab/percona-postgresql-operator:main', description: 'Existing operator image; no image is built by this job')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'Optional PostgreSQL image override')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'Optional PgBouncer image override')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'Optional pgBackRest image override')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'Optional upgrade image override')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'Optional PMM client image override')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'Optional PMM server image override')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'Optional log collector image override')
    }

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 6, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {
        stage('Prepare Tests') {
            steps {
                script {
                    deleteDir()
                    checkout scm
                    libraries = load('cloud/common/libraries.groovy').loadLibraries()
                    libraries.tools.gitClone([
                        repo: 'https://github.com/percona/percona-postgresql-operator',
                        branch: params.GIT_BRANCH
                    ])
                    def kubeconfigPath = "/tmp/nkp-${env.BUILD_TAG.replaceAll(/[^a-zA-Z0-9._-]/, '-')}"
                    testVariables = libraries.tests.prepareVersions([
                        libraries: libraries,
                        operator: 'pg',
                        platform: 'nkp',
                        platform_provider: 'nkp',
                        platform_version: params.PLATFORM_VERSION,
                        platform_channel: 'existing',
                        cluster_wide: params.CLUSTER_WIDE,
                        pillar_version: 'none',
                        git_branch: params.GIT_BRANCH,
                        job_name: env.JOB_BASE_NAME,
                        db_version: params.PG_VERSION,
                        debug_tests: params.DEBUG_TESTS,
                        test_executor_type: 'kuttl',
                        default_operator_image: params.IMAGE_OPERATOR,
                        kubeconfigPath: kubeconfigPath,
                        numClusters: 2,
                        images: [
                            IMAGE_OPERATOR: params.IMAGE_OPERATOR,
                            IMAGE_POSTGRESQL: params.IMAGE_POSTGRESQL,
                            IMAGE_PGBOUNCER: params.IMAGE_PGBOUNCER,
                            IMAGE_BACKREST: params.IMAGE_BACKREST,
                            IMAGE_UPGRADE: params.IMAGE_UPGRADE,
                            IMAGE_PMM_CLIENT: params.IMAGE_PMM_CLIENT,
                            IMAGE_PMM_SERVER: params.IMAGE_PMM_SERVER,
                            IMAGE_LOGCOLLECTOR: params.IMAGE_LOGCOLLECTOR
                        ],
                        extra_envs: [PG_VER: params.PG_VERSION, PLATFORM: 'nkp', SKIP_TEST_WARNINGS: params.SKIP_TEST_WARNINGS]
                    ])
                    testVariables.tests = libraries.tests.loadTestList(params.TEST_LIST, params.TEST_SUITE)
                    if (!testVariables.tests) {
                        error('Select at least one PG test')
                    }

                    if ((params.IGNORE_PREVIOUS_RUN ?: 'NO') == 'NO') {
                        libraries.tests.updateListWithLastExecutionStatus(testVariables)
                    } else {
                        echo 'All tests will be re-run, ignoring previous execution results!'
                        libraries.tests.purgePreviousRunArtifacts(testVariables)
                    }

                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${params.GIT_BRANCH} NKP"
                    libraries.tests.loadCloudSecret('pg')
                    stash includes: 'cloud/**', name: 'pipelineFILES'
                    stash includes: 'source/**', name: 'sourceFILES', useDefaultExcludes: false
                }
            }
        }

        stage('Run Tests') {
            steps {
                script {
                    testVariables.jenkins_agent_label = params.JENKINS_AGENT_LABEL

                    lock(resource: 'nkp-percona-cluster-1', extra: [[resource: 'nkp-percona-cluster-2']]) {
                        parallel libraries.tests.buildParallelClusterStages(testVariables)
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                if (testVariables.tests) {
                    libraries.tests.makeReportJUnit(testVariables.tests, testVariables)
                }
            }
            junit testResults: 'TestsReport.xml', allowEmptyResults: true
            archiveArtifacts artifacts: 'TestsReport.xml,PipelineParameters.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}