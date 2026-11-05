// PMM HA on Argo CD tests.
//
// Creates a bare EKS cluster, installs PMM HA on it through Argo CD with
// k8s/install_pmm_ha.sh --install-method argocd (--backup-minio, --external-access), attaches
// monitored databases, runs the @ha-argocd tests, then the @pmm-ha suite with Argo CD self-heal
// turned off on both Applications.
//
// @pmm-ha scales StatefulSets and patches Services with kubectl, which self-heal would revert, so
// self-heal is switched off only right before it. A failed @ha-argocd stage stops the job before
// that suite runs. Kept apart from the nightly @pmm-ha run.
//
// HELM_CHART_BRANCH and DEPS_CHART_BRANCH accept OWNER:BRANCH to install a chart from a fork,
// e.g. theTibi:PMM-13858; the charts are cloned from percona-helm-charts.

void provisionCluster() {
    def clusterCreateJob = build job: 'pmm3-ha-eks', parameters: [
        string(name: 'K8S_VERSION', value: params.K8S_VERSION),
        string(name: 'WORKER_COUNT', value: params.EKS_WORKER_COUNT),
        booleanParam(name: 'DEPLOY_PMM', value: false),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: false),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    env.CLUSTER_BUILD_NUMBER = clusterCreateJob.number.toString()
}

def summaryValue(String key) {
    return sh(
        returnStdout: true,
        script: """awk -F= -v k='${key}' '\$1 == k { print \$2 }' '${env.PMM_HA_SUMMARY}' | tail -n 1""",
    ).trim()
}

// Carried over from the ROSA pipelines, where a cluster's API hostname can stop resolving for up to
// 15 minutes after it turns ready. On EKS it only absorbs a short API blip, so the install waits up
// to 20 minutes for the API. kubectl, since this agent has no oc.
def waitForClusterApi() {
    sh '''
        set +x
        for i in $(seq 1 40); do
            if OUT=$(kubectl get --raw /readyz --request-timeout=10s 2>&1); then
                exit 0
            fi
            echo "Cluster API not reachable (${i}/40), retrying in 30s: $(echo "${OUT}" | tail -n 1)"
            sleep 30
        done
        echo "ERROR: the cluster API is still not reachable after 20 minutes."
        exit 1
    '''
}

