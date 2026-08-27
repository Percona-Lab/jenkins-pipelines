import groovy.transform.Field

@Field Integer numClusters = 8
@Field List clusters = []
@Field Map libraries = [:]
@Field Map testVariables = [:]
@Field String sourceRepo = 'https://github.com/percona/percona-xtradb-cluster-operator'
@Field String operatorImage = 'perconalab/percona-xtradb-cluster-operator'

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

String jenkinsAgentLabel() {
    return params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
}

String getLocation(String job_name) {
    if ("$job_name" == 'pxco-aks-1') {
        return 'eastus'
    } else {
        return 'norwayeast'
    }
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
    }
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '84', '80', '57'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'AKS kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'Operator image: perconalab/percona-xtradb-cluster-operator:main')
        string(name: 'IMAGE_PXC', defaultValue: '', description: 'PXC image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0')
        string(name: 'IMAGE_PROXY', defaultValue: '', description: 'PXC proxy image: perconalab/percona-xtradb-cluster-operator:main-proxysql')
        string(name: 'IMAGE_HAPROXY', defaultValue: '', description: 'PXC haproxy image: perconalab/percona-xtradb-cluster-operator:main-haproxy')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'Backup image: perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'PXC logcollector image: perconalab/percona-xtradb-cluster-operator:main-logcollector')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'PMM client image: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'PMM server image: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'AKS_LOCATION', defaultValue: '', description: 'AKS location to use for cluster. By default "eastus" is for aks-1 job and "norwayeast" for aks-2')
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
        copyArtifactPermission('pxc-operator-latest-scheduler');
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
                        '',
                        'pxc-operator',
                        'azure'
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
                        operator              : 'pxc-operator',
                        platform              : 'aks',
                        platform_provider     : 'azure',
                        platform_version      : PLATFORM_VER,
                        region                : params.AKS_LOCATION ?: getLocation(JOB_NAME),
                        cluster_wide          : CLUSTER_WIDE,
                        pillar_version        : PILLAR_VERSION,
                        git_branch            : GIT_BRANCH,
                        source_repo           : sourceRepo,
                        job_name              : JOB_NAME,
                        debug_tests           : DEBUG_TESTS,
                        default_operator_image: "${operatorImage}:${GIT_BRANCH}",
                        clusters              : clusters,
                        numClusters           : numClusters,
                        kubeconfigPath        : '/tmp',
                        retries               : 1,
                        jenkins_agent_label   : libraries.tools.jenkinsAgentLabel(params),
                        images: [
                            IMAGE_OPERATOR    : IMAGE_OPERATOR,
                            IMAGE_PXC         : IMAGE_PXC,
                            IMAGE_PROXY       : IMAGE_PROXY,
                            IMAGE_HAPROXY     : IMAGE_HAPROXY,
                            IMAGE_BACKUP      : IMAGE_BACKUP,
                            IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR,
                            IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                            IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                            IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
                            IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER
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
                        operator          : 'pxc'
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
