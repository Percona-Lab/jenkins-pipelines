import groovy.transform.Field

@Field Integer numClusters = 8
@Field List clusters = []

@Field Map libraries = [:]
@Field Map testVariables = [:]

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
        choice(name: 'PILLAR_VERSION', choices: ['none', '80', '83', '70', '60'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch')
        string(name: 'PLATFORM_VERSION', defaultValue: 'latest', description: 'EKS kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        string(name: 'EKS_REGION', defaultValue: 'eu-west-3', description: 'EKS region to use for cluster')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: '')
        string(name: 'IMAGE_MONGOD', defaultValue: '', description: '')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: '')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: '')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: '')
        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: '')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: '')
    }

    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
        copyArtifactPermission('psmdb-operator-latest-scheduler')
    }

    stages {
        stage('Init Workspace') {
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
                    libraries.dependencies.installEksctl()
                    libraries.dependencies.installAzureCLI()

                    libraries.azure.auth()
                }
            }
        }

        stage('Docker Build and Push') {
            steps {
                script {
                    libraries.tools.dockerBuildAndPush(
                        operatorImage: 'perconalab/percona-server-mongodb-operator',
                        branch: GIT_BRANCH,
                        platforms: 'linux/amd64,linux/arm64'
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

                        platform_provider     : 'eks',
                        platform_version      : PLATFORM_VERSION,
                        region                : EKS_REGION,

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
                            IMAGE_LOGCOLLECTOR: IMAGE_LOGCOLLECTOR
                        ]
                    ])

                    currentBuild.displayName = "#${currentBuild.number} ${GIT_BRANCH}"
                    def cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
                    currentBuild.description = "${testVariables.platform_version} | ${DB_TAG} | ${cw}"
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
                        tests        : testVariables.tests,
                        gitBranch    : GIT_BRANCH,
                        platformVer  : testVariables.platform_version,
                        clusterWide  : testVariables.cluster_wide,
                        image        : testVariables.images.IMAGE_MONGOD,
                        operatorImage: testVariables.images.IMAGE_OPERATOR
                    )
                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
                }

                clusters.each { clusterSuffix ->
                    try {
                        def clusterCfg = [
                            clusterName  : testVariables.cluster_name,
                            clusterSuffix: clusterSuffix,
                            region       : EKS_REGION,
                            kubeconfig   : "/tmp/${testVariables.cluster_name}-${clusterSuffix}"
                        ]

                        libraries.tools.kubernetesCleanupCluster(clusterCfg.kubeconfig)
                        libraries.eks.shutdownCluster(clusterCfg)
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
