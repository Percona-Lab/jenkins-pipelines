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

void provisionCluster() {
    def eks = params.CLUSTER_TYPE == 'EKS'
    def jobName = eks ? 'pmm3-ha-eks' : 'pmm3-ha-rosa'
    def clusterCreateJob = build job: jobName, parameters: [
        string(name: eks ? 'K8S_VERSION' : 'OCP_VERSION', value: eks ? params.K8S_VERSION : params.OCP_VERSION),
        string(name: 'WORKER_COUNT', value: eks ? params.EKS_WORKER_COUNT : params.WORKER_COUNT),
        booleanParam(name: 'DEPLOY_PMM', value: false),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: false),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    env.PLATFORM = eks ? 'eks' : 'openshift'
    env.CLUSTER_JOB_NAME = jobName
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
            choices: ['12', '11', '10', '9'],
            description: 'Worker nodes for the EKS cluster. Used only when CLUSTER_TYPE = EKS. 12 is what pmm3-ha-eks gives the unmodified chart values. EKS has not been run end to end yet, and its workers are spot instances that can be reclaimed while the restore has PMM down.',
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
        // Covers post too, and a job timeout that fires during post leaks the cluster: this leaves
        // room for pmm3-ha-rosa's own 2h, a stage running to its timeout, and the deletion.
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
                    # Its own command, so a helm failure fails the stage rather than reading as no release.
                    releases=$(helm list --namespace pmm --filter '^pmm-ha$' -q)
                    if [ -n "${releases}" ]; then
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
                    // The client follows the server image, as in pmm3-ha-rosa and pmm3-ha-eks (PMM-15633).
                    def clientImage = params.DOCKER_VERSION.replace('pmm-server', 'pmm-client')
                    withEnv([
                        "PMM_ADMIN_PASSWORD=${params.ADMIN_PASSWORD}",
                        "CHART_REPO=${params.HELM_CHART_REPO}",
                        "PMM_CLIENT_IMAGE=${clientImage}",
                    ]) {
                        sh '''
                            /srv/pmm-qa/k8s/install_pmm_ha.sh \
                                --platform "${PLATFORM}" \
                                --image "${DOCKER_VERSION}" \
                                --chart-branch "${HELM_CHART_BRANCH}" \
                                --backup-minio \
                                --external-access \
                                --set "pmmClient.image.repository=${PMM_CLIENT_IMAGE%:*}" \
                                --set "pmmClient.image.tag=${PMM_CLIENT_IMAGE##*:}" \
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
                    env.PMM_URL = "https://admin:${params.ADMIN_PASSWORD}@${env.PMM_SERVER_ADDRESS}"
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
        stage('Run backup and restore tests') {
            options {
                // Past Playwright's 60m globalTimeout, so Playwright times out and reports first.
                timeout(time: 75, unit: 'MINUTES')
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
                timeout(time: 75, unit: 'MINUTES')
            }
            steps {
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        # Each run replaces output/junit.xml and playwright-report/.
                        mv output/junit.xml output/junit-backup-restore.xml
                        mv playwright-report playwright-report-backup-restore

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
        // conditions and deletes the cluster; unsuccessful also covers ABORTED.
        unsuccessful {
            archiveArtifacts artifacts: 'pmm-ha-debug/**', allowEmptyArchive: true
        }
        always {
            script {
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
                    playwright-report-backup-restore playwright-report || true

                # The suite runs out of /srv/pmm-qa, but the junit step globs relative
                # to the workspace, so the reports have to come back here.
                mkdir -p ${WORKSPACE}/output
                cp /srv/pmm-qa/e2e_tests/output/junit*.xml ${WORKSPACE}/output/ || true
            '''
            archiveArtifacts artifacts: 'cluster-state.txt, logs.zip, playwright-report.tar.gz', allowEmptyArchive: true
            junit testResults: 'output/junit*.xml', keepLongStdio: true, allowEmptyResults: true,
                skipPublishingChecks: true
            script {
                if (env.CLUSTER_NAME) {
                    build job: "${env.CLUSTER_JOB_NAME}-cleanup", parameters: [
                        string(name: 'ACTION', value: 'DELETE_CLUSTER'),
                        string(name: 'CLUSTER_NAME', value: env.CLUSTER_NAME),
                    ]
                }
            }
        }
    }
}
