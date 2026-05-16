def tests = []
def clusters = []
def release_versions = "source/e2e-tests/release_versions"
def pgoCommon

void loadPgoCommon() {
    pgoCommon = load "cloud/common/pgoPipelineCommon.groovy"
}

Map providerConfig() {
    return [
        platformPrefix: 'OPENSHIFT',
        kubeconfig: "\$WORKSPACE/openshift/\$clusterSuffix/auth/kubeconfig"
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
        PLATFORM_VER = sh(script: "curl -fsSL https://mirror.openshift.com/pub/openshift-v4/x86_64/clients/ocp/$PLATFORM_VER/release.txt | sed -n 's/^\\s*Version:\\s\\+\\(\\S\\+\\)\\s*\$/\\1/p'", returnStdout: true).trim()
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
        curl -fsSL https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-client-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - oc
        curl -fsSL https://mirror.openshift.com/pub/openshift-v4/clients/ocp/$PLATFORM_VER/openshift-install-linux.tar.gz | sudo tar -C /usr/local/bin -xzf - openshift-install
    """
}

void clusterRunner(String cluster) {
    pgoCommon.clusterRunner(tests, cluster, this.&createCluster, this.&runTest, this.&shutdownCluster)
}

Map commonTestConfig() {
    return pgoCommon.testConfig(this, providerConfig())
}

void enableVolumeSnapshotResources(String CLUSTER_SUFFIX) {
    sh """
        export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig

        for i in \$(seq 1 60); do
            if kubectl get crd csisnapshotcontrollers.operator.openshift.io >/dev/null 2>&1; then
                break
            fi
            sleep 10
        done

        cat <<EOF | kubectl apply -f -
apiVersion: operator.openshift.io/v1
kind: CSISnapshotController
metadata:
  name: cluster
spec:
  managementState: Managed
EOF

        kubectl get csisnapshotcontroller cluster -o yaml
    """
}

void verifyVolumeSnapshotResources(String CLUSTER_SUFFIX) {
    sh """
        export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig

        wait_for_deployment() {
            local deployment_name="\$1"
            local namespace="\$2"

            for i in \$(seq 1 60); do
                if kubectl get deployment "\$deployment_name" -n "\$namespace" >/dev/null 2>&1; then
                    kubectl wait --for=condition=Available deployment/"\$deployment_name" -n "\$namespace" --timeout=10m
                    return 0
                fi
                sleep 10
            done

            kubectl get deployment -n "\$namespace" || true
            return 1
        }

        wait_for_deployment csi-snapshot-controller-operator openshift-cluster-storage-operator
        wait_for_deployment csi-snapshot-controller openshift-cluster-storage-operator

        kubectl get crd volumesnapshots.snapshot.storage.k8s.io volumesnapshotcontents.snapshot.storage.k8s.io volumesnapshotclasses.snapshot.storage.k8s.io
        kubectl api-resources --api-group=snapshot.storage.k8s.io
    """
}

void createCluster(String CLUSTER_SUFFIX) {
    clusters.add("$CLUSTER_SUFFIX")

    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'), file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift4-secrets', variable: 'OPENSHIFT_CONF_FILE'), usernamePassword(credentialsId: 'docker.io', passwordVariable: 'DOCKER_READ_PASS', usernameVariable: 'DOCKER_READ_USER')]) {
        sh """
            mkdir -p openshift/$CLUSTER_SUFFIX
            timestamp="\$(date +%s)"
tee openshift/$CLUSTER_SUFFIX/install-config.yaml << EOF
additionalTrustBundlePolicy: Proxyonly
credentialsMode: Mint
apiVersion: v1
baseDomain: cd.percona.com
compute:
- architecture: amd64
  hyperthreading: Enabled
  name: worker
  platform:
    aws:
      type: m5.xlarge
  replicas: 3
controlPlane:
  architecture: amd64
  hyperthreading: Enabled
  name: master
  platform: {}
  replicas: 1
metadata:
  creationTimestamp: null
  name: $CLUSTER_NAME-$CLUSTER_SUFFIX
networking:
  clusterNetwork:
  - cidr: 10.128.0.0/14
    hostPrefix: 23
  machineNetwork:
  - cidr: 10.0.0.0/16
  networkType: OVNKubernetes
  serviceNetwork:
  - 172.30.0.0/16
platform:
  aws:
    region: ${AWS_REGION}
    userTags:
      iit-billing-tag: openshift
      delete-cluster-after-hours: 8
      team: cloud
      product: pg-operator
      creation-time: \$timestamp

publish: External
EOF
            cat $OPENSHIFT_CONF_FILE >> openshift/$CLUSTER_SUFFIX/install-config.yaml
        """

        withEnv(["CLUSTER_SUFFIX=${CLUSTER_SUFFIX}"]) {
            sshagent(['aws-openshift-41-key']) {
                sh '''
                    /usr/local/bin/openshift-install create cluster --dir=openshift/$CLUSTER_SUFFIX
                    export KUBECONFIG=openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                    TMP=$(mktemp)
                    oc get secret/pull-secret -n openshift-config --template='{{index .data ".dockerconfigjson" | base64decode}}' > $TMP
                    oc registry login --registry='docker.io' --auth-basic="$DOCKER_READ_USER:$DOCKER_READ_PASS" --to=$TMP
                    oc set data secret/pull-secret -n openshift-config --from-file=.dockerconfigjson=$TMP
                    rm -rf $TMP
                '''
            }
        }
    }

    enableVolumeSnapshotResources(CLUSTER_SUFFIX)
    verifyVolumeSnapshotResources(CLUSTER_SUFFIX)
}

void runTest(Integer TEST_ID) {
    pgoCommon.runTest(tests, TEST_ID, commonTestConfig())
}

void shutdownCluster(String CLUSTER_SUFFIX) {
    withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'openshift-cicd', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'), file(credentialsId: 'aws-openshift-41-key-pub', variable: 'AWS_NODES_KEY_PUB'), file(credentialsId: 'openshift-secret-file', variable: 'OPENSHIFT-CONF-FILE')]) {
        sshagent(['aws-openshift-41-key']) {
            pgoCommon.cleanupKubernetesNamespaces("$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig")
            sh """
                export KUBECONFIG=$WORKSPACE/openshift/$CLUSTER_SUFFIX/auth/kubeconfig
                /usr/local/bin/openshift-install destroy cluster --dir=openshift/$CLUSTER_SUFFIX || true
            """
        }
    }
}

pipeline {
    parameters {
        choice(name: 'TEST_SUITE', choices: ['run-release.csv', 'run-distro.csv'], description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.')
        text(name: 'TEST_LIST', defaultValue: '', description: 'List of tests to run separated by new line')
        choice(name: 'IGNORE_PREVIOUS_RUN', choices: ['NO', 'YES'], description: 'Ignore passed tests in previous run (run all)')
        choice(name: 'PILLAR_VERSION', choices: ['none', '14', '14-postgis', '15', '15-postgis', '16', '16-postgis', '17', '17-postgis', '18', '18-postgis'], description: 'For release runs. PG version to test. Use -postgis to take PostGIS images from release_versions.')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Tag/Branch for percona/percona-postgresql-operator repository')
        string(name: 'PLATFORM_VER', defaultValue: 'latest', description: 'OpenShift kubernetes version. If set to min or max, value will be automatically taken from release_versions file.')
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
        string(name: 'AWS_REGION', defaultValue: 'eu-west-3', description: 'AWS region to use for openshift cluster')
        choice(name: 'JENKINS_AGENT', choices: ['Hetzner', 'AWS'], description: 'Cloud infra for build')
        choice(name: 'SKIP_TEST_WARNINGS', choices: ['false', 'true'], description: 'Skip test warnings that requires release documentation')
    }
    agent {
        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
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
                // initParams on openshift should run before provider tools because PLATFORM_VER selects the oc version.
                initParams()
                script { pgoCommon.prepareAgentBase() }
                prepareProviderAgent()
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
                timeout(time: 4, unit: 'HOURS')
            }
            parallel {
                stage('cluster1') {
                    agent {
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
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
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
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
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
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
                        label params.JENKINS_AGENT == 'Hetzner' ? 'docker-x64-min' : 'min-al2023-x64'
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
