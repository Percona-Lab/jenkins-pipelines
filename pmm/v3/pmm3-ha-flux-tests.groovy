// PMM HA on Flux tests.
//
// Creates a bare EKS cluster, installs Flux, then PMM HA on it as two HelmReleases (pmm-operators
// and pmm-ha) with k8s/install_pmm_ha.sh --install-method flux (--backup-minio, --external-access),
// attaches monitored databases, runs the @ha-flux tests, then the @pmm-ha suite with both
// HelmReleases suspended.
//
// @pmm-ha scales StatefulSets and patches Services with kubectl, which helm-controller would revert
// on its next reconcile, so the HelmReleases are suspended only right before it. A failed @ha-flux
// stage stops the job before that suite runs. Kept apart from the nightly @pmm-ha run.
//
// By default the released pmm-ha and pmm-ha-dependencies charts are installed (HELM_CHART_BRANCH
// latest); HELM_CHART_VERSION and DEPS_CHART_VERSION pin a released version. Any other
// HELM_CHART_BRANCH or DEPS_CHART_BRANCH installs from a percona-helm-charts branch, and
// OWNER:BRANCH, e.g. theTibi:PMM-13858, from a fork. The version parameters apply only to a
// released chart. A default run is expected to fail at install until the gitops chart change is
// released.
//
// Cleanup runs in post, in this order: archive flux-debug/, scale helm-controller to 0 so it cannot
// recreate anything, delete the LoadBalancer Services (their NLBs would otherwise fail the VPC
// delete), then pmm3-ha-eks-cleanup deletes the cluster.

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
            defaultValue: '12',
            description: 'Worker nodes for the EKS cluster, passed to pmm3-ha-eks as WORKER_COUNT. The chart is installed with its own resource requests, which need 12 (pmm3-ha-eks fits 7 only with its minimal profile).',
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
            defaultValue: 'latest',
            description: '`latest` installs the released pmm-ha chart; otherwise a percona-helm-charts branch, or OWNER:BRANCH for a fork, e.g. theTibi:PMM-13858.',
            name: 'HELM_CHART_BRANCH')
        string(
            defaultValue: '',
            description: 'Released pmm-ha chart version. Empty means the newest release. Only with HELM_CHART_BRANCH latest.',
            name: 'HELM_CHART_VERSION')
        string(
            defaultValue: '',
            description: 'percona-helm-charts branch (or `latest`, or OWNER:BRANCH) for the pmm-ha-dependencies chart. Empty means the same source as HELM_CHART_BRANCH.',
            name: 'DEPS_CHART_BRANCH')
        string(
            defaultValue: '',
            description: 'Released pmm-ha-dependencies chart version. Empty means the newest release. Only with a released chart.',
            name: 'DEPS_CHART_VERSION')
        string(
            defaultValue: 'v2.10.0',
            description: 'Flux release whose install.yaml is applied',
            name: 'FLUX_VERSION')
        string(
            defaultValue: '--database ps',
            description: 'pmm-framework arguments for the monitored databases. Empty skips the step.',
            name: 'CLIENTS')
        string(
            defaultValue: '@ha-flux',
            description: 'Playwright --grep tag expression for the Flux tests',
            name: 'TAGS_FOR_TESTS')
        string(
            defaultValue: '@pmm-ha',
            description: 'Playwright --grep tag expression for the HA suite run last, with both HelmReleases suspended. Empty skips it.',
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
                    def chartVersions = [params.HELM_CHART_VERSION, params.DEPS_CHART_VERSION].findAll { it }.join(', ')
                    currentBuild.description = "HA on Flux, EKS ${params.K8S_VERSION}. Chart: ${params.HELM_CHART_BRANCH ?: ''}${chartVersions ? " (${chartVersions})" : ''}. Server: ${params.DOCKER_VERSION ?: ''}"
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
                    flux_ns=$(kubectl get namespace flux-system --ignore-not-found -o name)
                    if [ -n "${releases}" ] || [ -n "${flux_ns}" ]; then
                        echo "ERROR: this cluster already has pmm-ha or a flux-system namespace." >&2
                        echo "This job needs a bare cluster - check that the create job ran with DEPLOY_PMM=false." >&2
                        helm list --all-namespaces >&2
                        kubectl get namespace >&2
                        exit 1
                    fi
                    echo "No pmm-ha release and no flux-system namespace on the cluster, as expected."
                '''
            }
        }
        stage('Install PMM HA with Flux') {
            options {
                timeout(time: 50, unit: 'MINUTES')
            }
            steps {
                script {
                    // The client follows the server image, as in pmm3-ha-eks (PMM-15633).
                    def clientImage = (params.DOCKER_VERSION ?: '').replace('pmm-server', 'pmm-client')
                    withEnv([
                        "PMM_ADMIN_PASSWORD=${params.ADMIN_PASSWORD ?: ''}",
                        "PMM_CLIENT_IMAGE=${clientImage}",
                        "HELM_CHART_BRANCH=${params.HELM_CHART_BRANCH ?: ''}",
                        "DEPS_BRANCH=${params.DEPS_CHART_BRANCH ?: params.HELM_CHART_BRANCH ?: ''}",
                        "HELM_CHART_VERSION=${params.HELM_CHART_VERSION ?: ''}",
                        "DEPS_CHART_VERSION=${params.DEPS_CHART_VERSION ?: ''}",
                        "FLUX_VERSION=${params.FLUX_VERSION ?: ''}",
                        "DOCKER_VERSION=${params.DOCKER_VERSION ?: ''}",
                    ]) {
                        // install_pmm_ha.sh keeps an existing pmm-secret and backup secret and
                        // re-applies everything else, so a rerun picks up where the API went away.
                        withClusterApiRetry {
                            sh '''
                                set --
                                if [ -n "${HELM_CHART_VERSION}" ]; then
                                    set -- "$@" --chart-version "${HELM_CHART_VERSION}"
                                fi
                                if [ -n "${DEPS_CHART_VERSION}" ]; then
                                    set -- "$@" --deps-chart-version "${DEPS_CHART_VERSION}"
                                fi

                                /srv/pmm-qa/k8s/install_pmm_ha.sh \
                                    --platform eks \
                                    --install-method flux \
                                    --backup-minio \
                                    --external-access \
                                    --clone-dir "${WORKSPACE}/charts" \
                                    --chart-branch "${HELM_CHART_BRANCH}" \
                                    --deps-chart-branch "${DEPS_BRANCH}" \
                                    "$@" \
                                    --flux-version "${FLUX_VERSION}" \
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
                    env.PMM_URL = "https://admin:${params.ADMIN_PASSWORD ?: ''}@${env.PMM_SERVER_ADDRESS}"

                    env.PMM_HA_DEPS_CHART_DIR = summaryValue('deps_chart_dir')
                    env.PMM_HA_FLUX_RELEASES = summaryValue('flux_releases')
                    env.PMM_HA_FLUX_SOURCES = summaryValue('flux_sources')
                    env.PMM_HA_CHART_VERSION = summaryValue('chart_version')
                    if (!env.PMM_HA_DEPS_CHART_DIR || !env.PMM_HA_FLUX_RELEASES || !env.PMM_HA_FLUX_SOURCES) {
                        error "Could not read deps_chart_dir, flux_releases and flux_sources out of ${env.PMM_HA_SUMMARY}"
                    }
                    echo "PMM HA serving on ${env.PMM_UI_URL}, Flux HelmReleases ${env.PMM_HA_FLUX_RELEASES}"
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
        stage('Run Flux tests') {
            options {
                // Past the 120m --global-timeout, so Playwright times out and reports first.
                timeout(time: 135, unit: 'MINUTES')
            }
            steps {
                // No catchError: a failure here must stop the job before the HA suite.
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${TAGS_FOR_TESTS}" --global-timeout=7200000
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
                // Patching is safe to repeat, so an API blip does not fail a build after a green @ha-flux.
                withClusterApiRetry {
                    sh '''
                        # Suspended, helm-controller no longer reverts what @pmm-ha does.
                        for release in $(echo "${PMM_HA_FLUX_RELEASES}" | tr ',' ' '); do
                            kubectl -n pmm patch helmreleases.helm.toolkit.fluxcd.io "${release}" --type=merge \
                                -p '{"spec":{"suspend":true}}'
                        done
                    '''
                }
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        # Each run replaces output/junit.xml and playwright-report/.
                        mv output/junit.xml output/junit-flux.xml
                        mv playwright-report playwright-report-flux

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
                        mkdir -p flux-debug
                        releases="${PMM_HA_FLUX_RELEASES:-pmm-operators,pmm-ha}"
                        timeout 60 kubectl get helmreleases.helm.toolkit.fluxcd.io --namespace pmm -o yaml \
                            > flux-debug/helmreleases.yaml 2>&1 || true
                        timeout 60 kubectl get gitrepositories.source.toolkit.fluxcd.io --namespace pmm -o yaml \
                            > flux-debug/gitrepositories.yaml 2>&1 || true
                        timeout 60 kubectl get helmrepositories.source.toolkit.fluxcd.io --namespace pmm -o yaml \
                            > flux-debug/helmrepositories.yaml 2>&1 || true
                        timeout 60 kubectl get events --namespace pmm --sort-by=.lastTimestamp \
                            > flux-debug/events.txt 2>&1 || true
                        timeout 60 kubectl logs --namespace flux-system deployment/helm-controller --tail=500 \
                            > flux-debug/helm-controller.log 2>&1 || true
                        timeout 60 kubectl logs --namespace flux-system deployment/source-controller --tail=500 \
                            > flux-debug/source-controller.log 2>&1 || true
                        for release in $(echo "${releases}" | tr ',' ' '); do
                            timeout 60 kubectl get helmreleases.helm.toolkit.fluxcd.io "${release}" --namespace pmm -o yaml \
                                > "flux-debug/helmrelease-${release}.yaml" 2>&1 || true
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
                    playwright-report-flux playwright-report || true

                # The suite runs out of /srv/pmm-qa, but the junit step globs relative
                # to the workspace, so the reports have to come back here.
                mkdir -p ${WORKSPACE}/output
                cp /srv/pmm-qa/e2e_tests/output/junit*.xml ${WORKSPACE}/output/ || true
            '''
            archiveArtifacts artifacts: 'cluster-state.txt, logs.zip, playwright-report.tar.gz, pmm-ha-debug/**, flux-debug/**', allowEmptyArchive: true
            junit testResults: 'output/junit*.xml', keepLongStdio: true, allowEmptyResults: true,
                skipPublishingChecks: true
            script {
                // eksctl deletes the LoadBalancer Services before the VPC, but helm-controller would
                // recreate HAProxy's, and its NLB then fails the stack delete.
                if (env.KUBECONFIG) {
                    sh '''
                        timeout 120 kubectl scale deployment helm-controller --namespace flux-system --replicas=0 || true
                        timeout 120 kubectl wait --for=delete pod --namespace flux-system \
                            --selector app=helm-controller --timeout=90s || true
                        for svc in $(timeout 60 kubectl get services --namespace pmm \
                                -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.metadata.name}{" "}{end}'); do
                            timeout 300 kubectl delete service --namespace pmm "${svc}" --wait=true || true
                        done
                    '''
                }
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
