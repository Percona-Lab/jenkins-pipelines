// PMM HA Helm upgrade tests.
//
// Creates a bare Kubernetes cluster with no PMM on it, then drives k8s/install_pmm_ha.sh
// through the upgrade in the order a real one happens:
//
//   1. install the released PMM HA from the published chart
//   2. @pmm-helm-pre-upgrade
//   3. upgrade the dependencies to the percona-helm-charts branch
//   4. @pmm-helm-mid-upgrade - the server must be untouched and still serving
//   5. upgrade pmm-ha to that branch and the image under test
//   6. @pmm-helm-post-upgrade
//
// Every helm step is k8s/install_pmm_ha.sh from pmm-qa, on the branch PMM_QA_GIT_BRANCH
// points at; this job only sequences it and runs the tests.
//
// The cluster jobs are asked for a bare cluster with DEPLOY_PMM=false; both
// pmm3-ha-eks and pmm3-ha-rosa gate their 'Install PMM HA' stage on it and still
// archive the kubeconfig, so this job installs PMM itself with install_pmm_ha.sh.

void runEKSClusterCreate(String k8sVersion) {
    def clusterCreateJob = build job: 'pmm3-ha-eks', parameters: [
        string(name: 'K8S_VERSION', value: k8sVersion),
        booleanParam(name: 'DEPLOY_PMM', value: false),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: false),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    env.PLATFORM = 'eks'
    env.CLUSTER_JOB_NAME = 'pmm3-ha-eks'
    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    // Pinned to this exact build: the create job may run concurrently for other
    // requesters, so lastSuccessful is not safe here.
    env.CLUSTER_BUILD_NUMBER = clusterCreateJob.number.toString()
    env.KUBECONFIG_ARTIFACT = 'kubeconfig'
}

