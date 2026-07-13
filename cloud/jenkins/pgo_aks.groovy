def location = params.AKS_LOCATION ?: getLocation(JOB_NAME)
def tests = []
def clusters = []
def release_versions = "source/e2e-tests/release_versions"
def pgoCommon

String getLocation(String job_name) {
    if ("$job_name" == 'pgo-aks-1') {
        return 'eastus'
    } else {
        return 'norwayeast'
    }
}

void loadPgoCommon() {
    pgoCommon = load "cloud/common/pgoPipelineCommon.groovy"
}

Map providerConfig() {
    return [
        platformPrefix: 'AKS',
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
        PLATFORM_VER = sh(script: "az aks get-versions --location $location --output json | jq -r '.values | max_by(.patchVersions) | .patchVersions | keys[]' | sort --version-sort | tail -1", returnStdout: true).trim()
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
    installAzureCLI()
    azureAuth()
}

void azureAuth() {
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
        sh '''
            az login --service-principal -u "$AZURE_CLIENT_ID" -p "$AZURE_CLIENT_SECRET" -t "$AZURE_TENANT_ID"  --allow-no-subscriptions
            az account set -s "$AZURE_SUBSCRIPTION_ID"
        '''
    }
}

void installAzureCLI() {
    sh """
        if ! command -v az &>/dev/null; then
            if [ "\$JENKINS_AGENT" = "AWS" ]; then
                curl -fsSL -o install.py https://azurecliprod.blob.core.windows.net/install.py
                printf "/usr/azure-cli\\n/usr/bin" | sudo python3 install.py
                sudo /usr/azure-cli/bin/python -m pip install "urllib3<2.0.0" > /dev/null
            else
                echo "Installing Azure CLI for Hetzner instances..."
                sudo rpm --import https://packages.microsoft.com/keys/microsoft.asc
                cat <<EOF | sudo tee /etc/yum.repos.d/azure-cli.repo
[azure-cli]
name=Azure CLI
baseurl=https://packages.microsoft.com/yumrepos/azure-cli
enabled=1
gpgcheck=1
gpgkey=https://packages.microsoft.com/keys/microsoft.asc
EOF
                sudo dnf install azure-cli -y
            fi
        fi
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

    sh """
        export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
        az aks create -n $CLUSTER_NAME-$CLUSTER_SUFFIX \
            -g percona-operators \
            --subscription eng-cloud-dev \
            --load-balancer-sku standard \
            --enable-managed-identity \
            --node-count 3 \
            --node-vm-size Standard_B4ms \
            --node-osdisk-size 30 \
            --network-plugin kubenet \
            --generate-ssh-keys \
            --outbound-type loadbalancer \
            --kubernetes-version $PLATFORM_VER \
            --tags team=cloud delete-cluster-after-hours=6 creation-time=\$(date -u +%s) \
            -l $location
        az aks get-credentials --subscription eng-cloud-dev --resource-group percona-operators --name $CLUSTER_NAME-$CLUSTER_SUFFIX --overwrite-existing
    """
}

void runTest(Integer TEST_ID) {
    pgoCommon.runTest(tests, TEST_ID, commonTestConfig())
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([azureServicePrincipal('PERCONA-OPERATORS-SP')]) {
        pgoCommon.cleanupKubernetesNamespaces("/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX")
        sh """
            export KUBECONFIG=/tmp/$CLUSTER_NAME-$CLUSTER_SUFFIX
            az aks delete --name $CLUSTER_NAME-$CLUSTER_SUFFIX --resource-group percona-operators --subscription eng-cloud-dev --yes || true
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
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'AKS kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
        choice(name: 'CLUSTER_WIDE', choices: ['YES', 'NO'], description: 'Run tests in cluster wide mode')
        string(name: 'PG_VER', defaultValue: '', description: 'PG version')
        string(name: 'IMAGE_OPERATOR', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main')
        string(name: 'IMAGE_POSTGRESQL', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg18-postgres')
        string(name: 'IMAGE_PGBOUNCER', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg18-pgbouncer')
        string(name: 'IMAGE_BACKREST', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-ppg18-pgbackrest')
        string(name: 'IMAGE_PMM_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:dev-latest')
        string(name: 'IMAGE_PMM_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:dev-latest')
        string(name: 'IMAGE_PMM3_CLIENT', defaultValue: '', description: 'ex: perconalab/pmm-client:3-dev-latest')
        string(name: 'IMAGE_PMM3_SERVER', defaultValue: '', description: 'ex: perconalab/pmm-server:3-dev-latest')
        string(name: 'IMAGE_UPGRADE', defaultValue: '', description: 'ex: perconalab/percona-postgresql-operator:main-upgrade')
        string(name: 'AKS_LOCATION', defaultValue: '', description: 'AKS location to use for cluster. By default "eastus" is for aks-1 job and "norwayeast" for aks-2')
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
