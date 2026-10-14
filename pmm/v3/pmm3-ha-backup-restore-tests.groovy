// PMM HA backup and restore tests.
//
// Creates a bare Kubernetes cluster, installs PMM HA on it with backups to an in-cluster
// MinIO (k8s/install_pmm_ha.sh --backup-minio), attaches monitored databases, runs the
// @ha-backup-restore tests, then the @pmm-ha suite on the restored cluster.
//
// A restore re-bootstraps the HA Raft cluster and cycles VictoriaMetrics, so @pmm-ha last
// asks whether failover and ingestion still work afterwards; the databases are attached
// before the backup so the restore has to carry their registrations across. Kept apart from
// the nightly @pmm-ha run: a restore that fails half way leaves PMM scaled to 0, and the
// failed stage stops this job before the suite runs against it.
//
// Backup and restore ship in the pmm-ha chart (files/pmm-backup.sh), so point
// HELM_CHART_REPO / HELM_CHART_BRANCH at a chart PR to test it before it merges.

void runEKSClusterCreate(String k8sVersion) {
    def clusterCreateJob = build job: 'pmm3-ha-eks', parameters: [
        string(name: 'K8S_VERSION', value: k8sVersion),
        string(name: 'WORKER_COUNT', value: params.EKS_WORKER_COUNT),
        booleanParam(name: 'DEPLOY_PMM', value: false),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: false),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    env.PLATFORM = 'eks'
    env.CLUSTER_JOB_NAME = 'pmm3-ha-eks'
    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    env.CLUSTER_BUILD_NUMBER = clusterCreateJob.number.toString()
}

