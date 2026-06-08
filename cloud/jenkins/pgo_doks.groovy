def tests = []
def clusters = []
def release_versions = "source/e2e-tests/release_versions"
def pgoCommon

void loadPgoCommon() {
    pgoCommon = load "cloud/common/pgoPipelineCommon.groovy"
}

Map providerConfig() {
    return [
        platformPrefix: 'DOKS',
        kubeconfig: "/tmp/$CLUSTER_NAME-\$clusterSuffix"
    ]
}

void initParams() {
    PLATFORM_VER = pgoCommon.initReleaseParams(
        releaseVersions: release_versions,
        pillarVersion: PILLAR_VERSION,
        platformVer: PLATFORM_VER,
        platformPrefix: providerConfig().platformPrefix
    )

    if ("$PLATFORM_VER" == "latest") {
        withCredentials([string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
            PLATFORM_VER = sh(script: "doctl kubernetes options versions | awk 'NR==2 { print \$2 }'", returnStdout: true).trim()
            echo "Latest platform version is $PLATFORM_VER"
        }
    }

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
    CLUSTER_NAME = hash.clusterName
}

void prepareProviderAgent() {
    sh """
        client_version=\$(curl -fsSL https://api.github.com/repos/digitalocean/doctl/releases/latest | grep '"tag_name":' | cut -d '"' -f4 | sed 's/^v//')
        curl -fsSL "https://github.com/digitalocean/doctl/releases/download/v\$client_version/doctl-\$client_version-linux-amd64.tar.gz" | tar -xz && sudo mv doctl /usr/local/bin
        doctl version
    """
}

void clusterRunner(String cluster) {
    pgoCommon.clusterRunner(tests, cluster, this.&createCluster, this.&runTest, this.&shutdownCluster)
}

Map commonTestConfig() {
    return pgoCommon.testConfig(this, providerConfig())
}

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    withCredentials([string(credentialsId: 'DOKS_PROJECT_ID', variable: 'PROJECT'), string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
        sh """
            set -euo pipefail

            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            cluster="$CLUSTER_NAME-$CLUSTER_SUFFIX"
            cluster_version=\$(doctl kubernetes options versions --output json | jq -r --arg v "$PLATFORM_VER" '.[] | select(.kubernetes_version==\$v) | .slug')

            create_cluster() {
                doctl kubernetes cluster create "\$cluster" \
                    --region "$DO_REGION" \
                    --version "\$cluster_version" \
                    --node-pool "name=default-pool;size=s-4vcpu-16gb-amd;tag=worker;auto-scale=true;count=4;min-nodes=4;max-nodes=6"

                doctl kubernetes cluster kubeconfig save "\$cluster"
            }

            assign_cluster_to_project() {
                cluster_id=\$(doctl kubernetes cluster get "\$cluster" --format ID --no-header)
                urn="do:kubernetes:\$cluster_id"

                doctl projects resources assign "$PROJECT" --resource "\$urn"
            }

            max_retries=15
            for ((i=1;i<=max_retries;i++)); do
                if create_cluster && assign_cluster_to_project; then
                    break
                fi

                echo "Retry \$i/\$max_retries"
                sleep 2
            done
        """
    }
}

void runTest(Integer TEST_ID) {
    def cfg = commonTestConfig()
    cfg.credentials = [string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]
    pgoCommon.runTest(tests, TEST_ID, cfg)
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([string(credentialsId: 'DOKS_PROJECT_ID', variable: 'PROJECT'), string(credentialsId: 'DOKS_TOKEN', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
        pgoCommon.cleanupKubernetesNamespaces("/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX", deletePvcs: true)
        sh """
            doctl kubernetes cluster delete $CLUSTER_NAME-$CLUSTER_SUFFIX --force || true
        """
    }
    clusters.remove("$CLUSTER_SUFFIX")
}

pipeline {
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-pr.csv', 'run-minikube.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis'], description: 'For release runs. PG version to test. Use -postgis to take PostGIS images from release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'Digital Ocean Kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
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
        string(name: 'DO_REGION', defaultValue: 'nyc1', description: 'Digital Ocean region to use for cluster')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that requires release documentation')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
                pgoCommon.postCleanup(tests, cfg)
            }
        }
    }
}
