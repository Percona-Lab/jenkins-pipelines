import groovy.transform.Field

@Field Integer numClusters = 2
@Field List clusters = []

@Field Map libraries = [:]
@Field Map testVariables = [:]

def getLibraries() {
    def loader = load('cloud/common/libraries.groovy')
    libraries = loader.loadLibraries()
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
        DB_TAG = sh(
            script: '''[[ "$IMAGE_PXC" ]] && echo "$IMAGE_PXC" | awk -F':' '{print $2}' || echo main''',
            returnStdout: true
        ).trim()
    }

    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-minikube.csv', 'run-distro.csv'], description: 'Choose test suite from file')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run')
        choice(name: 'PILLAR_VERSION', choices: ['none', '84', '80', '57'], description: 'Implies release run.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch')
        string(name: 'GIT_REPO', defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator', description: 'Repository')
        string(name: 'PLATFORM_VERSION', defaultValue: 'latest', description: 'Minikube Kubernetes version. Use max or rel to read MINIKUBE_REL from release_versions, or latest for upstream stable.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster-wide mode')

        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main')
        string(name: 'IMAGE_PXC', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-pxc8.0')
        string(name: 'IMAGE_PROXY', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-proxysql')
        string(name: 'IMAGE_HAPROXY', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-haproxy')
        string(name: 'IMAGE_BACKUP', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-pxc8.0-backup')
        string(name: 'IMAGE_LOGCOLLECTOR', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-logcollector')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-pmm')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-pmm-server')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-pmm3')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'Example: perconalab/percona-xtradb-cluster-operator:main-pmm3-server')

        choice(name: 'DEBUG_TESTS', choices: ['NO', 'YES'], description: 'Enable debug mode for tests')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Jenkins agent provider')
    }

    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
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
                    deleteDir()
                    checkout scm
                    getLibraries()
                }
            }
        }

        stage('Prepare Node') {
            steps {
                script {
                    libraries.tools.gitClone(branch: GIT_BRANCH, repo: GIT_REPO)
                    libraries.dependencies.install()
                    libraries.dependencies.installGoogleCLI()
                    libraries.dependencies.installAzureCLI()
                    libraries.dependencies.installMinikube()
                    libraries.gcloud.auth()
                    libraries.azure.auth()
                }
            }
        }

        stage('Docker Build and Push') {
            steps {
                script {
                    libraries.tools.dockerBuildAndPush(
                        operatorImage: 'perconalab/percona-xtradb-cluster-operator',
                        branch: GIT_BRANCH
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
                        operator_repo         : 'perconalab/percona-xtradb-cluster-operator',

                        platform_provider     : 'minikube',
                        platform_version      : PLATFORM_VERSION,

                        cluster_wide          : CLUSTER_WIDE,
                        pillar_version        : PILLAR_VERSION,

                        git_branch            : GIT_BRANCH,
                        job_name              : JOB_NAME,
                        db_tag                : env.DB_TAG,
                        debug_tests           : DEBUG_TESTS,
                        test_executor_type    : 'shell',

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

                    def imageTag = testVariables.images.IMAGE_PXC ? testVariables.images.IMAGE_PXC.split(':')[-1] : DB_TAG
                    testVariables.db_tag = imageTag

                    def cw = ("$CLUSTER_WIDE" == "YES") ? "CW" : "NON-CW"
                    currentBuild.displayName = "#${currentBuild.number} ${GIT_BRANCH}"
                    currentBuild.description = "${testVariables.platform_version.replaceFirst('^v', '')} ${imageTag} ${cw}"
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
                    libraries.tests.loadCloudSecret('pxc')
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
                    testVariables.tests.toString().replace('], ', ']\n').replace(']]', ']').replaceFirst('\\[', '')
                libraries.tests.makeReport(testVariables.tests, testVariables)

                try {
                    def sendJobSlack = load('cloud/common/sendJobSlackNotification.groovy')
                    sendJobSlack.call(
                        tests        : testVariables.tests,
                        gitBranch    : GIT_BRANCH,
                        platformVer  : testVariables.platform_version,
                        clusterWide  : testVariables.cluster_wide,
                        image        : testVariables.images.IMAGE_PXC,
                        operatorImage: testVariables.images.IMAGE_OPERATOR
                    )
                } catch (err) {
                    echo "Slack helper load/call failed: ${err}"
                }

                clusters.each { clusterSuffix ->
                    def clusterCfg = [
                        clusterName  : testVariables.cluster_name,
                        clusterSuffix: clusterSuffix,
                        kubeconfig   : "/tmp/${testVariables.cluster_name}-${clusterSuffix}"
                    ]
                    libraries.tools.kubernetesCleanupCluster(clusterCfg.kubeconfig)
                    libraries.minikube.shutdownCluster(clusterCfg)
                }
                libraries.tools.dockerCleanupVolumes()
            }

            junit testResults: '*.xml', healthScaleFactor: 1.0, allowEmptyResults: true
            archiveArtifacts artifacts: '*.xml,*.txt', allowEmptyArchive: true
            deleteDir()
        }
    }
}
