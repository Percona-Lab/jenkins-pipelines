import groovy.transform.Field

@Field Integer numClusters = 8
@Field List clusters = []

@Field Map libraries = [:]
@Field Map testVariables = [:]
@Field String sourceRepo = 'https://github.com/percona/percona-server-mysql-operator'
@Field String operatorImage = 'docker.io/perconalab/percona-server-mysql-operator'

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

pipeline {
    environment {
        DB_TAG = sh(
            script: '''[[ "$IMAGE_MYSQL" ]] && echo "$IMAGE_MYSQL" | awk -F':' '{print $2}' || echo main''',
            returnStdout: true
        ).trim()
    }

    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run')
        choice(name: 'PILLAR_VERSION', choices: ['none', '84', '80'], description: 'Set to 80/84 for a release run. Release runs force PLATFORM_CHANNEL=stable and load images from source/e2e-tests/release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch')
        choice(name: 'PLATFORM_CHANNEL', choices: ['stable', 'latest', 'testing'], description: 'Used when PLATFORM_VERSION=latest. Release runs override this to stable.')
        string(name: 'PLATFORM_VERSION', defaultValue: 'latest', description: 'RKE2/Kubernetes version. Use latest to resolve from PLATFORM_CHANNEL, min to use RKE2_MIN from release_versions, max to use RKE2_MAX from release_versions, or pass an explicit version.')
        choice(name: 'PLATFORM_ARCH', choices: ['amd64', 'arm64'], description: 'Platform architecture used to select the machine type.')
        string(name: 'RANCHER_VERSION', defaultValue: 'latest', description: 'Rancher chart version. In release runs, latest or empty is replaced with RANCHER from source/e2e-tests/release_versions.')
        string(name: 'RANCHER_ZONE', defaultValue: 'us-central1-a', description: 'Google zone to schedule Rancher instances')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster-wide mode')

        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main')
        string(name: 'IMAGE_MYSQL', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-psmysql8.0')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-backup8.0')
        string(name: 'IMAGE_ROUTER', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-router8.0')
        string(name: 'IMAGE_HAPROXY', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-haproxy')
        string(name: 'IMAGE_ORCHESTRATOR', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-orchestrator')
        string(name: 'IMAGE_TOOLKIT', defaultValue: '', description: 'ex: perconalab/percona-server-mysql-operator:main-toolkit')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_BINLOG_SERVER', defaultValue: '', description: 'ex: perconalab/percona-binlog-server:0.2.1')

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
                        branch: GIT_BRANCH,
                        operator: 'ps-operator'
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
                        db_tag                : DB_TAG,
                        debug_tests           : DEBUG_TESTS,
                        test_executor_type    : 'kuttl',

                        default_operator_image: "${operatorImage}:${GIT_BRANCH}",

                        images: [
                            IMAGE_OPERATOR    : IMAGE_OPERATOR,
                            IMAGE_MYSQL       : IMAGE_MYSQL,
                            IMAGE_BACKUP      : IMAGE_BACKUP,
                            IMAGE_ROUTER      : IMAGE_ROUTER,
                            IMAGE_HAPROXY     : IMAGE_HAPROXY,
                            IMAGE_ORCHESTRATOR: IMAGE_ORCHESTRATOR,
                            IMAGE_TOOLKIT     : IMAGE_TOOLKIT,
                            IMAGE_PMM_CLIENT  : IMAGE_PMM_CLIENT,
                            IMAGE_PMM_SERVER  : IMAGE_PMM_SERVER,
                            IMAGE_PMM3_CLIENT : IMAGE_PMM3_CLIENT,
                            IMAGE_PMM3_SERVER : IMAGE_PMM3_SERVER,
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
                    testVariables.tests = libraries.tests.loadTestList(TEST_LIST, TEST_SUITE)

                    if (IGNORE_PREVIOUS_RUN == 'NO') {
                        libraries.tests.updateListWithLastExecutionStatus(testVariables)
                    } else {
                        echo 'All tests will be re-run, ignoring previous execution results!'
                    }

                    libraries.tests.loadCloudSecret('ps')
                    // Stash cloned files to use in parallel stages with different nodes
                    libraries.tools.stashClonedGitFiles()
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
                    testVariables.jenkins_agent_label = params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'

                    // Creates clusters in parallel and runs tests in parallel on each cluster
                    parallel libraries.tests.buildParallelClusterStages(testVariables)
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

                libraries.tests.makeReportJUnit(testVariables.tests, testVariables)

                try {
                    def sendJobSlack = load('cloud/common/sendJobSlackNotification.groovy')

                    sendJobSlack.call(
                        tests          : testVariables.tests,
                        gitBranch      : GIT_BRANCH,
                        platformVer    : testVariables.platform_version,
                        platformChannel: testVariables.platform_channel,
                        platformArch   : testVariables.platform_arch,
                        clusterWide    : testVariables.cluster_wide,
                        image          : testVariables.images.IMAGE_MYSQL,
                        operatorImage  : testVariables.images.IMAGE_OPERATOR
                    )
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

            junit testResults: 'TestsReport.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
