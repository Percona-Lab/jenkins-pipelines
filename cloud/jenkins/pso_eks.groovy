import groovy.transform.Field

@Field Integer numClusters = 8
@Field List clusters = []
@Field Map libraries = [:]
@Field Map testVariables = [:]
@Field String sourceRepo = 'https://github.com/percona/percona-server-mysql-operator'
@Field String operatorImage = 'perconalab/percona-server-mysql-operator'

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

String jenkinsAgentLabel() {
    return params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
}

pipeline {
    environment {
        PMM_TELEMETRY_TOKEN = credentials('PMM-CHECK-DEV-TOKEN')
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '84', '80'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-server-mysql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'EKS kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
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
        string(name: 'EKS_REGION', defaultValue: 'eu-west-2', description: 'EKS region to use for cluster')
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
        copyArtifactPermission('weekly-pso');
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
                        'kuttl',
                        'ps-operator',
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
                        branch       : GIT_BRANCH
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
                        operator              : 'ps-operator',
                        platform              : 'eks',
                        platform_provider     : 'eks',
                        platform_version      : PLATFORM_VER,
                        region                : EKS_REGION,
                        cluster_wide          : CLUSTER_WIDE,
                        pillar_version        : PILLAR_VERSION,
                        git_branch            : GIT_BRANCH,
                        source_repo           : sourceRepo,
                        job_name              : JOB_NAME,
                        test_executor_type    : 'kuttl',
                        default_operator_image: "${operatorImage}:${GIT_BRANCH}",
                        clusters              : clusters,
                        numClusters           : numClusters,
                        kubeconfigPath        : '/tmp',
                        retries               : 1,
                        jenkins_agent_label   : libraries.tools.jenkinsAgentLabel(params),
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
                        operator          : 'ps'
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
