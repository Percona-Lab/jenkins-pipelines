def tests = []
def release_versions = "source/e2e-tests/release_versions"
def pgoCommon

void loadPgoCommon() {
    pgoCommon = load "cloud/common/pgoPipelineCommon.groovy"
}

Map providerConfig() {
    return [
        platformPrefix: 'MINIKUBE',
        clusterName: 'minikube',
        kubeconfig: "\$HOME/.kube/config"
    ]
}

void initParams() {
    PLATFORM_VER = pgoCommon.initReleaseParams(
        releaseVersions: release_versions,
        pillarVersion: PILLAR_VERSION,
        platformVer: PLATFORM_VER,
        platformPrefix: providerConfig().platformPrefix
    )

    pgoCommon.setBuildDescription(PLATFORM_VER, IMAGE_POSTGRESQL, CLUSTER_WIDE, GIT_BRANCH)
    pgoCommon.setDbTag(IMAGE_POSTGRESQL)
}

void createHash() {
    def hash = pgoCommon.createHash(
        gitBranch: GIT_BRANCH,
        platformVer: PLATFORM_VER,
        clusterWide: CLUSTER_WIDE,
        pgVer: PG_VER,
        imageOperator: IMAGE_OPERATOR,
        imagePostgresql: IMAGE_POSTGRESQL,
        imagePgbouncer: IMAGE_PGBOUNCER,
        imageBackrest: IMAGE_BACKREST,
        imagePmmClient: IMAGE_PMM_CLIENT,
        imagePmmServer: IMAGE_PMM_SERVER,
        imagePmm3Client: IMAGE_PMM3_CLIENT,
        imagePmm3Server: IMAGE_PMM3_SERVER,
        imageUpgrade: IMAGE_UPGRADE,
        jobName: JOB_NAME
    )
    GIT_SHORT_COMMIT = hash.gitShortCommit
    PARAMS_HASH = hash.paramsHash
}

void prepareProviderAgent() {
    sh """
        sudo curl -fsSL -o /usr/local/bin/minikube https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 && sudo chmod +x /usr/local/bin/minikube
    """
}

Map commonTestConfig() {
    return pgoCommon.testConfig(this, providerConfig())
}

void clusterRunner(String cluster) {
    sh """
        export CHANGE_MINIKUBE_NONE_USER=true
        minikube start --kubernetes-version $PLATFORM_VER --cpus=6 --memory=28G --force
    """

    for (int i = 0; i < tests.size(); i++) {
        if (tests[i]["result"] == "skipped") {
            tests[i]["result"] = "failure"
            tests[i]["cluster"] = cluster
            pgoCommon.runTest(tests, i, commonTestConfig())
        }
    }
}

pipeline {
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-minikube.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis'], description: 'For release runs. PG version to test. Use -postgis to take PostGIS images from release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'Minikube kubernetes version. If set to max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'PG_VER', defaultValue: '', description: 'PG version')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg18-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbouncer18')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-pgbackrest18')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-upgrade')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that requires release documentation')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        copyArtifactPermission('weekly-pgo');
    }
    stages {
        stage('Prepare Node') {
            steps {
                script {
                    checkout(scm)
                    loadPgoCommon()
                    sh "rm -rf source"
                    pgoCommon.prepareSources(GIT_BRANCH, false)
                    initParams()
                    createHash()
                }
            }
        }
        stage('Docker Build and Push') {
            steps {
                script { pgoCommon.dockerBuildPush(GIT_BRANCH) }
            }
        }
        stage('Init Tests') {
            steps {
                script { pgoCommon.initTests(tests, commonTestConfig()) }
            }
        }
        stage('Run Tests') {
            options {
                timeout(time: 3, unit: 'HOURS')
            }
            steps {
                script { pgoCommon.prepareAgentBase() }
                prepareProviderAgent()
                clusterRunner('cluster1')
            }
        }
    }
    post {
        always {
            script { pgoCommon.postCleanup(tests, commonTestConfig()) }
        }
    }
}
