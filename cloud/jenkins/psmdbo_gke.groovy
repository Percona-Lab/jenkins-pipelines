import groovy.transform.Field

@Field Integer numClusters = 8
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
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv', 'run-backups.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'ARCH', choices: ['amd64', 'arm64'], description: 'Architecture')
        choice(name: 'PILLAR_VERSION', choices: ['none', '80', '83', '70', '60'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mongodb-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'GKE kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'GKE_RELEASE_CHANNEL', choices: ['rapid', 'stable', 'regular', 'None'], description: 'GKE release channel. Will be forced to stable for release run.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongod8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-backup')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
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
        copyArtifactPermission('psmdb-operator-latest-scheduler');
    }
    stages {
        stage('Init Workspace') {
            steps {
                script {
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
                        'psmdb-operator',
                        'gcloud'
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
                        platform     : 'linux/amd64,linux/arm64'
                    )
                }
            }
        }

        stage('Prepare Test Variables') {
            steps {
                script {
                    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT')]) {
                        testVariables = libraries.tests.prepareVersions([
                            libraries             : libraries,
                            release_versions      : 'source/e2e-tests/release_versions',
                            operator              : 'psmdb-operator',
                            platform              : 'gke',
                            platform_provider     : 'gcloud',
                            platform_channel      : GKE_RELEASE_CHANNEL,
                            platform_version      : PLATFORM_VER,
                            platform_arch         : ARCH,
                            zone                  : GKE_REGION,
                            cluster_wide          : CLUSTER_WIDE,
                            pillar_version        : PILLAR_VERSION,
                            git_branch            : GIT_BRANCH,
                            source_repo           : sourceRepo,
                            job_name              : JOB_NAME,
                            debug_tests           : DEBUG_TESTS,
                            test_executor_type    : 'make',
                            default_operator_image: "${operatorImage}:${GIT_BRANCH}",
                            clusters              : clusters,
                            numClusters           : numClusters,
                            kubeconfigPath        : '/tmp',
                            retries               : 1,
                            jenkins_agent_label   : libraries.tools.jenkinsAgentLabel(params),
                            extra_envs            : [
                                GCP_PROJECT            : GCP_PROJECT,
                                GCS_WI_SERVICE_ACCOUNT : "percona-psmdb-operator-wi@${GCP_PROJECT}.iam.gserviceaccount.com"
                            ],
                            images: [
                                IMAGE_OPERATOR    : IMAGE_OPERATOR,
                                IMAGE_MONGOD      : IMAGE_MONGOD,
                                IMAGE_BACKUP      : IMAGE_BACKUP,
                                IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                                IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                                IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
                                IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER,
                                IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR,
                                IMAGE_SEARCH      : IMAGE_SEARCH
                            ]
                        ])
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
                    libraries.tests.initTestRun(testVariables, [
                        testList          : TEST_LIST,
                        testSuite         : TEST_SUITE,
                        ignorePreviousRun : IGNORE_PREVIOUS_RUN,
                        operator          : 'psmdb'
                    ])
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
                libraries.tests.finalizeJob(testVariables)
            }

            junit testResults: 'TestsReport.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
