import groovy.transform.Field

@Field Integer numClusters = 8
@Field List clusters = []
@Field Map libraries = [:]
@Field Map testVariables = [:]
@Field String sourceRepo = 'https://github.com/percona/percona-server-mongodb-operator'
@Field String operatorImage = 'docker.io/perconalab/percona-server-mongodb-operator'

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

String jenkinsAgentLabel() {
    return params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv', 'run-backups.csv'], description: 'Choose test suite from file')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run')
        choice(name: 'PILLAR_VERSION', choices: ['none', '80', '83', '70', '60'], description: 'Set to 60/70/80/83 for a release run. Release runs force PLATFORM_CHANNEL=stable and load images from source/e2e-tests/release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch')
        choice(name: 'PLATFORM_CHANNEL', choices: ['stable', 'latest', 'testing'], description: 'Used when PLATFORM_VERSION=latest. Release runs override this to stable.')
        string(name: 'PLATFORM_VERSION', defaultValue: 'latest', description: 'RKE2/Kubernetes version. Use latest to resolve from PLATFORM_CHANNEL, min to use RKE2_MIN from release_versions, max to use RKE2_MAX from release_versions, or pass an explicit version.')
        string(name: 'PLATFORM_ARCH', defaultValue: 'amd64', description: 'Platform architecture used to select the machine type, for example amd64 or arm64.')
        string(name: 'RANCHER_VERSION', defaultValue: 'latest', description: 'Rancher chart version. In release runs, latest or empty is replaced with RANCHER from source/e2e-tests/release_versions.')
        string(name: 'RANCHER_ZONE', defaultValue: 'us-central1-a', description: 'Google zone to schedule Rancher instances')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster-wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongod8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-backup')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'ex: perconalab/fluentbit:main-logcollector')
        string(name: 'IMAGE_SEARCH', defaultValue: '', description: 'ex: perconalab/percona-server-mongodb-operator:main-mongot')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Enable debug mode for tests')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Jenkins agent provider')
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
                        'rancher'
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
                    testVariables = libraries.tests.prepareVersions([
                        libraries             : libraries,
                        release_versions      : 'source/e2e-tests/release_versions',
                        operator              : 'psmdb-operator',
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
                        source_repo           : sourceRepo,
                        job_name              : JOB_NAME,
                        debug_tests           : DEBUG_TESTS,
                        test_executor_type    : 'make',
                        default_operator_image: "${operatorImage}:${GIT_BRANCH}",
                        clusters              : clusters,
                        numClusters           : numClusters,
                        kubeconfigPath        : '/tmp',
                        retries               : 1,
                        jenkins_agent_label   : libraries.tools.jenkinsAgentLabel(params, 'min-al2023-x64'),
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
