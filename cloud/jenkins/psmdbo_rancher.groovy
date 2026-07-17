import groovy.transform.Field

@Field Integer numClusters = 8
@Field List clusters = []

@Field Map libraries = [:]
@Field Map testVariables = [:]
@Field String clusterType = "rancher"

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

pipeline {
    environment {
        DB_TAG = sh(
            script: '''[[ "$IMAGE_MONGOD" ]] && echo "$IMAGE_MONGOD" | awk -F':' '{print $2}' || echo main''',
            returnStdout: true
        ).trim()
    }

    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv', 'run-backups.csv'], description: 'Choose test suite from file')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run')
        choice(name: 'PILLAR_VERSION', choices: ['none', '80', '70', '60'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch')
        choice(name: 'PLATFORM_CHANNEL', choices: ['stable', 'latest', 'testing'], description: '')
        string(name: 'PLATFORM_VERSION', defaultValue: 'latest', description: 'Kubernetes version')
        string(name: 'PLATFORM_ARCH', defaultValue: 'amd64', description: 'Platform architecture')
        string(name: 'RANCHER_VERSION', defaultValue: 'latest', description: 'Rancher chart version')
        string(name: 'RANCHER_ZONE', defaultValue: 'us-central1-a', description: 'Google zone to schedule Rancher instances')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: '')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: '')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: '')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: '')
        string(name: 'IMAGE_SEARCH', defaultValue: '', description: '')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: '')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: '')
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
        stage ('Init Workspace') {
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
                        repo: 'https://github.com/percona/percona-server-mongodb-operator'
                    )

                    libraries.dependencies.install()
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
                        operatorImage: 'perconalab/percona-server-mongodb-operator',
                        branch: GIT_BRANCH
                    )
                }
            }
        }

        stage('Prepare Test Variables') {
            steps {
                script {
                    // rke2 is the Kubernetes version used in Rancher clusters, so we set it as platform version for proper test selection
                    // Rancher version used to define Rancher chart version for test, the chart contains Kubernetes manifests for Rancher management and downstream clusters
                    testVariables = libraries.tests.prepareVersions([
                        libraries             : libraries,
                        release_versions      : 'source/e2e-tests/release_versions',
                        operator              : 'psmdb-operator',

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
                        db_tag                : DB_TAG,
                        debug_tests           : DEBUG_TESTS,
                        test_executor_type    : 'shell',

                        default_operator_image: "perconalab/percona-server-mongodb-operator:${GIT_BRANCH}",

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
                    testVariables.tests = libraries.tests.loadTestList(TEST_LIST, TEST_SUITE)

                    if (IGNORE_PREVIOUS_RUN == 'NO') {
                        libraries.tests.updateListWithLastExecutionStatus(testVariables)
                    } else {
                        echo 'All tests will be re-run, ignoring previous execution results!'
                    }

                    libraries.tests.loadCloudSecret('psmdb')
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

                    sendJobSlack.call(
                        tests          : testVariables.tests,
                        gitBranch      : GIT_BRANCH,
                        platformVer    : testVariables.platform_version,
                        platformChannel: testVariables.platform_channel,
                        platformArch   : testVariables.platform_arch,
                        clusterWide    : testVariables.cluster_wide,
                        image          : testVariables.images.IMAGE_MONGOD,
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

            junit testResults: '*.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
