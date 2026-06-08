def region = 'us-central1-c'
def tests = []
def clusters = []
def release_versions = "source/e2e-tests/release_versions"
def pgoCommon

void loadPgoCommon() {
    pgoCommon = load "cloud/common/pgoPipelineCommon.groovy"
}

Map providerConfig() {
    return [
        platformPrefix: 'GKE',
        kubeconfig: "/tmp/$CLUSTER_NAME-\$clusterSuffix",
        extraHashValues: [GKE_RELEASE_CHANNEL],
        extraParameters: [GKE_RELEASE_CHANNEL: GKE_RELEASE_CHANNEL]
    ]
}

void initParams() {
    if ("$PILLAR_VERSION" != "none") {
        GKE_RELEASE_CHANNEL = "stable"
        echo "Forcing GKE_RELEASE_CHANNEL=stable, because it's a release run!"
    }
    PLATFORM_VER = pgoCommon.initReleaseParams(
        releaseVersions: release_versions,
        pillarVersion: PILLAR_VERSION,
        platformVer: PLATFORM_VER,
        platformPrefix: providerConfig().platformPrefix
    )

    if ("$PLATFORM_VER" == "latest") {
        PLATFORM_VER = sh(script: "gcloud container get-server-config --region=${GKE_REGION} --flatten=channels --filter='channels.channel=$GKE_RELEASE_CHANNEL' --format='value(channels.validVersions)' | cut -d- -f1", returnStdout: true).trim()
    }

    pgoCommon.setBuildDescription(PLATFORM_VER, IMAGE_POSTGRESQL, CLUSTER_WIDE, GIT_BRANCH, GKE_RELEASE_CHANNEL)
    pgoCommon.setDbTag(IMAGE_POSTGRESQL)
}

void createHash() {
    def hash = pgoCommon.createHash(
        gitBranch: GIT_BRANCH,
        extraHashValues: providerConfig().extraHashValues,
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
    CLUSTER_NAME = hash.clusterName
}

void prepareProviderAgent() {
    sh """
        sudo tee /etc/yum.repos.d/google-cloud-sdk.repo << EOF
[google-cloud-cli]
name=Google Cloud CLI
baseurl=https://packages.cloud.google.com/yum/repos/cloud-sdk-el7-x86_64
enabled=1
gpgcheck=1
repo_gpgcheck=0
gpgkey=https://packages.cloud.google.com/yum/doc/rpm-package-key.gpg
EOF
        sudo yum install -y google-cloud-cli google-cloud-cli-gke-gcloud-auth-plugin
    """

    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            gcloud auth activate-service-account --key-file $CLIENT_SECRET_FILE
            gcloud config set project $GCP_PROJECT
        """
    }
}

void clusterRunner(String cluster) {
    pgoCommon.clusterRunner(tests, cluster, this.&createCluster, this.&runTest, this.&shutdownCluster)
}

Map commonTestConfig() {
    return pgoCommon.testConfig(this, providerConfig())
}

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX

            maxRetries=10
            exitCode=1

            printf 'linuxConfig:\n  hugepageConfig:\n    hugepage_size2m: 1024\n' > ${WORKSPACE}/hugepages-config-${CLUSTER_SUFFIX}.yaml

            while [[ \$exitCode != 0 && \$maxRetries > 0 ]]; do
                gcloud container clusters create $CLUSTER_NAME-$CLUSTER_SUFFIX \
                    --release-channel $GKE_RELEASE_CHANNEL \
                    --zone $GKE_REGION \
                    --cluster-version $PLATFORM_VER \
                    --preemptible \
                    --disk-size 30 \
                    --machine-type n1-standard-4 \
                    --num-nodes=3 \
                    --network=jenkins-vpc \
                    --subnetwork=jenkins-$CLUSTER_SUFFIX \
                    --cluster-ipv4-cidr=/21 \
                    --labels delete-cluster-after-hours=6 \
                    --enable-ip-alias \
                    --monitoring=NONE \
                    --logging=NONE \
                    --no-enable-managed-prometheus \
                    --workload-pool=cloud-dev-112233.svc.id.goog \
                    --system-config-from-file=${WORKSPACE}/hugepages-config-${CLUSTER_SUFFIX}.yaml \
                    --quiet &&\
                kubectl create clusterrolebinding cluster-admin-binding1 --clusterrole=cluster-admin --user=\$(gcloud config get-value core/account)
                exitCode=\$?
                if [[ \$exitCode == 0 ]]; then break; fi
                (( maxRetries -- ))
                sleep 1
            done
            rm -f ${WORKSPACE}/hugepages-config-${CLUSTER_SUFFIX}.yaml

            if [[ \$exitCode != 0 ]]; then exit \$exitCode; fi
        """
    }
}

void runTest(Integer TEST_ID) {
    pgoCommon.runTest(tests, TEST_ID, commonTestConfig())
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([string(credentialsId: 'GCP_PROJECT_ID', variable: 'GCP_PROJECT'), file(credentialsId: 'gcloud-key-file', variable: 'CLIENT_SECRET_FILE')]) {
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            gcloud container clusters delete --zone ${GKE_REGION} $CLUSTER_NAME-$CLUSTER_SUFFIX --quiet || true
        """
    }
}

pipeline {
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis'], description: 'For release runs. PG version to test. Use -postgis to take PostGIS images from release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'GKE kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'GKE_RELEASE_CHANNEL', choices: ['rapid', 'stable', 'regular', 'None'], description: 'GKE release channel. Will be forced to stable for release run.')
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
        string(name: 'GKE_REGION', defaultValue: 'us-central1-c', description: 'GKE region to use for cluster')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that requires release documentation')
    }
    agent {
        label 'docker'
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
                    deleteDir()
                    checkout(scm)
                    loadPgoCommon()
                }
                script { pgoCommon.prepareSources(GIT_BRANCH, false) }
                script { pgoCommon.prepareAgentBase() }
                prepareProviderAgent()
                initParams()
                createHash()
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
            parallel {
                stage('cluster1') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster1')
                    }
                }
                stage('cluster2') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster2')
                    }
                }
                stage('cluster3') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster3')
                    }
                }
                stage('cluster4') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        unstash "sourceFILES"
                        loadPgoCommon()
                        script { pgoCommon.prepareAgentBase() }
                        prepareProviderAgent()
                        clusterRunner('cluster4')
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                def cfg = commonTestConfig()
                cfg.clusters = clusters
                cfg.shutdownClusterFn = this.&shutdownCluster
                cfg.slack = [gkeReleaseChannel: GKE_RELEASE_CHANNEL]
                pgoCommon.postCleanup(tests, cfg)
            }
        }
    }
}
