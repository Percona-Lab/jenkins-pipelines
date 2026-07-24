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
        DB_TAG = sh(
            script: '''
                if [[ -n "$IMAGE_POSTGRESQL" ]]; then
                    echo "$IMAGE_POSTGRESQL" | awk -F':' '{
                        tag=$2
                        sub(/-postgres$/, "", tag)
                        sub(/-[0-9]+$/, "", tag)
                        print tag
                    }'
                elif [[ -n "$PG_VER" ]]; then
                    echo "main-ppg${PG_VER}"
                fi
            ''',
            returnStdout: true
        ).trim()
    }

    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-minikube.csv', 'run-distro.csv'], description: 'Choose test suite from file')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run')

        choice(
            name: 'PILLAR_VERSION',
            choices: [
                'none',
                '14',
                '14-postgis',
                '15',
                '15-postgis',
                '16',
                '16-postgis',
                '17',
                '17-postgis',
                '18',
                '18-postgis'
            ],
            description: 'Implies release run.'
        )

        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch')
        string(name: 'PLATFORM_VERSION', defaultValue: 'latest', description: 'Minikube Kubernetes version. Use max or rel to read MINIKUBE_REL from release_versions, or latest for upstream stable.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster-wide mode')
        string(name: 'PG_VER', defaultValue: '', description: 'PostgreSQL version. Example: 18, 17, 16, 15, 14')

        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-ppg18-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pgbouncer18')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pgbackrest18')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pmm')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pmm-server')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pmm3')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-pmm3-server')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'Example: perconalab/percona-postgresql-operator:main-upgrade')

        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Jenkins agent provider')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that require release documentation')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }

    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 6, unit: 'HOURS')
        copyArtifactPermission('weekly-pgo')
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
                        repo: 'https://github.com/percona/percona-postgresql-operator.git'
                    )
                    libraries.dependencies.install()
                    libraries.dependencies.installMinikube()
                    libraries.dependencies.installKuttl()
                }
            }
        }

        stage('Docker Build and Push') {
            steps {
                script {
                    if (IMAGE_OPERATOR == '' || PILLAR_VERSION == '' ) {
                        echo "IMAGE_OPERATOR or PILLAR_VERSION is empty, skipping docker build and push"
                    } else {
                        libraries.tools.dockerBuildAndPush(
                            operatorImage: 'perconalab/percona-postgresql-operator',
                            branch: GIT_BRANCH
                        )
                    }
                }
            }
        }

        stage('Prepare Test Variables') {
            steps {
                script {
                    def extraEnvs = [
                        SKIP_TEST_WARNINGS: SKIP_TEST_WARNINGS
                    ]
                    if (PG_VER?.trim()) {
                        extraEnvs.PG_VER = PG_VER
                    }

                    testVariables = libraries.tests.prepareVersions([
                        libraries             : libraries,
                        release_versions      : 'source/e2e-tests/release_versions',
                        operator              : 'pg-operator',
                        operator_repo         : 'perconalab/percona-postgresql-operator',

                        platform_provider     : 'minikube',
                        platform_version      : PLATFORM_VERSION,

                        cluster_wide          : CLUSTER_WIDE,
                        pillar_version        : PILLAR_VERSION,

                        git_branch            : GIT_BRANCH,
                        job_name              : JOB_NAME,
                        db_tag                : env.DB_TAG,
                        test_executor_type    : 'kuttl',

                        images: [
                            IMAGE_OPERATOR   : IMAGE_OPERATOR,
                            IMAGE_POSTGRESQL : IMAGE_POSTGRESQL,
                            IMAGE_PGBOUNCER  : IMAGE_PGBOUNCER,
                            IMAGE_BACKREST   : IMAGE_BACKREST,
                            IMAGE_PMM_CLIENT : IMAGE_PMM_CLIENT,
                            IMAGE_PMM_SERVER : IMAGE_PMM_SERVER,
                            IMAGE_PMM3_CLIENT: IMAGE_PMM3_CLIENT,
                            IMAGE_PMM3_SERVER: IMAGE_PMM3_SERVER,
                            IMAGE_UPGRADE    : IMAGE_UPGRADE
                        ],

                        extra_envs: extraEnvs
                    ])

                    def imageTag = testVariables.images.IMAGE_POSTGRESQL
                            ? testVariables.images.IMAGE_POSTGRESQL.split(':')[-1]
                            : DB_TAG

                    testVariables.db_tag = imageTag.replaceFirst(/-postgres$/, '').replaceFirst(/-[0-9]+$/, '')

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
                    libraries.tests.loadCloudSecret('pg')
                    libraries.tests.loadCloudMinioSecret()
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
                        image        : testVariables.images.IMAGE_POSTGRESQL,
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