void runOpenShiftClusterCreate(String ocpVersion) {
    def clusterCreateJob = build job: 'pmm3-ha-rosa', parameters: [
        string(name: 'OCP_VERSION', value: ocpVersion),
        booleanParam(name: 'DEPLOY_PMM', value: false),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: false),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    env.PLATFORM = 'openshift'
    env.CLUSTER_JOB_NAME = 'pmm3-ha-rosa'
    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    env.CLUSTER_BUILD_NUMBER = clusterCreateJob.number.toString()
    env.KUBECONFIG_ARTIFACT = 'kubeconfig'
}

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm-qa repository',
            name: 'PMM_QA_GIT_BRANCH')
        choice(
            choices: ['OpenShift', 'EKS'],
            description: 'Kubernetes platform to provision. The cluster is created without PMM; this job installs it.',
            name: 'CLUSTER_TYPE')
        choice(
            choices: ['4.21', '4.20', '4.19', '4.18'],
            description: 'OpenShift (ROSA) cluster version. Used only when CLUSTER_TYPE = OpenShift.',
            name: 'OCP_VERSION')
        choice(
            choices: ['1.35', '1.34', '1.33'],
            description: 'EKS cluster version. Used only when CLUSTER_TYPE = EKS.',
            name: 'K8S_VERSION')
        string(
            defaultValue: '',
            description: 'PMM Server image the upgrade STARTS from - installed before the tests run (image-name:version-tag). Empty resolves the newest released percona/pmm-server tag from Docker Hub.',
            name: 'RELEASE_DOCKER_VERSION')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server image the upgrade GOES TO - the image under test (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: '',
            description: 'Published pmm-ha chart version to install before the upgrade. Empty installs the latest published chart.',
            name: 'CHART_VERSION')
        string(
            defaultValue: 'PMM-HA-GA',
            description: 'percona-helm-charts branch whose pmm-ha chart the upgrade installs; the script clones it. The install always comes from the published chart. "latest" upgrades from the published chart too, making it an image-only upgrade.',
            name: 'HELM_CHART_BRANCH')
        string(
            defaultValue: 'pmm3admin!',
            description: 'pmm-server admin user password',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: '@pmm-helm-pre-upgrade',
            description: 'Playwright --grep tag expression run against the released install',
            name: 'PRE_UPGRADE_TAGS')
        string(
            defaultValue: '@pmm-helm-mid-upgrade',
            description: 'Playwright --grep tag expression run after the dependencies upgrade, before the server one',
            name: 'MID_UPGRADE_TAGS')
        string(
            defaultValue: '@pmm-helm-post-upgrade',
            description: 'Playwright --grep tag expression run after the upgrade',
            name: 'POST_UPGRADE_TAGS')
    }
    options {
        skipDefaultCheckout()
        timeout(time: 4, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }
    environment {
        HA_UPGRADE_BASELINE = "${WORKSPACE}/ha-upgrade-baseline.json"
        PMM_HA_SUMMARY = "${WORKSPACE}/pmm-ha-summary.env"
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    def clusterVersion = params.CLUSTER_TYPE == 'EKS' ? params.K8S_VERSION : params.OCP_VERSION
                    def releasedImage = params.RELEASE_DOCKER_VERSION ?: 'latest released'
                    currentBuild.description = "HA upgrade on ${params.CLUSTER_TYPE} ${clusterVersion}. ${releasedImage} -> ${params.DOCKER_VERSION}"
                }
                deleteDir()
                git poll: false, branch: PMM_QA_GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'
                sh '''
                    sudo rm -rf /srv/pmm-qa || :
                    sudo mkdir -p /srv/pmm-qa
                    sudo rsync -a ${WORKSPACE}/ /srv/pmm-qa/
                    sudo chown -R ec2-user:ec2-user /srv/pmm-qa

                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium || :

                    # helm as well as kubectl: install_pmm_ha.sh drives both charts, and
                    # does its OpenShift work through kubectl, so no oc binary is needed.
                    bash -x /srv/pmm-qa/k8s/install_k8s_tools.sh --kubectl --helm --sudo
                    kubectl version --client
                    helm version --short
                '''
            }
        }
        stage('Create Kubernetes cluster') {
            parallel {
                stage('Provision EKS Cluster') {
                    when {
                        expression { params.CLUSTER_TYPE == 'EKS' }
                    }
                    steps {
                        runEKSClusterCreate(K8S_VERSION)
                    }
                }
                stage('Provision OpenShift Cluster') {
                    when {
                        expression { params.CLUSTER_TYPE == 'OpenShift' }
                    }
                    steps {
                        runOpenShiftClusterCreate(OCP_VERSION)
                    }
                }
            }
        }
        stage('Copy kubeconfig') {
            steps {
                script {
                    copyArtifacts(
                        projectName: env.CLUSTER_JOB_NAME,
                        selector: specific(env.CLUSTER_BUILD_NUMBER),
                        filter: env.KUBECONFIG_ARTIFACT,
                        target: 'cluster-artifacts',
                    )

                    def kubeconfig = "${WORKSPACE}/cluster-artifacts/${env.KUBECONFIG_ARTIFACT}"
                    if (!fileExists(kubeconfig)) {
                        error "Failed to copy kubeconfig from ${env.CLUSTER_JOB_NAME} build ${env.CLUSTER_BUILD_NUMBER}"
                    }
                    env.KUBECONFIG = kubeconfig

                    sh '''
                        chmod 600 "${KUBECONFIG}"
                        for i in $(seq 1 6); do
                            if kubectl get nodes >/dev/null 2>&1; then
                                echo "Successfully connected to the cluster"
                                kubectl get nodes -o wide
                                exit 0
                            fi
                            echo "Waiting for cluster to be accessible... (attempt $i/6)"
                            sleep 10
                        done
                        echo "Failed to connect to the cluster" >&2
                        exit 1
                    '''
                }
            }
        }
        stage('Verify the cluster has no PMM') {
            steps {
                // Fails closed: if the create job installed PMM anyway, the upgrade
                // scenario would silently start from the wrong place.
                sh '''
                    if helm list --namespace pmm --filter '^pmm-ha$' -q 2>/dev/null | grep -q .; then
                        echo "ERROR: pmm-ha is already installed on this cluster." >&2
                        echo "This job needs a bare cluster - check that the create job ran with DEPLOY_PMM=false." >&2
                        helm list --namespace pmm >&2
                        exit 1
                    fi
                    echo "No pmm-ha release on the cluster, as expected."
                '''
            }
        }
        stage('Install released PMM HA') {
            options {
                timeout(time: 40, unit: 'MINUTES')
            }
            steps {
                script {
                    withEnv(["PMM_ADMIN_PASSWORD=${params.ADMIN_PASSWORD}"]) {
                        sh '''
                            /srv/pmm-qa/k8s/install_pmm_ha.sh \
                                --platform "${PLATFORM}" \
                                ${RELEASE_DOCKER_VERSION:+--image "${RELEASE_DOCKER_VERSION}"} \
                                --external-access \
                                --summary-file "${PMM_HA_SUMMARY}" \
                                --debug-dir "${WORKSPACE}/pmm-ha-debug" \
                                ${CHART_VERSION:+--chart-version "${CHART_VERSION}"}
                        '''
                    }

                    // Read back rather than assumed: with RELEASE_DOCKER_VERSION empty the
                    // script resolves the released image itself, and the pre-upgrade test
                    // asserts against whatever actually got installed.
                    env.RELEASE_DOCKER_VERSION = sh(
                        returnStdout: true,
                        script: "awk -F= '/^image=/{print \$2}' ${env.PMM_HA_SUMMARY}",
                    ).trim()
                    def pmmAddress = sh(
                        returnStdout: true,
                        script: "awk -F= '/^url=/{print \$2}' ${env.PMM_HA_SUMMARY}",
                    ).trim()
                    env.PMM_UI_URL = "${pmmAddress}/"
                    env.PMM_URL = "https://admin:${params.ADMIN_PASSWORD}@${pmmAddress.split('//')[1]}"

                    if (!env.RELEASE_DOCKER_VERSION || !env.PMM_UI_URL.startsWith('https://')) {
                        error "Could not read the image and PMM URL out of ${env.PMM_HA_SUMMARY}"
                    }
                    currentBuild.description = "HA upgrade on ${params.CLUSTER_TYPE}. ${env.RELEASE_DOCKER_VERSION} -> ${params.DOCKER_VERSION}"
                    echo "PMM HA ${env.RELEASE_DOCKER_VERSION} serving on ${env.PMM_UI_URL}"
                }
            }
        }
        stage('Install dependencies') {
            steps {
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        npm ci
                        npx playwright install chromium
                    popd
                '''
            }
        }
        stage('Run pre-upgrade tests') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                // Never add --reporter: a CLI reporter replaces the whole config list,
                // including the junit reporter the junit step below consumes.
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${PRE_UPGRADE_TAGS}"
                    popd
                '''
            }
        }
        stage('Upgrade dependencies') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                sh '''
                    /srv/pmm-qa/k8s/install_pmm_ha.sh \
                        --platform "${PLATFORM}" \
                        --charts deps \
                        --chart-branch "${HELM_CHART_BRANCH}" \
                        --summary-file "${PMM_HA_SUMMARY}" \
                        --debug-dir "${WORKSPACE}/pmm-ha-debug"
                '''
            }
        }
        stage('Verify PMM after the dependencies upgrade') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                // Asserts the operators moved while the pmm-ha release, its pods and the
                // version they serve did not.
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${MID_UPGRADE_TAGS}"
                    popd
                '''
            }
        }
        stage('Upgrade PMM HA') {
            options {
                timeout(time: 40, unit: 'MINUTES')
            }
            steps {
                withEnv(["PMM_ADMIN_PASSWORD=${params.ADMIN_PASSWORD}"]) {
                    // Same flags as the install, but the chart comes from the
                    // percona-helm-charts branch instead of the published repo. --charts
                    // pmm-ha leaves the operators exactly as installed.
                    sh '''
                        /srv/pmm-qa/k8s/install_pmm_ha.sh \
                            --platform "${PLATFORM}" \
                            --image "${DOCKER_VERSION}" \
                            --chart-branch "${HELM_CHART_BRANCH}" \
                            --charts pmm-ha \
                            --external-access \
                            --summary-file "${PMM_HA_SUMMARY}" \
                            --debug-dir "${WORKSPACE}/pmm-ha-debug"
                    '''
                }
            }
        }
        stage('Run post-upgrade tests') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${POST_UPGRADE_TAGS}"
                    popd
                '''
            }
        }
    }
    post {
        failure {
            archiveArtifacts artifacts: 'pmm-ha-debug/**', allowEmptyArchive: true
        }
        always {
            sh '''
                if [ -n "${PMM_URL:-}" ]; then
                    curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                fi
                tar -zcvf playwright-report.tar.gz -C /srv/pmm-qa/e2e_tests playwright-report || true

                # The suite runs out of /srv/pmm-qa, but the junit step globs relative
                # to the workspace, so the report has to come back here.
                mkdir -p ${WORKSPACE}/output
                cp /srv/pmm-qa/e2e_tests/output/junit.xml ${WORKSPACE}/output/ || true
            '''
            archiveArtifacts artifacts: 'logs.zip', allowEmptyArchive: true
            archiveArtifacts artifacts: 'playwright-report.tar.gz', allowEmptyArchive: true
            // allowEmptyResults: a run that dies before the test phase writes no XML at
            // all, and that should not mask the real failure.
            // keepLongStdio: Playwright puts the whole error and code frame in the
            // failure body, which the plugin truncates by default.
            junit testResults: 'output/junit.xml', keepLongStdio: true, allowEmptyResults: true,
                skipPublishingChecks: true
            script {
                if (env.CLUSTER_NAME && params.CLUSTER_TYPE == 'EKS') {
                    build job: 'pmm3-ha-eks-cleanup', parameters: [
                        string(name: 'ACTION', value: 'DELETE_CLUSTER'),
                        string(name: 'CLUSTER_NAME', value: env.CLUSTER_NAME),
                    ]
                }
                if (env.CLUSTER_NAME && params.CLUSTER_TYPE == 'OpenShift') {
                    build job: 'pmm3-ha-rosa-cleanup', parameters: [
                        string(name: 'ACTION', value: 'DELETE_CLUSTER'),
                        string(name: 'CLUSTER_NAME', value: env.CLUSTER_NAME),
                    ]
                }
            }
        }
    }
}