void runOpenShiftClusterCreate(String ocpVersion) {
    def clusterCreateJob = build job: 'pmm3-ha-rosa', parameters: [
        string(name: 'OCP_VERSION', value: ocpVersion),
        string(name: 'WORKER_COUNT', value: params.WORKER_COUNT),
        booleanParam(name: 'DEPLOY_PMM', value: false),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: false),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    env.PLATFORM = 'openshift'
    env.CLUSTER_JOB_NAME = 'pmm3-ha-rosa'
    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    env.CLUSTER_BUILD_NUMBER = clusterCreateJob.number.toString()
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
            // A restore Job shares the backup-tools node (its volume is ReadWriteOnce) and
            // adds a temporary pod per vmstorage and PMM ordinal, so it needs headroom.
            choices: ['6', '5', '4'],
            description: 'Worker nodes for the OpenShift cluster. 6 is the size the restore was validated on.',
            name: 'WORKER_COUNT')
        choice(
            choices: ['7', '8', '9', '6'],
            description: 'Worker nodes for the EKS cluster. Used only when CLUSTER_TYPE = EKS.',
            name: 'EKS_WORKER_COUNT')
        choice(
            choices: ['4.21', '4.20', '4.19', '4.18'],
            description: 'OpenShift (ROSA) cluster version. Used only when CLUSTER_TYPE = OpenShift.',
            name: 'OCP_VERSION')
        choice(
            choices: ['1.35', '1.34', '1.33'],
            description: 'EKS cluster version. Used only when CLUSTER_TYPE = EKS.',
            name: 'K8S_VERSION')
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server image under test (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'https://github.com/percona/percona-helm-charts.git',
            description: 'percona-helm-charts repository to clone the chart from; a fork for a chart PR opened from one',
            name: 'HELM_CHART_REPO')
        string(
            defaultValue: 'PMM-HA-GA',
            description: 'Branch of HELM_CHART_REPO whose pmm-ha and pmm-ha-dependencies charts are installed. It must carry backup support (PMM-13858). A fork PR\'s owner:branch label, e.g. theTibi:PMM-13858, clones that owner\'s fork instead.',
            name: 'HELM_CHART_BRANCH')
        string(
            defaultValue: 'pmm3admin!',
            description: 'pmm-server admin user password',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: '--database psmdb --database ps',
            description: 'pmm-framework arguments for the databases monitored across the backup and restore. Empty skips the step. No PostgreSQL by default: PMM-T2108 in @pmm-ha expects only the chart\'s own three PostgreSQL services.',
            name: 'CLIENTS')
        string(
            defaultValue: 'latest-tarball',
            description: 'PMM Client version the monitored databases register with',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: '@ha-backup-restore',
            description: 'Playwright --grep tag expression for the backup and restore tests',
            name: 'TAGS_FOR_TESTS')
        string(
            defaultValue: '@pmm-ha',
            description: 'Playwright --grep tag expression for the HA suite run last, on the restored cluster. Empty skips it.',
            name: 'HA_TAGS')
    }
    options {
        skipDefaultCheckout()
        timeout(time: 3, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }
    environment {
        PMM_HA_SUMMARY = "${WORKSPACE}/pmm-ha-summary.env"
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    def clusterVersion = params.CLUSTER_TYPE == 'EKS' ? params.K8S_VERSION : params.OCP_VERSION
                    currentBuild.description = "HA backup/restore on ${params.CLUSTER_TYPE} ${clusterVersion}. Chart: ${params.HELM_CHART_BRANCH}. Server: ${params.DOCKER_VERSION}"
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
                        filter: 'kubeconfig',
                        target: 'cluster-artifacts',
                    )

                    def kubeconfig = "${WORKSPACE}/cluster-artifacts/kubeconfig"
                    if (!fileExists(kubeconfig)) {
                        error "Failed to copy kubeconfig from ${env.CLUSTER_JOB_NAME} build ${env.CLUSTER_BUILD_NUMBER}"
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
        stage('Install PMM HA with backups') {
            options {
                timeout(time: 40, unit: 'MINUTES')
            }
            steps {
                script {
                    withEnv(["PMM_ADMIN_PASSWORD=${params.ADMIN_PASSWORD}", "CHART_REPO=${params.HELM_CHART_REPO}"]) {
                        sh '''
                            /srv/pmm-qa/k8s/install_pmm_ha.sh \
                                --platform "${PLATFORM}" \
                                --image "${DOCKER_VERSION}" \
                                --chart-branch "${HELM_CHART_BRANCH}" \
                                --backup-minio \
                                --external-access \
                                --summary-file "${PMM_HA_SUMMARY}" \
                                --debug-dir "${WORKSPACE}/pmm-ha-debug"
                        '''
                    }

                    def pmmAddress = sh(
                        returnStdout: true,
                        script: "awk -F= '/^url=/{print \$2}' ${env.PMM_HA_SUMMARY}",
                    ).trim()
                    if (!pmmAddress.startsWith('https://')) {
                        error "Could not read the PMM URL out of ${env.PMM_HA_SUMMARY}"
                    }
                    env.PMM_UI_URL = "${pmmAddress}/"
                    env.PMM_SERVER_ADDRESS = pmmAddress.split('//')[1]
                    env.PMM_URL = "https://admin:${params.ADMIN_PASSWORD}@${pmmAddress.split('//')[1]}"
                    echo "PMM HA serving on ${env.PMM_UI_URL}"
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
        stage('Run backup and restore tests') {
            options {
                timeout(time: 60, unit: 'MINUTES')
            }
            steps {
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${TAGS_FOR_TESTS}"
                    popd
                '''
            }
        }
        stage('Run HA tests on the restored cluster') {
            when {
                expression { params.HA_TAGS }
            }
            options {
                timeout(time: 60, unit: 'MINUTES')
            }
            steps {
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        npx playwright test --grep "${HA_TAGS}"
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
            // In always, not failure: always runs first and deletes the cluster. The tests
            // delete their backup and restore Jobs, so the runs' logs survive only on the
            // backup-tools volume.
            sh '''
                if [ -n "${PMM_URL:-}" ]; then
                    curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                    kubectl exec --namespace pmm deployment/pmm-ha-backup-tools -- \
                        sh -c 'cd /backups && tar -czf - logs' > pmm-backup-logs.tar.gz || true
                fi
                tar -zcvf playwright-report.tar.gz -C /srv/pmm-qa/e2e_tests playwright-report || true

                # The suite runs out of /srv/pmm-qa, but the junit step globs relative
                # to the workspace, so the report has to come back here.
                mkdir -p ${WORKSPACE}/output
                cp /srv/pmm-qa/e2e_tests/output/junit.xml ${WORKSPACE}/output/ || true
            '''
            archiveArtifacts artifacts: 'logs.zip', allowEmptyArchive: true
            archiveArtifacts artifacts: 'pmm-backup-logs.tar.gz', allowEmptyArchive: true
            archiveArtifacts artifacts: 'playwright-report.tar.gz', allowEmptyArchive: true
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
