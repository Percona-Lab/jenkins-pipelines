library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

library changelog: false, identifier: 'v3lib@master', retriever: modernSCM(
  scm: [$class: 'GitSCMSource', remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'],
  libraryPath: 'pmm/v3/'
)

void runHAClusterCreate(String OCP_VERSION, DOCKER_VERSION, HELM_CHART_BRANCH, ADMIN_PASSWORD) {
    def pmmImageRepo = DOCKER_VERSION.split(":")[0]
    def pmmImageTag = DOCKER_VERSION.split(":")[1]

    def clusterCreateJob = build job: 'pmm3-ha-rosa', parameters: [
        string(name: 'OCP_VERSION', value: OCP_VERSION),
        string(name: 'HELM_CHART_BRANCH', value: HELM_CHART_BRANCH),
        string(name: 'PMM_IMAGE_REPOSITORY', value: pmmImageRepo),
        string(name: 'PMM_IMAGE_TAG', value: pmmImageTag),
        string(name: 'PMM_ADMIN_PASSWORD', value: ADMIN_PASSWORD),
        booleanParam(name: 'ENABLE_EXTERNAL_ACCESS', value: true),
        string(name: 'RETENTION_DAYS', value: '1'),
    ]

    def pmmAddress = clusterCreateJob.buildVariables.PMM_URL
    def pmmHostname = pmmAddress.split("//")[1]

    env.SERVER_IP = pmmHostname
    env.CLUSTER_NAME = clusterCreateJob.buildVariables.CLUSTER_NAME
    // Pin the artifact copy to this exact build - 'pmm3-ha-rosa' may run
    // concurrently for other requesters, so lastSuccessful is not safe here.
    env.HA_BUILD_NUMBER = clusterCreateJob.number.toString()
    env.PMM_URL = "https://admin:${ADMIN_PASSWORD}@${pmmHostname}"
    env.PMM_UI_URL = "${pmmAddress}/"
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
        string(
            defaultValue: 'perconalab/pmm-server:3-dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: 'latest-tarball',
            description: 'PMM Client version',
            name: 'CLIENT_VERSION')
        string(
            defaultValue: 'pmm3admin!',
            description: 'pmm-server admin user default password',
            name: 'ADMIN_PASSWORD')
        string(
            defaultValue: 'main',
            description: 'HA setup branch of percona-helm-charts repo',
            name: 'HELM_CHART_BRANCH')
        choice(
            choices: ['4.21', '4.20', '4.19', '4.18'],
            description: 'OpenShift (ROSA) cluster version for the PMM HA setup',
            name: 'OCP_VERSION')
        string(
            defaultValue: '@pmm-ha',
            description: 'Playwright --grep tag expression selecting the HA e2e tests to run.',
            name: 'TAGS_FOR_TESTS')
        string(
            defaultValue: '',
            description: 'Optional pmm-framework arguments to provision monitored DBs on this agent (e.g. "--database ps=8.4").',
            name: 'CLIENTS')
    }
    options {
        skipDefaultCheckout()
    }
    triggers { cron('0 2 * * *') }
    stages {
        stage('Prepare') {
            steps {
                script {
                    currentBuild.description = "HA on OCP ${env.OCP_VERSION}. Server: ${env.DOCKER_VERSION}. Client: ${env.CLIENT_VERSION}. Tags: ${env.TAGS_FOR_TESTS}"
                }
                deleteDir()
                git poll: false, branch: PMM_QA_GIT_BRANCH, url: 'https://github.com/percona/pmm-qa.git'
                sh '''
                    sudo rm -rf /srv/pmm-qa || :
                    sudo mkdir -p /srv/pmm-qa
                    sudo rsync -a ${WORKSPACE}/ /srv/pmm-qa/
                    sudo chown -R ec2-user:ec2-user /srv/pmm-qa

                    sudo ln -s /usr/bin/chromium-browser /usr/bin/chromium || :

                    # kubectl is needed to reach the HA cluster with the copied kubeconfig
                    bash -x /srv/pmm-qa/k8s/install_k8s_tools.sh --kubectl --sudo
                    kubectl version --client
                '''
                slackSend botUser: true, channel: '#pmm-notifications', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Start PMM HA Server') {
            steps {
                runHAClusterCreate(OCP_VERSION, DOCKER_VERSION, HELM_CHART_BRANCH, ADMIN_PASSWORD)
            }
        }
        stage('Copy kubeconfig') {
            steps {
                script {
                    copyArtifacts(
                        projectName: 'pmm3-ha-rosa',
                        selector: specific(env.HA_BUILD_NUMBER),
                        filter: 'kubeconfig',
                        target: 'cluster-artifacts',
                    )

                    def kubeconfig = "${WORKSPACE}/cluster-artifacts/kubeconfig"
                    if (!fileExists(kubeconfig)) {
                        error "Failed to copy kubeconfig from pmm3-ha-rosa build ${env.HA_BUILD_NUMBER}"
                    }
                    env.KUBECONFIG = kubeconfig

                    // The ROSA kubeconfig carries its own cluster-admin token,
                    // so kubectl needs no extra credentials here.
                    sh '''
                        chmod 600 "${KUBECONFIG}"
                        for i in $(seq 1 6); do
                            if kubectl get nodes >/dev/null 2>&1; then
                                echo "Successfully connected to the PMM HA cluster"
                                kubectl get nodes -o wide
                                kubectl get pods -n pmm
                                exit 0
                            fi
                            echo "Waiting for cluster to be accessible... (attempt $i/6)"
                            sleep 10
                        done
                        echo "Failed to connect to the PMM HA cluster" >&2
                        exit 1
                    '''
                }
            }
        }
        stage('Sanity check') {
            steps {
                sh '''
                    timeout 300 bash -c 'until [ "$(curl -ks -o /dev/null -w "%{http_code}" ${PMM_URL}/v1/server/readyz)" = "200" ]; do sleep 5; done' || false
                '''
            }
        }
        stage('Disable upgrade on HA PMM instance') {
            steps {
                sh '''
                    curl --location -i --insecure --request PUT \
                        --user "admin:${ADMIN_PASSWORD}" \
                        "${PMM_UI_URL}v1/server/settings" \
                        --header "Content-Type: application/json" \
                        --data '{ "enable_updates": false }'
                '''
            }
        }
        stage('Setup PMM Client') {
            steps {
                 sh '''
                   echo "started client setup"
                 '''
                 setupPMM3Client(SERVER_IP, CLIENT_VERSION.trim(), 'pmm', 'no', 'no', 'no', 'compose_setup', ADMIN_PASSWORD, 'no')
                 sh '''
                   echo "installed local client"
                 '''
                script {
                        env.PMM_REPO = params.CLIENT_VERSION == "pmm3-rc" ? "testing" : "experimental"
                }
                sh '''
                        set -o errexit
                        set -o xtrace
                        # Exit if no CLIENTS are provided
                        [ -z "${CLIENTS// }" ] && exit 0

                        export PATH=$PATH:/usr/sbin
                        export PMM_CLIENT_VERSION=${CLIENT_VERSION}
                        if [ "${CLIENT_VERSION}" = 3-dev-latest ]; then
                            export PMM_CLIENT_VERSION="3-dev-latest"
                        fi

                        sudo rm -rf /srv/pmm-qa
                        sudo mkdir -p /srv/pmm-qa
                        sudo rsync -a ${WORKSPACE}/ /srv/pmm-qa/
                        sudo chown -R ec2-user:ec2-user /srv/pmm-qa

                        pushd /srv/pmm-qa/qa-integration/pmm_qa
                            echo "Setting docker based PMM clients"

                            ./pmm-framework/pmm-framework \
                                --pmm-server-password=${ADMIN_PASSWORD} \
                                --client-version=${PMM_CLIENT_VERSION} \
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
        stage('Run HA e2e tests') {
            options {
                timeout(time: 60, unit: "MINUTES")
            }
            steps {
                // KUBECONFIG is what lets K8sHelper drive the failover steps;
                // without it the cluster-level HA tests skip themselves
                // instead of failing.
                sh '''
                    pushd /srv/pmm-qa/e2e_tests
                        export CI=true
                        export CHROMIUM_PATH=/usr/bin/chromium
                        export PMM_K8S_NAMESPACE=pmm
                        npx playwright test --grep "${TAGS_FOR_TESTS}"
                    popd
                '''
            }
        }
    }
    post {
        always {
            sh '''
                if [ -n "${PMM_URL:-}" ]; then
                    curl --insecure ${PMM_URL}/logs.zip --output logs.zip || true
                fi
                tar -zcvf playwright-report.tar.gz -C /srv/pmm-qa/e2e_tests playwright-report || true
            '''
            archiveArtifacts artifacts: 'logs.zip', allowEmptyArchive: true
            archiveArtifacts artifacts: 'playwright-report.tar.gz', allowEmptyArchive: true
            script {
                if (env.CLUSTER_NAME) {
                    build job: 'pmm3-ha-rosa-cleanup', parameters: [
                        string(name: 'ACTION', value: 'DELETE_CLUSTER'),
                        string(name: 'CLUSTER_NAME', value: env.CLUSTER_NAME),
                    ]
                }
            }
        }
        success {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#00FF00',
                    message: "[${JOB_NAME}]: build finished - ${BUILD_URL}"
            }
        }
        failure {
            script {
                slackSend botUser: true, channel: '#pmm-notifications', color: '#FF0000',
                    message: "[${JOB_NAME}]: build ${currentBuild.result} - ${BUILD_URL}"
            }
        }
    }
}
