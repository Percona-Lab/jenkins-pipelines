// CLOUD-1004 pilot: run several PSMDB e2e tests at the same time on each GKE cluster and compare
// with one test per cluster. Same flow as psmdbo_gke.groovy, with three differences:
// - NUM_CLUSTERS, TESTS_PER_CLUSTER and SLOT_START_DELAY are parameters;
// - IMAGE_OPERATOR is required and the job never builds or pushes an operator image;
// - CLUSTER_WIDE=YES gives each shared test its own cluster-wide operator (SHARED_CLUSTER=1).
import groovy.transform.Field

@Field List clusters = []
@Field Map libraries = [:]
@Field Map testVariables = [:]
@Field String sourceRepo = 'https://github.com/percona/percona-server-mongodb-operator'
@Field String operatorImage = 'perconalab/percona-server-mongodb-operator'

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

String jenkinsAgentLabel() {
    return params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
    }
    parameters {
        text(name: 'TEST_LIST', defaultValue: '', description: 'Tests to run, one per line. Empty: the release suite. Tests tagged ownCluster or locks in tests.yaml are kept apart (operator branches with select_tests.py placement).')
        string(name: 'NUM_CLUSTERS', defaultValue: '1', description: 'Number of GKE clusters')
        string(name: 'TESTS_PER_CLUSTER', defaultValue: '4', description: 'Tests running at the same time on each cluster. 1 gives the serial baseline.')
        choice(name: 'CLUSTER_WIDE', choices: ['NO', 'YES'], description: 'YES: each test that shares a cluster gets its own cluster-wide operator, which watches only its test namespace')
        string(name: 'SLOT_START_DELAY', defaultValue: '0', description: 'Seconds between the first test of each slot, so the startup load does not line up')
        choice(name: 'CLUSTER_LOAD', choices: ['YES', 'NO'], description: 'Sample kubectl top nodes during the run: load per test case in the Allure report, one line per cluster in the log')
        string(name: 'LOAD_HIGH_CPU', defaultValue: '90', description: 'Mark a cluster HIGH when its busiest node stays at this CPU % or more...')
        string(name: 'LOAD_HIGH_MEMORY', defaultValue: '70', description: '...or at this memory % or more...')
        string(name: 'LOAD_HIGH_AFTER', defaultValue: '120', description: '...for this many seconds without a break')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['YES', 'NO'], description: 'Ignore passed tests in previous run (run all)')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mongodb-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'GKE kubernetes version')
        choice(name: 'GKE_RELEASE_CHANNEL', choices: ['rapid', 'stable', 'regular', 'None'], description: 'GKE release channel')
        string(name: 'IMAGE_OPERATOR', defaultValue: 'perconalab/percona-server-mongodb-operator:main', description: 'Required. An existing operator image; this job does not build one.')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongod8.3')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-backup')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'ex: perconalab/fluentbit:main-logcollector')
        string(name: 'IMAGE_SEARCH', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongot')
        string(name: 'GKE_REGION', defaultValue: 'us-central1-a', description: 'GKE region to use for cluster')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Run tests with debug')
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
    }
    stages {
        stage('Init Workspace') {
            steps {
                script {
                    if (!IMAGE_OPERATOR?.trim()) {
                        error('IMAGE_OPERATOR is required: this job does not build or push an operator image')
                    }

                    deleteDir()
                    checkout scm
                    getLibraries()
                    libraries.tools.gitClone([
                        repo: sourceRepo,
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
                        'make',
                        'psmdb',
                        'gcloud'
                    )
                }
            }
        }

        stage('Prepare Test Variables') {
            steps {
                script {
                    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT')]) {
                        testVariables = libraries.tests.prepareVersions([
                            libraries               : libraries,
                            release_versions        : 'source/e2e-tests/release_versions',
                            operator                : 'psmdb',
                            platform                : 'gke',
                            platform_channel        : GKE_RELEASE_CHANNEL,
                            platform_version        : PLATFORM_VER,
                            platform_arch           : 'amd64',
                            zone                    : GKE_REGION,
                            cluster_wide            : CLUSTER_WIDE,
                            pillar_version          : 'none',
                            git_branch              : GIT_BRANCH,
                            source_repo             : sourceRepo,
                            job_name                : JOB_NAME,
                            debug_tests             : DEBUG_TESTS,
                            test_executor_type      : 'make',
                            allure                  : true,
                            default_operator_image  : "${operatorImage}:${GIT_BRANCH}",
                            clusters                : clusters,
                            numClusters             : NUM_CLUSTERS as Integer,
                            tests_per_cluster       : TESTS_PER_CLUSTER as Integer,
                            slot_start_delay_seconds: SLOT_START_DELAY as Integer,
                            cluster_load            : [
                                enabled            : CLUSTER_LOAD == 'YES',
                                interval_seconds   : 30,
                                cpu_percent        : LOAD_HIGH_CPU as Integer,
                                memory_percent     : LOAD_HIGH_MEMORY as Integer,
                                high_after_seconds : LOAD_HIGH_AFTER as Integer
                            ],
                            kubeconfigPath          : '/tmp',
                            retries                 : 1,
                            jenkins_agent_label     : libraries.tools.jenkinsAgentLabel(params),
                            extra_envs              : [
                                GCP_PROJECT            : GCP_PROJECT,
                                GCS_WI_SERVICE_ACCOUNT : "percona-psmdb-operator-wi@${GCP_PROJECT}.iam.gserviceaccount.com"
                            ],
                            images: [
                                IMAGE_OPERATOR    : IMAGE_OPERATOR,
                                IMAGE_MONGOD      : IMAGE_MONGOD,
                                IMAGE_BACKUP      : IMAGE_BACKUP,
                                IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                                IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                                IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR,
                                IMAGE_SEARCH      : IMAGE_SEARCH
                            ]
                        ])
                    }

                    currentBuild.displayName = "#${currentBuild.number} ${NUM_CLUSTERS}x${TESTS_PER_CLUSTER} ${GIT_BRANCH}"
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
                        testSuite         : 'release',
                        ignorePreviousRun : IGNORE_PREVIOUS_RUN,
                        operator          : 'psmdb'
                    ])
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
                libraries.tests.publishAllureReport(testVariables)
            }

            junit testResults: 'TestsReport.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