// Runs body again when it failed because the cluster API went away under it. A failure while
// the API still answers is real and is rethrown at once. body must be safe to repeat.
def withClusterApiRetry(Closure body) {
    def attempts = 3
    for (int i = 1; i <= attempts; i++) {
        waitForClusterApi()
        try {
            body()
            return
        } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            throw e
        } catch (err) {
            def apiUp = sh(returnStatus: true, script: 'kubectl get --raw /readyz --request-timeout=10s >/dev/null 2>&1') == 0
            if (apiUp || i == attempts) {
                throw err
            }
            echo "The cluster API went away during this stage, running it again (attempt ${i + 1}/${attempts})"
        }
    }
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
            choices: ['1.35', '1.34', '1.33'],
            description: 'EKS cluster version',
            name: 'K8S_VERSION')
        string(
            defaultValue: '7',
            description: 'Worker nodes for the EKS cluster, passed to pmm3-ha-eks as WORKER_COUNT',
            name: 'EKS_WORKER_COUNT')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server image under test (image-name:version-tag). The client image follows it.',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'latest-tarball',
            description: 'PMM Client version the monitored databases register with',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'pmm3admin!',
            description: 'pmm-server admin user password',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: 'main',
            description: 'Branch of percona-helm-charts whose pmm-ha chart is installed. OWNER:BRANCH, e.g. theTibi:PMM-13858, clones that owner\'s fork instead.',
            name: 'HELM_CHART_BRANCH')
        string(
            defaultValue: '',
            description: 'Branch of percona-helm-charts whose pmm-ha-dependencies chart is installed; OWNER:BRANCH for a fork. Empty means HELM_CHART_BRANCH.',
            name: 'DEPS_CHART_BRANCH')
        string(
            defaultValue: 'v3.5.6',
            description: 'Argo CD release whose manifests/install.yaml is applied',
            name: 'ARGOCD_VERSION')
        string(
            defaultValue: '--database ps',
            description: 'pmm-framework arguments for the monitored databases. Empty skips the step.',
            name: 'CLIENTS')
        string(
            defaultValue: '@ha-argocd',
            description: 'Playwright --grep tag expression for the Argo CD tests',
            name: 'TAGS_FOR_TESTS')
        string(
            defaultValue: '@pmm-ha',
            description: 'Playwright --grep tag expression for the HA suite run last, with Argo CD self-heal off. Empty skips it.',
            name: 'HA_TAGS')
    }
    options {
        skipDefaultCheckout()
        // Covers post too, and a job timeout that fires during post leaks the cluster: this leaves
        // room for pmm3-ha-eks, a stage running to its timeout, and the deletion.
        timeout(time: 6, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }
    environment {
        PMM_HA_SUMMARY = "${WORKSPACE}/pmm-ha-summary.env"
        // PMM-T2105 in @pmm-ha monitors an RDS instance.
        PMM_QA_AWS_ACCESS_KEY_ID = credentials('PMM_QA_AWS_ACCESS_KEY_ID')
        PMM_QA_AWS_ACCESS_KEY = credentials('PMM_QA_AWS_ACCESS_KEY')
        PMM_QA_MYSQL_RDS_8_4_HOST = credentials('PMM_QA_MYSQL_RDS_8_4_HOST')
        PMM_QA_MYSQL_RDS_8_4_USER = credentials('PMM_QA_MYSQL_RDS_8_4_USER')
        PMM_QA_MYSQL_RDS_8_4_PASSWORD = credentials('PMM_QA_MYSQL_RDS_8_4_PASSWORD')
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    currentBuild.description = "HA on Argo CD, EKS ${params.K8S_VERSION}. Chart: ${params.HELM_CHART_BRANCH}. Server: ${params.DOCKER_VERSION}"
                }
                deleteDir()
                git poll: false, branch: PMM_QA_GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'
                sh '''
                    sudo rm -rf /srv/pmm-qa || :
                    sudo mkdir -p /srv/pmm-qa
                    sudo rsync -a ${WORKSPACE}/ /srv/pmm-qa/
                    sudo chown -R ec2-user:ec2-user /srv/pmm-qa

                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium || :

                    bash -x /srv/pmm-qa/k8s/install_k8s_tools.sh --kubectl --helm --sudo
                    kubectl version --client
                    helm version --short
                '''
            }
        }
        stage('Create the cluster and install dependencies') {
            parallel {
                stage('Create Kubernetes cluster') {
                    steps {
                        script {
                            provisionCluster()
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
            }
        }
        stage('Copy kubeconfig') {
            steps {
                script {
                    copyArtifacts(
                        projectName: 'pmm3-ha-eks',
                        selector: specific(env.CLUSTER_BUILD_NUMBER),
                        filter: 'kubeconfig',
                        target: 'cluster-artifacts',
                    )

                    def kubeconfig = "${WORKSPACE}/cluster-artifacts/kubeconfig"
                    if (!fileExists(kubeconfig)) {
                        error "Failed to copy kubeconfig from pmm3-ha-eks build ${env.CLUSTER_BUILD_NUMBER}"
                    }
                    env.KUBECONFIG = kubeconfig

                    sh '''
                        chmod 600 "${KUBECONFIG}"

                        server=$(kubectl config view --minify --output jsonpath='{.clusters[0].cluster.server}')
                        echo "Waiting for ${server} to answer"

                        for i in $(seq 1 40); do
                            if output=$(kubectl get nodes -o wide 2>&1); then
                                echo "${output}"
                                exit 0
                            fi
                            echo "attempt ${i}/40: $(echo "${output}" | tail -1)"
                            sleep 15
                        done

                        echo "${server} did not answer within 10m. Last error:" >&2
                        echo "${output}" >&2
                        exit 1
                    '''
                }
            }
        }
        stage('Verify the cluster has no PMM') {
            steps {
                sh '''
                    # Their own commands, so a helm or kubectl failure fails the stage rather than reading as absent.
                    releases=$(helm list --all-namespaces --filter '^pmm-ha$' -q)
                    argocd_ns=$(kubectl get namespace argocd --ignore-not-found -o name)
                    if [ -n "${releases}" ] || [ -n "${argocd_ns}" ]; then
                        echo "ERROR: this cluster already has pmm-ha or an argocd namespace." >&2
                        echo "This job needs a bare cluster - check that the create job ran with DEPLOY_PMM=false." >&2
                        helm list --all-namespaces >&2
                        kubectl get namespace >&2
                        exit 1
                    fi
                    echo "No pmm-ha release and no argocd namespace on the cluster, as expected."
                '''
            }
        }
        stage('Install PMM HA with Argo CD') {
            options {
                timeout(time: 50, unit: 'MINUTES')
            }
            steps {
                script {
                    // The client follows the server image, as in pmm3-ha-eks (PMM-15633).
                    def clientImage = params.DOCKER_VERSION.replace('pmm-server', 'pmm-client')
                    withEnv([
                        "PMM_ADMIN_PASSWORD=${params.ADMIN_PASSWORD}",
                        "PMM_CLIENT_IMAGE=${clientImage}",
                        "DEPS_BRANCH=${params.DEPS_CHART_BRANCH ?: params.HELM_CHART_BRANCH}",
                    ]) {
                        // install_pmm_ha.sh keeps an existing pmm-secret and backup secret and
                        // re-applies everything else, so a rerun picks up where the API went away.
                        withClusterApiRetry {
                            sh '''
                                /srv/pmm-qa/k8s/install_pmm_ha.sh \
                                    --platform eks \
                                    --install-method argocd \
                                    --backup-minio \
                                    --external-access \
                                    --clone-dir "${WORKSPACE}/charts" \
                                    --chart-branch "${HELM_CHART_BRANCH}" \
                                    --deps-chart-branch "${DEPS_BRANCH}" \
                                    --argocd-version "${ARGOCD_VERSION}" \
                                    --image "${DOCKER_VERSION}" \
                                    --set "pmmClient.image.repository=${PMM_CLIENT_IMAGE%:*}" \
                                    --set "pmmClient.image.tag=${PMM_CLIENT_IMAGE##*:}" \
                                    --summary-file "${PMM_HA_SUMMARY}" \
                                    --debug-dir "${WORKSPACE}/pmm-ha-debug"
                            '''
                        }
                    }

                    def pmmAddress = summaryValue('url')
                    if (!pmmAddress.startsWith('https://')) {
                        error "Could not read the PMM URL out of ${env.PMM_HA_SUMMARY}"
                    }
                    env.PMM_UI_URL = "${pmmAddress}/"
                    env.PMM_SERVER_ADDRESS = pmmAddress.split('//')[1]
                    env.PMM_URL = "https://admin:${params.ADMIN_PASSWORD}@${env.PMM_SERVER_ADDRESS}"

                    env.PMM_HA_DEPS_CHART_DIR = summaryValue('deps_chart_dir')
                    env.PMM_HA_ARGOCD_APPS = summaryValue('argocd_apps')
                    if (!env.PMM_HA_DEPS_CHART_DIR || !env.PMM_HA_ARGOCD_APPS) {
                        error "Could not read deps_chart_dir and argocd_apps out of ${env.PMM_HA_SUMMARY}"
                    }
                    echo "PMM HA serving on ${env.PMM_UI_URL}, Argo CD applications ${env.PMM_HA_ARGOCD_APPS}"
                }
            }
        }
        stage('Set up monitored databases') {
            when {
                expression { params.CLIENTS }
            }
            options {
                timeout(time: 40, unit: 'MINUTES')
            }
            steps {
                sh '''
                    pushd /srv/pmm-qa/qa-integration/pmm_qa
                        ./pmm-framework/pmm-framework \
                            --parallel \
                            --pmm-server-ip "${PMM_SERVER_ADDRESS}" \
                            --pmm-server-password "${ADMIN_PASSWORD}" \
                            --client-version "${CLIENT_VERSION}" \
                            ${CLIENTS}
                    popd
                '''
            }
        }
        stage('Run Argo CD tests') {
            options {
                // Past Playwright's 60m globalTimeout, so Playwright times out and reports first.
                timeout(time: 75, unit: 'MINUTES')
            }
            steps {
                // No catchError: a failure here must stop the job before the HA suite.
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${TAGS_FOR_TESTS}"
                    popd
                '''
            }
        }
        stage('Run HA tests') {
            when {
                expression { params.HA_TAGS }
            }
            options {
                timeout(time: 75, unit: 'MINUTES')
            }
            steps {
                sh '''
                    # @pmm-ha scales and patches resources with kubectl, which self-heal would revert.
                    for app in $(echo "${PMM_HA_ARGOCD_APPS}" | tr ',' ' '); do
                        kubectl -n argocd patch application "${app}" --type merge \
                            -p '{"spec":{"syncPolicy":{"automated":{"selfHeal":false}}}}'
                    done

                    pushd /srv/pmm-qa/e2e_tests
                        # Each run replaces output/junit.xml and playwright-report/.
                        mv output/junit.xml output/junit-argocd.xml
                        mv playwright-report playwright-report-argocd

                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${HA_TAGS}"
                    popd
                '''
            }
        }
    }
    post {
        // Everything that reads the cluster is in always, which runs before the other
        // conditions and deletes the cluster.
        always {
            script {
                if (env.KUBECONFIG) {
                    sh '''
                        mkdir -p argocd-debug
                        apps="${PMM_HA_ARGOCD_APPS:-pmm-operators,pmm-ha}"
                        timeout 60 kubectl get applications --namespace argocd -o yaml > argocd-debug/applications.yaml 2>&1 || true
                        timeout 60 kubectl logs --namespace argocd statefulset/argocd-application-controller --tail=500 \
                            > argocd-debug/application-controller.log 2>&1 || true
                        timeout 60 kubectl logs --namespace argocd deployment/argocd-repo-server --tail=500 \
                            > argocd-debug/repo-server.log 2>&1 || true
                        for app in $(echo "${apps}" | tr ',' ' '); do
                            timeout 60 kubectl get application "${app}" --namespace argocd -o yaml \
                                > "argocd-debug/application-${app}.yaml" 2>&1 || true
                        done
                    '''
                }
                if (env.KUBECONFIG && currentBuild.currentResult != 'SUCCESS') {
                    sh '''
                        {
                            timeout 60 kubectl get pods,statefulsets,deployments,jobs --namespace pmm -o wide
                            timeout 60 kubectl get events --namespace pmm --sort-by=.lastTimestamp
                            for pod in $(timeout 60 kubectl get pods --namespace pmm --no-headers \
                                    | awk '{ split($2, ready, "/"); if (ready[1] != ready[2] && $3 != "Completed") print $1 }'); do
                                timeout 60 kubectl describe pod --namespace pmm "${pod}"
                            done
                        } > cluster-state.txt 2>&1 || true
                    '''
                }
            }
            sh '''
                if [ -n "${PMM_URL:-}" ]; then
                    curl --fail --max-time 120 --insecure "${PMM_URL}/logs.zip" --output logs.zip || rm -f logs.zip
                fi
                tar -zcvf playwright-report.tar.gz -C /srv/pmm-qa/e2e_tests \
                    playwright-report-argocd playwright-report || true

                # The suite runs out of /srv/pmm-qa, but the junit step globs relative
                # to the workspace, so the reports have to come back here.
                mkdir -p ${WORKSPACE}/output
                cp /srv/pmm-qa/e2e_tests/output/junit*.xml ${WORKSPACE}/output/ || true
            '''
            archiveArtifacts artifacts: 'cluster-state.txt, logs.zip, playwright-report.tar.gz, pmm-ha-debug/**, argocd-debug/**', allowEmptyArchive: true
            junit testResults: 'output/junit*.xml', keepLongStdio: true, allowEmptyResults: true,
                skipPublishingChecks: true
            script {
                if (env.CLUSTER_NAME) {
                    build job: 'pmm3-ha-eks-cleanup', parameters: [
                        string(name: 'ACTION', value: 'DELETE_CLUSTER'),
                        string(name: 'CLUSTER_NAME', value: env.CLUSTER_NAME),
                    ]
                }
            }
        }
    }
}
